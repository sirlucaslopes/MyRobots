package my.robots.core.render3d

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

    /** Nós das letras da legenda da origem (o desenho vira cada uma para a câmera). */
    val LEGEND_NODES = listOf("letra_x", "letra_y", "letra_z")

    /** Comprimento das setas da origem (mm) e onde fica o meio de cada letra. */
    const val ORIGIN_ARROW_MM = 1000.0
    val LEGEND_POSITIONS_MM = listOf(Vec3(1130.0, 0.0, 0.0), Vec3(0.0, 1130.0, 0.0), Vec3(0.0, 0.0, 1130.0))

    /**
     * Grade no chão (Z = 0) de ±[halfSizeMm] com uma linha a cada [stepMm], mais forte a cada metro,
     * e a origem no estilo do CAD: uma bolinha branca e as setas X (vermelha), Y (verde) e Z (azul).
     */
    fun sceneryGlb(halfSizeMm: Double = 3000.0, stepMm: Double = 250.0): ByteArray {
        val glb = GlbBuilder()
        val minor = glb.addMaterial(GlbBuilder.Material("grade", 0.16f, 0.17f, 0.19f, unlit = true))
        val major = glb.addMaterial(GlbBuilder.Material("grade_metro", 0.32f, 0.34f, 0.38f, unlit = true))
        val triad = triadMaterials(glb)

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

        glb.addNode(SCENERY_NODE, listOf(thin to minor, thick to major) + triadMeshes(ORIGIN_ARROW_MM, triad))
        return glb.build()
    }

    /** Materiais das setas X/Y/Z: vermelho, verde, azul e a bolinha branca, com luz (como no CAD). */
    private fun triadMaterials(glb: GlbBuilder) = listOf(
        glb.addMaterial(GlbBuilder.Material("eixo_x", 0.85f, 0.05f, 0.04f, roughness = 0.45f)),
        glb.addMaterial(GlbBuilder.Material("eixo_y", 0.04f, 0.45f, 0.06f, roughness = 0.45f)),
        glb.addMaterial(GlbBuilder.Material("eixo_z", 0.04f, 0.10f, 0.80f, roughness = 0.45f)),
        glb.addMaterial(GlbBuilder.Material("origem", 0.92f, 0.92f, 0.92f, roughness = 0.35f)),
    )

    /**
     * Setas X, Y e Z de comprimento [lengthMm] saindo do zero, e a bolinha no meio: o desenho de
     * um sistema de coordenadas (origem do espaço, sistema do robô, TCP). Proporções fixas, para
     * todos parecerem iguais em tamanhos diferentes.
     */
    private fun triadMeshes(lengthMm: Double, mats: List<Int>, thickness: Double = 1.0): List<Pair<MeshData, Int>> {
        fun arrow(d: Vec3) = MeshData().arrow(Vec3.ZERO, d, lengthMm, shaft = lengthMm * 0.010 * thickness,
            headRadius = lengthMm * 0.030 * thickness, headLength = lengthMm * 0.10 * thickness)
        return listOf(
            arrow(Vec3.X) to mats[0], arrow(Vec3.Y) to mats[1], arrow(Vec3.Z) to mats[2],
            MeshData().sphere(Vec3.ZERO, lengthMm * 0.030 * thickness) to mats[3],
        )
    }

    /** Nós dos sistemas desenhados pelo [FilamentViewer.setFrames]. */
    const val FRAME_ROBOT = "sistema_robo"
    const val FRAME_TCP = "sistema_tcp"
    /** Base deslocada pelo BASE do controlador (frame fora do zero do robô). */
    const val FRAME_BASE = "sistema_base"

    /** Setas do sistema do robô (mm). */
    const val ROBOT_FRAME_MM = 400.0
    /** Setas da base deslocada pelo BASE (mm). */
    const val BASE_FRAME_MM = 300.0
    /** Setas do TCP (mm): menores, sem legenda. */
    const val TCP_FRAME_MM = 150.0

    /**
     * Os sistemas que se movem: o do robô (base, no zero ou fora dele) e o do TCP. Cada um é um nó
     * com as setas em volta do zero; o desenho põe o nó na pose de cada sistema.
     */
    fun framesGlb(): ByteArray {
        val glb = GlbBuilder()
        val mats = triadMaterials(glb)
        glb.addNode(FRAME_ROBOT, triadMeshes(ROBOT_FRAME_MM, mats))
        glb.addNode(FRAME_BASE, triadMeshes(BASE_FRAME_MM, mats, thickness = 1.5))
        // o TCP é pequeno: setas mais grossas para aparecerem de longe
        glb.addNode(FRAME_TCP, triadMeshes(TCP_FRAME_MM, mats, thickness = 2.5))
        return glb.build()
    }

    /**
     * As letras X, Y e Z da origem: um nó por letra, de traços, com 1000 mm de altura e o meio no
     * zero do nó, desenhadas no plano XY (de frente para +Z). O desenho põe cada uma na ponta da
     * seta, virada para a câmera e no tamanho certo ([LEGEND_NODES]).
     */
    fun legendGlb(): ByteArray {
        val glb = GlbBuilder()
        val mat = glb.addMaterial(GlbBuilder.Material("legenda", 0.95f, 0.95f, 0.95f, unlit = true))
        // traços de cada letra num quadrado de 0 a 1 (largura 0,7)
        val strokes = listOf(
            listOf(0.0 to 0.0, 0.7 to 1.0, 0.0 to 1.0, 0.7 to 0.0),                 // X: duas diagonais
            listOf(0.0 to 1.0, 0.35 to 0.5, 0.7 to 1.0, 0.35 to 0.5, 0.35 to 0.5, 0.35 to 0.0), // Y
            listOf(0.0 to 1.0, 0.7 to 1.0, 0.7 to 1.0, 0.0 to 0.0, 0.0 to 0.0, 0.7 to 0.0),     // Z
        )
        for ((i, name) in LEGEND_NODES.withIndex()) {
            val m = MeshData()
            val pts = strokes[i]
            for (k in pts.indices step 2) {
                val (ax, ay) = pts[k]
                val (bx, by) = pts[k + 1]
                val a = Vec3((ax - 0.35) * 1000, (ay - 0.5) * 1000, 0.0)
                val b = Vec3((bx - 0.35) * 1000, (by - 0.5) * 1000, 0.0)
                m.stroke(a, b, 110.0)
            }
            glb.addNode(name, listOf(m to mat))
        }
        return glb.build()
    }

    /** Um eixo para marcar: ponto e direção (mm, espaço do app), raio da face e se é o editado. */
    data class AxisMarker(val point: Vec3, val direction: Vec3, val radiusMm: Double, val editing: Boolean)

    /**
     * Marcas do montador, no espaço do app (mm), no estilo do Fusion 360: para cada eixo, uma seta
     * reta no sentido do eixo e uma seta curva em volta dele no sentido do giro positivo (regra da
     * mão direita: polegar na seta reta, os dedos na curva). Mais os pontos tocados.
     * Sem luz, para aparecer igual de qualquer lado. null se não há nada para marcar.
     */
    fun markersGlb(axes: List<AxisMarker>, points: List<Vec3>, halfLengthMm: Double = 350.0): ByteArray? {
        if (axes.isEmpty() && points.isEmpty()) return null
        val glb = GlbBuilder()
        val current = glb.addMaterial(GlbBuilder.Material("eixo_editado", 1f, 0.80f, 0.05f, unlit = true))
        val other = glb.addMaterial(GlbBuilder.Material("eixo", 0.15f, 0.70f, 1f, unlit = true))
        val point = glb.addMaterial(GlbBuilder.Material("ponto", 1f, 0.15f, 0.55f, unlit = true))
        val meshes = ArrayList<Pair<MeshData, Int>>()
        for (a in axes) {
            val d = a.direction.normalized()
            val tube = if (a.editing) 4.0 else 2.5
            val m = MeshData()
            // seta reta atravessando a junta, com a ponta no sentido positivo
            m.arrow(a.point - d * halfLengthMm, d, 2 * halfLengthMm, tube, tube * 4, 60.0)
            // seta curva: 270° em volta do eixo, um pouco fora da face, com a ponta no fim do giro
            val ring = (a.radiusMm * 1.3).coerceIn(60.0, 260.0)
            val frame = Transform.fromAxis(a.point, d)
            val sweep = 1.5 * Math.PI
            m.arc(frame, ring, tube, 0.0, sweep)
            val end = frame.apply(Vec3(kotlin.math.cos(sweep), kotlin.math.sin(sweep), 0.0) * ring)
            val tangent = frame.rotate(Vec3(-kotlin.math.sin(sweep), kotlin.math.cos(sweep), 0.0))
            m.cone(Transform.fromAxis(end, tangent), tube * 4, 55.0, 16)
            meshes += m to (if (a.editing) current else other)
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
