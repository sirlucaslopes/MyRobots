package my.robots.feature.robot3d

import my.robots.core.kinematics.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Malha de uma peça do robô, já no espaço do app: **mm, Z para cima**, na pose em que veio no
 * arquivo. É o que o montador usa para tocar na peça e achar os eixos (o Filament só desenha).
 *
 * [positions] e [normals]: x, y, z por vértice. [indices]: três por triângulo.
 */
class PartMesh(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3

    fun vertex(i: Int) = Vec3(positions[i * 3].toDouble(), positions[i * 3 + 1].toDouble(), positions[i * 3 + 2].toDouble())
    fun normal(i: Int) = Vec3(normals[i * 3].toDouble(), normals[i * 3 + 1].toDouble(), normals[i * 3 + 2].toDouble())

    fun triangleNormal(t: Int): Vec3 {
        val a = vertex(indices[t * 3]); val b = vertex(indices[t * 3 + 1]); val c = vertex(indices[t * 3 + 2])
        val n = (b - a).cross(c - a)
        val len = n.length()
        return if (len < 1e-12) Vec3.ZERO else n * (1.0 / len)
    }

    fun triangleArea(t: Int): Double {
        val a = vertex(indices[t * 3]); val b = vertex(indices[t * 3 + 1]); val c = vertex(indices[t * 3 + 2])
        return (b - a).cross(c - a).length() / 2
    }

    /** Centro e raio que envolvem a peça. */
    fun bounds(): Pair<Vec3, Double> {
        if (vertexCount == 0) return Vec3.ZERO to 0.0
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3].toDouble(); val y = positions[i * 3 + 1].toDouble(); val z = positions[i * 3 + 2].toDouble()
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        val c = Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
        return c to (Vec3(maxX, maxY, maxZ) - c).length()
    }

    /**
     * Primeiro triângulo que o raio ([origin] + t·[dir], na pose do arquivo) atravessa.
     * Devolve (triângulo, t) ou null.
     */
    fun raycast(origin: Vec3, dir: Vec3): Pair<Int, Double>? {
        var best = -1
        var bestT = Double.MAX_VALUE
        val ox = origin.x; val oy = origin.y; val oz = origin.z
        val dx = dir.x; val dy = dir.y; val dz = dir.z
        val p = positions
        for (t in 0 until triangleCount) {
            val i0 = indices[t * 3] * 3; val i1 = indices[t * 3 + 1] * 3; val i2 = indices[t * 3 + 2] * 3
            // Möller–Trumbore
            val e1x = p[i1] - p[i0].toDouble(); val e1y = p[i1 + 1] - p[i0 + 1].toDouble(); val e1z = p[i1 + 2] - p[i0 + 2].toDouble()
            val e2x = p[i2] - p[i0].toDouble(); val e2y = p[i2 + 1] - p[i0 + 1].toDouble(); val e2z = p[i2 + 2] - p[i0 + 2].toDouble()
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12) continue
            val inv = 1.0 / det
            val sx = ox - p[i0]; val sy = oy - p[i0 + 1]; val sz = oz - p[i0 + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0 || u > 1) continue
            val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
            val v = (dx * qx + dy * qy + dz * qz) * inv
            if (v < 0 || u + v > 1) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > 1e-6 && dist < bestT) {
                bestT = dist
                best = t
            }
        }
        return if (best < 0) null else best to bestT
    }

    /** Triângulos de cada vértice (CSR) pelo índice; [key] junta vértices (ex.: mesma posição). */
    private fun vertexTriangles(key: IntArray?): Triple<IntArray, IntArray, IntArray> {
        val ids = key ?: IntArray(vertexCount) { it }
        val count = IntArray(vertexCount + 1)
        for (i in indices) count[ids[i] + 1]++
        for (i in 1..vertexCount) count[i] += count[i - 1]
        val fill = count.copyOf()
        val tris = IntArray(indices.size)
        for (t in 0 until triangleCount) for (k in 0 until 3) {
            val v = ids[indices[t * 3 + k]]
            tris[fill[v]++] = t
        }
        return Triple(count, tris, ids)
    }

    private val byIndex by lazy { vertexTriangles(null) }

    /** Vértices na mesma posição (0,01 mm) contam como um só. */
    private val byPosition by lazy {
        val map = HashMap<Triple<Long, Long, Long>, Int>()
        val ids = IntArray(vertexCount) { v ->
            val k = Triple(
                Math.round(positions[v * 3] * 100.0), Math.round(positions[v * 3 + 1] * 100.0), Math.round(positions[v * 3 + 2] * 100.0),
            )
            map.getOrPut(k) { v }
        }
        vertexTriangles(ids)
    }

    /** Um número por posição: vértices no mesmo lugar (0,01 mm) dão o mesmo número. */
    fun positionId(v: Int): Int = byPosition.third[v]

    /**
     * A face do CAD em volta do triângulo [start]: os triângulos ligados a ele por vértices em
     * comum, sem dobra maior que [maxBendDeg] entre vizinhos. Quem converte do STEP (OpenCASCADE)
     * já separa os vértices de cada face, e a face sai exata. Se o arquivo separou os vértices
     * de cada triângulo (a face sai com 1 ou 2), junta pela posição e a dobra é quem corta.
     */
    fun faceAround(start: Int, maxBendDeg: Double = 35.0): IntArray {
        val exact = grow(start, maxBendDeg, byIndex)
        return if (exact.size > 2) exact else grow(start, maxBendDeg, byPosition)
    }

    private fun grow(start: Int, maxBendDeg: Double, adjacency: Triple<IntArray, IntArray, IntArray>): IntArray {
        val (offsets, tris, ids) = adjacency
        val cosLimit = kotlin.math.cos(Math.toRadians(maxBendDeg))
        val seen = BooleanArray(triangleCount)
        val queue = ArrayDeque<Int>()
        val out = ArrayList<Int>()
        seen[start] = true
        queue += start
        while (queue.isNotEmpty() && out.size < MAX_FACE_TRIANGLES) {
            val t = queue.removeFirst()
            out += t
            val nt = triangleNormal(t)
            for (k in 0 until 3) {
                val v = ids[indices[t * 3 + k]]
                for (j in offsets[v] until offsets[v + 1]) {
                    val o = tris[j]
                    if (seen[o]) continue
                    val no = triangleNormal(o)
                    // triângulo degenerado não corta a face
                    if (no != Vec3.ZERO && nt != Vec3.ZERO && nt.dot(no) < cosLimit) continue
                    seen[o] = true
                    queue += o
                }
            }
        }
        return out.toIntArray()
    }

    companion object {
        private const val MAX_FACE_TRIANGLES = 200_000
    }
}

/** As peças lidas de um .glb e os avisos para mostrar a quem abriu. */
class GlbParts(val parts: List<PartMesh>, val warnings: List<String>) {
    fun part(name: String) = parts.firstOrNull { it.name == name }
}

/**
 * Lê as peças de um .glb para o montador.
 *
 * Quais nós são peças: desce da cena enquanto há um nó só (a raiz e os grupos de montagem) e
 * pega os filhos do primeiro nó com mais de um filho. Cada peça leva junto as malhas dos filhos
 * dela. Assim um STEP convertido (robô → J0…J6) dá uma peça por eixo.
 *
 * Tudo vai para o espaço do app com [SceneModels.GLTF_TO_Z_UP] e metros → mm, o mesmo que o
 * Filament faz ao desenhar.
 */
object GlbReader {

    fun read(bytes: ByteArray): GlbParts {
        require(GlbBuilder.isGlb(bytes)) { "O arquivo não é um .glb (glTF binário)." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var json: Map<String, Any?> = emptyMap()
        var bin: ByteBuffer? = null
        while (offset + 8 <= bytes.size) {
            val len = b.getInt(offset)
            val type = b.getInt(offset + 4)
            val start = offset + 8
            require(len >= 0 && start + len <= bytes.size) { "Bloco do .glb cortado." }
            when (type) {
                0x4E4F534A -> json = MiniJson.parse(String(bytes, start, len, Charsets.UTF_8)).obj()
                0x004E4942 -> bin = ByteBuffer.wrap(bytes, start, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            offset = start + ((len + 3) and 3.inv())
        }
        return Reader(json, bin).read()
    }

    private class Reader(val json: Map<String, Any?>, val bin: ByteBuffer?) {
        val nodes = json["nodes"].arr().map { it.obj() }
        val meshes = json["meshes"].arr().map { it.obj() }
        val accessors = json["accessors"].arr().map { it.obj() }
        val views = json["bufferViews"].arr().map { it.obj() }
        val warnings = LinkedHashSet<String>()

        /** glTF (m, Y para cima) → app (mm, Z para cima). */
        val toApp = Mat4.of(SceneModels.GLTF_TO_Z_UP) * Mat4.scale(1000.0)

        fun read(): GlbParts {
            val used = json["extensionsRequired"].arr().mapNotNull { it.str() }
            if ("KHR_draco_mesh_compression" in used || "EXT_meshopt_compression" in used) {
                warnings += "Malha comprimida (${used.joinToString()}): o montador não consegue tocar nas peças."
            }
            val scene = json["scenes"].arr().getOrNull(json["scene"].int() ?: 0).obj()
            var roots = scene["nodes"].arr().mapNotNull { it.int() }
            if (roots.isEmpty()) roots = nodes.indices.toList() // sem cena: tudo
            var parent = Mat4()
            // desce pelos nós únicos (raiz, grupos) acumulando a posição deles
            var level = roots
            while (level.size == 1) {
                val n = nodes.getOrNull(level[0]) ?: break
                val children = n["children"].arr().mapNotNull { it.int() }
                if (n["mesh"] != null || children.isEmpty()) break
                parent = parent * localMatrix(n)
                level = children
            }
            val parts = ArrayList<PartMesh>()
            val names = HashSet<String>()
            for ((i, idx) in level.withIndex()) {
                val n = nodes.getOrNull(idx) ?: continue
                var name = n["name"].str()?.takeIf { it.isNotBlank() }
                if (name == null || name in names) {
                    warnings += "Há peças sem nome ou com nome repetido: o montador chamou de \"peça N\" e não consegue movê-las no desenho."
                    name = "peça ${i + 1}"
                }
                names += name
                val acc = MeshAccumulator()
                collect(idx, parent * localMatrix(n), acc, depth = 0)
                if (acc.triangleCount > 0) parts += acc.build(name)
            }
            return GlbParts(parts, warnings.toList())
        }

        fun localMatrix(n: Map<String, Any?>): Mat4 {
            n["matrix"].arr().takeIf { it.size == 16 }?.let { list ->
                return Mat4(DoubleArray(16) { list[it].num() ?: 0.0 })
            }
            fun vec(key: String) = n[key].arr().takeIf { it.isNotEmpty() }?.map { it.num() ?: 0.0 }?.toDoubleArray()
            return Mat4.trs(vec("translation"), vec("rotation"), vec("scale"))
        }

        fun collect(idx: Int, world: Mat4, acc: MeshAccumulator, depth: Int) {
            if (depth > 64) return
            val n = nodes.getOrNull(idx) ?: return
            n["mesh"].int()?.let { meshIdx -> addMesh(meshes.getOrNull(meshIdx) ?: return@let, toApp * world, acc) }
            for (c in n["children"].arr().mapNotNull { it.int() }) {
                collect(c, world * localMatrix(nodes.getOrNull(c) ?: continue), acc, depth + 1)
            }
        }

        fun addMesh(mesh: Map<String, Any?>, m: Mat4, acc: MeshAccumulator) {
            // normais: inversa transposta; giro + escala uniforme basta usar a própria matriz
            for (prim in mesh["primitives"].arr().map { it.obj() }) {
                val mode = prim["mode"].int() ?: 4
                if (mode != 4) { warnings += "Há malhas que não são triângulos: ficaram de fora do montador."; continue }
                val attrs = prim["attributes"].obj()
                val pos = readFloats(attrs["POSITION"].int() ?: continue, 3) ?: continue
                val nor = attrs["NORMAL"].int()?.let { readFloats(it, 3) }
                val count = pos.size / 3
                val idx = prim["indices"].int()?.let { readIndices(it) } ?: IntArray(count) { it }
                val base = acc.vertexCount
                for (i in 0 until count) {
                    val p = m.applyPoint(pos[i * 3].toDouble(), pos[i * 3 + 1].toDouble(), pos[i * 3 + 2].toDouble())
                    val nv = if (nor != null) m.applyVector(nor[i * 3].toDouble(), nor[i * 3 + 1].toDouble(), nor[i * 3 + 2].toDouble()) else Vec3.ZERO
                    acc.addVertex(p, nv)
                }
                for (i in 0 until idx.size / 3 * 3) {
                    val v = idx[i]
                    if (v < 0 || v >= count) { warnings += "Índice de vértice fora da malha: parte da peça ficou de fora."; return }
                    acc.addIndex(base + v)
                }
                if (nor == null) acc.needsNormals = true
            }
        }

        /** Lê um accessor de floats ([size] por elemento). */
        fun readFloats(accIdx: Int, size: Int): FloatArray? {
            val a = accessors.getOrNull(accIdx) ?: return null
            if (a["sparse"] != null) { warnings += "Accessor esparso não suportado no montador."; return null }
            if (a["componentType"].int() != 5126) { warnings += "Malha quantizada (sem float): ficou de fora do montador."; return null }
            val count = a["count"].int() ?: return null
            val (buf, start, stride) = view(a, size * 4) ?: return null
            val out = FloatArray(count * size)
            for (i in 0 until count) for (k in 0 until size) {
                out[i * size + k] = buf.getFloat(start + i * stride + k * 4)
            }
            return out
        }

        fun readIndices(accIdx: Int): IntArray? {
            val a = accessors.getOrNull(accIdx) ?: return null
            val count = a["count"].int() ?: return null
            val type = a["componentType"].int()
            val bytes = when (type) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> return null }
            val (buf, start, stride) = view(a, bytes) ?: return null
            return IntArray(count) { i ->
                val at = start + i * stride
                when (bytes) {
                    1 -> buf.get(at).toInt() and 0xFF
                    2 -> buf.getShort(at).toInt() and 0xFFFF
                    else -> buf.getInt(at)
                }
            }
        }

        /** (dados, início, passo) de um accessor, conferindo os limites. */
        fun view(a: Map<String, Any?>, elementBytes: Int): Triple<ByteBuffer, Int, Int>? {
            val bv = views.getOrNull(a["bufferView"].int() ?: return null) ?: return null
            if ((bv["buffer"].int() ?: 0) != 0) { warnings += "O .glb usa arquivos externos: parte ficou de fora."; return null }
            val data = bin ?: return null
            val start = (bv["byteOffset"].int() ?: 0) + (a["byteOffset"].int() ?: 0)
            val stride = bv["byteStride"].int()?.takeIf { it > 0 } ?: elementBytes
            val count = a["count"].int() ?: 0
            if (count > 0 && start + (count - 1) * stride + elementBytes > data.limit()) {
                warnings += "Dados da malha cortados: parte ficou de fora."
                return null
            }
            return Triple(data, start, stride)
        }
    }

    /** Junta as malhas de uma peça num só bloco. */
    private class MeshAccumulator {
        private var pos = FloatArray(3 * 1024)
        private var nor = FloatArray(3 * 1024)
        private var idx = IntArray(3 * 1024)
        var vertexCount = 0; private set
        private var indexCount = 0
        var needsNormals = false
        val triangleCount get() = indexCount / 3

        fun addVertex(p: Vec3, n: Vec3) {
            if (vertexCount * 3 + 3 > pos.size) { pos = pos.copyOf(pos.size * 2); nor = nor.copyOf(nor.size * 2) }
            val len = n.length()
            val nn = if (len > 1e-12) n * (1.0 / len) else n
            pos[vertexCount * 3] = p.x.toFloat(); pos[vertexCount * 3 + 1] = p.y.toFloat(); pos[vertexCount * 3 + 2] = p.z.toFloat()
            nor[vertexCount * 3] = nn.x.toFloat(); nor[vertexCount * 3 + 1] = nn.y.toFloat(); nor[vertexCount * 3 + 2] = nn.z.toFloat()
            vertexCount++
        }

        fun addIndex(i: Int) {
            if (indexCount + 1 > idx.size) idx = idx.copyOf(idx.size * 2)
            idx[indexCount++] = i
        }

        fun build(name: String): PartMesh {
            val mesh = PartMesh(name, pos.copyOf(vertexCount * 3), nor.copyOf(vertexCount * 3), idx.copyOf(indexCount))
            if (needsNormals) fillNormals(mesh)
            return mesh
        }

        /** Normais que faltam: média das faces de cada vértice. */
        private fun fillNormals(mesh: PartMesh) {
            val acc = DoubleArray(mesh.normals.size)
            for (t in 0 until mesh.triangleCount) {
                val n = mesh.triangleNormal(t) * mesh.triangleArea(t)
                for (k in 0 until 3) {
                    val v = mesh.indices[t * 3 + k]
                    if (mesh.normals[v * 3] != 0f || mesh.normals[v * 3 + 1] != 0f || mesh.normals[v * 3 + 2] != 0f) continue
                    acc[v * 3] += n.x; acc[v * 3 + 1] += n.y; acc[v * 3 + 2] += n.z
                }
            }
            for (v in 0 until mesh.vertexCount) {
                val x = acc[v * 3]; val y = acc[v * 3 + 1]; val z = acc[v * 3 + 2]
                val len = sqrt(x * x + y * y + z * z)
                if (len > 1e-12) {
                    mesh.normals[v * 3] = (x / len).toFloat(); mesh.normals[v * 3 + 1] = (y / len).toFloat(); mesh.normals[v * 3 + 2] = (z / len).toFloat()
                }
            }
        }
    }
}
