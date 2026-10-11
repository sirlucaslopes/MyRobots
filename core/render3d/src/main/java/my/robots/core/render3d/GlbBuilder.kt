package my.robots.core.render3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Monta um arquivo .glb (glTF 2.0 binário) na memória.
 *
 * O Filament só desenha com materiais compilados; o gltfio já traz os dele (ubershader), então o
 * robô de teste, a grade e os eixos viram um .glb gerado aqui e passam pelo mesmo caminho de um
 * arquivo aberto pelo usuário. Cada nó tem nome (o nome da peça) para o 3D achar e mover.
 *
 * Coordenadas em metros, como pede o glTF. Kotlin puro: testado na JVM.
 */
class GlbBuilder {

    /** Cor em RGB linear (0..1). [unlit] ignora a luz (grade e eixos ficam sempre da mesma cor). */
    data class Material(
        val name: String,
        val r: Float,
        val g: Float,
        val b: Float,
        val unlit: Boolean = false,
        val metallic: Float = 0.1f,
        val roughness: Float = 0.6f,
    )

    private class Prim(val mesh: MeshData, val material: Int)
    private class Node(val name: String, val prims: List<Prim>)

    private val materials = mutableListOf<Material>()
    private val nodes = mutableListOf<Node>()

    fun addMaterial(m: Material): Int {
        materials += m
        return materials.size - 1
    }

    /** Um nó com uma malha; cada par (malha, material) vira uma primitiva. Malhas vazias são puladas. */
    fun addNode(name: String, parts: List<Pair<MeshData, Int>>) {
        val prims = parts.filter { !it.first.isEmpty() }.map { (mesh, mat) ->
            require(mat in materials.indices) { "Material $mat não existe" }
            Prim(mesh, mat)
        }
        nodes += Node(name, prims)
    }

    fun build(): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder()
        val accessors = StringBuilder()
        var viewCount = 0
        var accessorCount = 0

        fun addView(bytes: ByteArray, target: Int): Int {
            val offset = bin.size()
            bin.write(bytes)
            if (views.isNotEmpty()) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"target\":$target}")
            return viewCount++
        }

        fun addAccessor(view: Int, componentType: Int, count: Int, type: String, extra: String = ""): Int {
            if (accessors.isNotEmpty()) accessors.append(',')
            accessors.append("{\"bufferView\":$view,\"componentType\":$componentType,\"count\":$count,\"type\":\"$type\"$extra}")
            return accessorCount++
        }

        val meshesJson = StringBuilder()
        val nodesJson = StringBuilder()
        var meshCount = 0
        for ((i, node) in nodes.withIndex()) {
            if (i > 0) nodesJson.append(',')
            nodesJson.append("{\"name\":").append(jsonString(node.name))
            if (node.prims.isNotEmpty()) {
                val primsJson = node.prims.joinToString(",") { p ->
                    val m = p.mesh
                    val (min, max) = m.bounds()
                    val pos = addAccessor(
                        addView(floatBytes(m.positions()), TARGET_ARRAY), FLOAT, m.vertexCount, "VEC3",
                        ",\"min\":${vec(min)},\"max\":${vec(max)}",
                    )
                    val nor = addAccessor(addView(floatBytes(m.normals()), TARGET_ARRAY), FLOAT, m.vertexCount, "VEC3")
                    val idx = addAccessor(addView(intBytes(m.indices()), TARGET_ELEMENTS), UNSIGNED_INT, m.indexCount, "SCALAR")
                    "{\"attributes\":{\"POSITION\":$pos,\"NORMAL\":$nor},\"indices\":$idx,\"material\":${p.material}}"
                }
                if (meshCount > 0) meshesJson.append(',')
                meshesJson.append("{\"primitives\":[").append(primsJson).append("]}")
                nodesJson.append(",\"mesh\":").append(meshCount++)
            }
            nodesJson.append('}')
        }

        val anyUnlit = materials.any { it.unlit }
        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"MyRobots\"}")
            if (anyUnlit) append(",\"extensionsUsed\":[\"KHR_materials_unlit\"]")
            append(",\"scene\":0,\"scenes\":[{\"nodes\":[")
            append(nodes.indices.joinToString(","))
            append("]}]")
            append(",\"nodes\":[").append(nodesJson).append(']')
            if (meshCount > 0) append(",\"meshes\":[").append(meshesJson).append(']')
            if (materials.isNotEmpty()) append(",\"materials\":[").append(materials.joinToString(",") { materialJson(it) }).append(']')
            if (accessorCount > 0) {
                append(",\"accessors\":[").append(accessors).append(']')
                append(",\"bufferViews\":[").append(views).append(']')
                append(",\"buffers\":[{\"byteLength\":${bin.size()}}]")
            }
            append('}')
        }
        return assemble(json.toByteArray(Charsets.UTF_8), bin.toByteArray())
    }

    private fun materialJson(m: Material): String {
        val pbr = "\"pbrMetallicRoughness\":{\"baseColorFactor\":[${f(m.r)},${f(m.g)},${f(m.b)},1.0]," +
            "\"metallicFactor\":${f(m.metallic)},\"roughnessFactor\":${f(m.roughness)}}"
        val ext = if (m.unlit) ",\"extensions\":{\"KHR_materials_unlit\":{}}" else ""
        return "{\"name\":${jsonString(m.name)},$pbr$ext}"
    }

    companion object {
        const val MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val FLOAT = 5126
        private const val UNSIGNED_INT = 5125
        private const val TARGET_ARRAY = 34962
        private const val TARGET_ELEMENTS = 34963

        /** Cabeçalho + bloco JSON (completado com espaços) + bloco BIN (completado com zeros). */
        internal fun assemble(json: ByteArray, bin: ByteArray): ByteArray {
            val jsonPad = pad4(json.size)
            val binPad = pad4(bin.size)
            val hasBin = bin.isNotEmpty()
            val total = 12 + 8 + jsonPad + (if (hasBin) 8 + binPad else 0)
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MAGIC).putInt(2).putInt(total)
            out.putInt(jsonPad).putInt(CHUNK_JSON).put(json)
            repeat(jsonPad - json.size) { out.put(' '.code.toByte()) }
            if (hasBin) {
                out.putInt(binPad).putInt(CHUNK_BIN).put(bin)
                repeat(binPad - bin.size) { out.put(0) }
            }
            return out.array()
        }

        /** O arquivo começa com "glTF" (é um .glb)? */
        fun isGlb(bytes: ByteArray): Boolean =
            bytes.size >= 12 && ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int == MAGIC

        private fun pad4(n: Int) = (n + 3) and 3.inv()

        private fun floatBytes(a: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            a.forEach { b.putFloat(it) }
            return b.array()
        }

        private fun intBytes(a: IntArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            a.forEach { b.putInt(it) }
            return b.array()
        }

        private fun f(v: Float) = String.format(Locale.US, "%.6f", v)
        private fun vec(v: FloatArray) = "[${f(v[0])},${f(v[1])},${f(v[2])}]"

        private fun jsonString(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
