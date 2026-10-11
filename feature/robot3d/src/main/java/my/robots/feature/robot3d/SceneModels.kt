package my.robots.feature.robot3d

import my.robots.core.kinematics.RobotModel
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3

/**
 * Os modelos gerados em código para o visualizador: o robô de teste (uma forma por peça) e o
 * cenário (grade no chão e eixos X/Y/Z no zero). Tudo com Z para cima, como o robô.
 */
object SceneModels {

    /** Nó do cenário com a grade e os eixos. */
    const val SCENERY_NODE = "cenario"

    /**
     * Robô de teste: um nó por peça, com o nome da peça. Cada peça é um "osso" do eixo dela até o
     * próximo eixo (ou o flange) e um cilindro no eixo, na pose do arquivo (pose zero). O 3D
     * depois só põe em cada nó a posição que o [RobotModel.partTransforms] devolve.
     */
    fun testRobotGlb(basePart: String, axes: List<RobotModel.AssembledAxis>, flange: Transform): ByteArray {
        val glb = GlbBuilder()
        val baseMat = glb.addMaterial(GlbBuilder.Material("base", 0.08f, 0.08f, 0.09f))
        val armMat = glb.addMaterial(GlbBuilder.Material("braco", 0.85f, 0.30f, 0.03f))
        val jointMat = glb.addMaterial(GlbBuilder.Material("eixo", 0.15f, 0.15f, 0.17f, metallic = 0.6f, roughness = 0.4f))
        val flangeMat = glb.addMaterial(GlbBuilder.Material("flange", 0.6f, 0.6f, 0.62f, metallic = 0.8f, roughness = 0.3f))

        val sorted = axes.sortedBy { it.number }
        val base = MeshData().box(Vec3(-300.0, -300.0, 0.0), Vec3(300.0, 300.0, 120.0))
        glb.addNode(basePart, listOf(base to baseMat))

        for ((i, axis) in sorted.withIndex()) {
            val next = if (i + 1 < sorted.size) sorted[i + 1].point else flange.t
            val width = WIDTHS_MM.getOrElse(i) { WIDTHS_MM.last() }
            val bone = MeshData().bone(axis.point, next, width)
            val joint = MeshData().cylinder(Transform.fromAxis(axis.point, axis.direction), width * 0.62, width * 0.65)
            val parts = mutableListOf(bone to armMat, joint to jointMat)
            if (i == sorted.lastIndex) {
                parts += MeshData().cylinder(flange, width * 0.7, 8.0) to flangeMat
            }
            glb.addNode(axis.part, parts)
        }
        return glb.build()
    }

    /**
     * Grade no chão (Z = 0) de ±[halfSizeMm] com uma linha a cada [stepMm], mais forte a cada metro,
     * e os eixos X (vermelho), Y (verde) e Z (azul) saindo do zero.
     */
    fun sceneryGlb(halfSizeMm: Double = 3000.0, stepMm: Double = 250.0): ByteArray {
        val glb = GlbBuilder()
        val minor = glb.addMaterial(GlbBuilder.Material("grade", 0.16f, 0.17f, 0.19f, unlit = true))
        val major = glb.addMaterial(GlbBuilder.Material("grade_metro", 0.32f, 0.34f, 0.38f, unlit = true))
        val red = glb.addMaterial(GlbBuilder.Material("eixo_x", 0.9f, 0.08f, 0.06f, unlit = true))
        val green = glb.addMaterial(GlbBuilder.Material("eixo_y", 0.10f, 0.75f, 0.12f, unlit = true))
        val blue = glb.addMaterial(GlbBuilder.Material("eixo_z", 0.10f, 0.30f, 0.95f, unlit = true))

        val thin = MeshData()
        val thick = MeshData()
        val n = (halfSizeMm / stepMm).toInt()
        for (k in -n..n) {
            val c = k * stepMm
            if (k == 0) continue // no zero ficam os eixos
            val metro = (c % 1000.0) == 0.0
            val mesh = if (metro) thick else thin
            val w = if (metro) 6.0 else 3.0
            // linhas achatadas (1 mm de altura), um pouco abaixo do zero para não brigar com os eixos
            mesh.box(Vec3(c - w / 2, -halfSizeMm, -1.5), Vec3(c + w / 2, halfSizeMm, -0.5))
            mesh.box(Vec3(-halfSizeMm, c - w / 2, -1.5), Vec3(halfSizeMm, c + w / 2, -0.5))
        }
        // as linhas do zero, do lado negativo, ficam na cor da grade forte
        thick.box(Vec3(-halfSizeMm, -3.0, -1.5), Vec3(0.0, 3.0, -0.5))
        thick.box(Vec3(-3.0, -halfSizeMm, -1.5), Vec3(3.0, 0.0, -0.5))

        val axisLen = 1000.0
        val t = 8.0
        val x = MeshData().box(Vec3(0.0, -t, -t), Vec3(axisLen, t, t))
        val y = MeshData().box(Vec3(-t, 0.0, -t), Vec3(t, axisLen, t))
        val z = MeshData().box(Vec3(-t, -t, 0.0), Vec3(t, t, axisLen))
        glb.addNode(SCENERY_NODE, listOf(thin to minor, thick to major, x to red, y to green, z to blue))
        return glb.build()
    }

    /**
     * Marcas do montador, no espaço do app (mm): a linha de cada eixo ([axes]: ponto, direção e
     * se é o que está sendo editado), com um cone na ponta positiva, e os pontos tocados.
     * Sem luz, para aparecer igual de qualquer lado. null se não há nada para marcar.
     */
    fun markersGlb(axes: List<Triple<Vec3, Vec3, Boolean>>, points: List<Vec3>, halfLengthMm: Double = 350.0): ByteArray? {
        if (axes.isEmpty() && points.isEmpty()) return null
        val glb = GlbBuilder()
        val current = glb.addMaterial(GlbBuilder.Material("eixo_editado", 1f, 0.85f, 0.05f, unlit = true))
        val other = glb.addMaterial(GlbBuilder.Material("eixo", 0.15f, 0.75f, 1f, unlit = true))
        val point = glb.addMaterial(GlbBuilder.Material("ponto", 1f, 0.15f, 0.55f, unlit = true))
        val meshes = ArrayList<Pair<MeshData, Int>>()
        for ((p, d, editing) in axes) {
            val frame = Transform.fromAxis(p, d)
            val r = if (editing) 4.0 else 2.5
            val line = MeshData().cylinder(frame, r, halfLengthMm, 12)
            // "seta": um cilindro mais grosso na ponta do sentido positivo
            line.cylinder(Transform.fromAxis(p + d.normalized() * (halfLengthMm - 20), d), r * 3.5, 20.0, 12)
            meshes += line to (if (editing) current else other)
        }
        if (points.isNotEmpty()) {
            val m = MeshData()
            for (p in points) m.box(Transform.translation(p), 7.0, 7.0, 7.0)
            meshes += m to point
        }
        glb.addNode("marcas", meshes)
        return glb.build()
    }

    /** Largura de cada peça (mm), do eixo 1 ao 6: o robô afina para o punho. */
    private val WIDTHS_MM = listOf(300.0, 220.0, 180.0, 140.0, 110.0, 90.0)

    /**
     * [Transform] (mm, rotação linha a linha) → matriz 4x4 do Filament (metros, coluna a coluna).
     * [out] é reaproveitado para não criar lixo a cada quadro.
     */
    fun toFilamentMatrix(t: Transform, out: FloatArray = FloatArray(16)): FloatArray {
        val r = t.r
        out[0] = r[0].toFloat(); out[1] = r[3].toFloat(); out[2] = r[6].toFloat(); out[3] = 0f
        out[4] = r[1].toFloat(); out[5] = r[4].toFloat(); out[6] = r[7].toFloat(); out[7] = 0f
        out[8] = r[2].toFloat(); out[9] = r[5].toFloat(); out[10] = r[8].toFloat(); out[11] = 0f
        out[12] = (t.t.x * MeshData.MM).toFloat()
        out[13] = (t.t.y * MeshData.MM).toFloat()
        out[14] = (t.t.z * MeshData.MM).toFloat()
        out[15] = 1f
        return out
    }

    /**
     * O glTF tem Y para cima; o app usa Z para cima. Girar +90° em X leva o Y do arquivo para o Z
     * (e o Z do arquivo para −Y). Vai na raiz do .glb aberto pelo usuário.
     */
    val GLTF_TO_Z_UP: Transform = Transform.rotation(Vec3.X, Math.PI / 2)

    /** Ponto do arquivo glTF (Y para cima) no espaço do app (Z para cima). */
    fun gltfToZUp(p: Vec3): Vec3 = GLTF_TO_Z_UP.apply(p)
}
