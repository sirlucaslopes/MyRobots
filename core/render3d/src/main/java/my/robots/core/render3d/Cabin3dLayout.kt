package my.robots.core.render3d

import my.robots.core.kinematics.RobotModel
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * A cabine 2D (linhas × colunas, faixas de equipamento entre as linhas) levada para o 3D, em mm
 * com Z para cima: as colunas correm em X (o sentido do transportador) e as linhas em Y, de trás
 * para a frente da tela. Cada robô fica no meio da vaga, virado para a faixa mais perto.
 *
 * Kotlin puro (testado no PC); a cabine 3D da Estação só desenha o que sai daqui.
 */
class Cabin3dLayout(
    val rows: Int,
    val cols: Int,
    /** Robô (id) → (linha, coluna). */
    val placed: Map<Int, Pair<Int, Int>>,
    /** Faixas de equipamento: (posição 0..rows, sentido do fluxo −1/0/+1). */
    val bands: List<Pair<Int, Int>>,
) {
    /** Centro da vaga no chão (mm). */
    fun cellCenter(row: Int, col: Int): Vec3 = Vec3(
        (col - (cols - 1) / 2.0) * COL_PITCH_MM,
        ((rows - 1) / 2.0 - row) * ROW_PITCH_MM,
        0.0,
    )

    /** Y do meio de uma faixa (0 = acima da linha 1, rows = abaixo da última). */
    fun bandY(position: Int): Double = ((rows - 1) / 2.0 - (position - 0.5)) * ROW_PITCH_MM

    /** Comprimento das faixas: a cabine inteira em X, com folga. */
    val bandLengthMm: Double get() = cols * COL_PITCH_MM

    /**
     * Para onde o robô da linha [row] olha: a faixa mais perto (+Y ou −Y). Sem faixa, a linha de
     * cima olha para a frente da tela (−Y) e as outras para trás, como robôs frente a frente.
     */
    fun facing(row: Int): Vec3 {
        val y = cellCenter(row, 0).y
        val nearest = bands.minByOrNull { abs(bandY(it.first) - y) }
        return when {
            nearest != null -> if (bandY(nearest.first) > y) Vec3.Y else -Vec3.Y
            rows > 1 && row == rows - 1 -> Vec3.Y
            else -> -Vec3.Y
        }
    }

    /**
     * Onde pôr o robô [id] no espaço: o ponto [anchor] do arquivo (o eixo 1 no piso da base) vai
     * para o meio da vaga, e o robô gira em Z para o braço ([armDir], no arquivo) olhar para a
     * faixa. null se o robô não está na grade.
     */
    fun placement(id: Int, anchor: Vec3, armDir: Vec3): Transform? {
        val (r, c) = placed[id] ?: return null
        val center = cellCenter(r, c)
        val f = facing(r)
        val angle = atan2(f.y, f.x) - atan2(armDir.y, armDir.x)
        return Transform.translation(center) * Transform.rotZ(angle) * Transform.translation(-anchor)
    }

    /**
     * Robô tocado: o primeiro cilindro de robô (raio [PICK_RADIUS_MM], altura [PICK_HEIGHT_MM]
     * em volta do meio da vaga) que o raio ([origin] + t·[dir], mm) atravessa. null se nenhum.
     */
    fun pick(origin: Vec3, dir: Vec3): Int? {
        var best: Int? = null
        var bestT = Double.MAX_VALUE
        for ((id, rc) in placed) {
            val c = cellCenter(rc.first, rc.second)
            // círculo em XY: |o + t d − c|² = R²
            val ox = origin.x - c.x; val oy = origin.y - c.y
            val a = dir.x * dir.x + dir.y * dir.y
            if (a < 1e-12) {
                // olhando reto de cima: vale se está dentro do círculo
                if (ox * ox + oy * oy <= PICK_RADIUS_MM * PICK_RADIUS_MM && dir.z != 0.0) {
                    val t = (PICK_HEIGHT_MM - origin.z) / dir.z
                    if (t > 0 && t < bestT) { bestT = t; best = id }
                }
                continue
            }
            val b = 2 * (ox * dir.x + oy * dir.y)
            val cc = ox * ox + oy * oy - PICK_RADIUS_MM * PICK_RADIUS_MM
            val disc = b * b - 4 * a * cc
            if (disc < 0) continue
            val sq = kotlin.math.sqrt(disc)
            for (t in listOf((-b - sq) / (2 * a), (-b + sq) / (2 * a))) {
                if (t <= 0) continue
                val z = origin.z + dir.z * t
                if (z in 0.0..PICK_HEIGHT_MM && t < bestT) { bestT = t; best = id }
                break
            }
            // entrando pela tampa de cima (vista quase de cima)
            if (dir.z < 0) {
                val t = (PICK_HEIGHT_MM - origin.z) / dir.z
                val px = ox + dir.x * t; val py = oy + dir.y * t
                if (t > 0 && px * px + py * py <= PICK_RADIUS_MM * PICK_RADIUS_MM && t < bestT) { bestT = t; best = id }
            }
        }
        return best
    }

    /** Centro e raio da cabine inteira (mm), para a câmera enquadrar. */
    fun bounds(): Pair<Vec3, Double> {
        val w = cols * COL_PITCH_MM
        val h = rows * ROW_PITCH_MM
        return Vec3(0.0, 0.0, 800.0) to kotlin.math.sqrt(w * w + h * h) / 2 + 500
    }

    companion object {
        /** Distância entre colunas (ao longo do transportador) e entre linhas (mm). */
        const val COL_PITCH_MM = 3500.0
        const val ROW_PITCH_MM = 4500.0
        const val PICK_RADIUS_MM = 1100.0
        const val PICK_HEIGHT_MM = 2800.0

        /**
         * Para onde o braço aponta com todos os eixos em 0 (no plano do chão): do eixo 1 até o
         * flange. Robô sem braço para o lado (ou sem modelo): +X.
         */
        fun armDirection(model: RobotModel?, axis1Point: Vec3): Vec3 {
            if (model == null) return Vec3.X
            val flange = model.flangeInBase(DoubleArray(model.axisCount)).t
            val d = Vec3(flange.x - axis1Point.x, flange.y - axis1Point.y, 0.0)
            return if (d.length() < 1.0) Vec3.X else d.normalized()
        }
    }
}
