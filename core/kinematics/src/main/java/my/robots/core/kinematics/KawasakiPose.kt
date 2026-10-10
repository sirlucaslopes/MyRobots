package my.robots.core.kinematics

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pose no formato da Kawasaki (o que o `WHERE` mostra e o que fica em `.TRANS`):
 * X, Y, Z em mm e O, A, T em graus.
 *
 * O, A, T são ângulos de Euler Z-Y-Z: gira O em volta de Z, depois A em volta do novo Y,
 * depois T em volta do novo Z. Rotação = Rz(O) · Ry(A) · Rz(T).
 */
data class KawasakiPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val o: Double,
    val a: Double,
    val t: Double,
) {

    fun toTransform(): Transform =
        Transform.translation(Vec3(x, y, z)) *
            Transform.rotZ(Math.toRadians(o)) *
            Transform.rotY(Math.toRadians(a)) *
            Transform.rotZ(Math.toRadians(t))

    /** Texto como no `.TRANS`: "X Y Z O A T" com 3 casas. */
    fun format(): String = listOf(x, y, z, o, a, t).joinToString(" ") { String.format(java.util.Locale.US, "%.3f", it) }

    companion object {

        /**
         * Transformação → X, Y, Z, O, A, T. Quando A é 0° ou 180°, O e T giram em volta do mesmo
         * eixo e só a soma (ou a diferença) importa: nesse caso O fica 0 e T leva o giro todo.
         */
        fun fromTransform(tr: Transform): KawasakiPose {
            val m = tr.r
            val r13 = m[2]; val r23 = m[5]; val r33 = m[8]
            val r31 = m[6]; val r32 = m[7]
            val r11 = m[0]; val r21 = m[3]
            val sinA = sqrt(r13 * r13 + r23 * r23)
            val o: Double
            val a: Double
            val t: Double
            if (sinA > 1e-9) {
                a = atan2(sinA, r33)
                o = atan2(r23, r13)
                t = atan2(r32, -r31)
            } else if (r33 > 0) {
                a = 0.0
                o = 0.0
                t = atan2(r21, r11)
            } else {
                a = Math.PI
                o = 0.0
                t = atan2(-r21, -r11)
            }
            return KawasakiPose(
                tr.t.x, tr.t.y, tr.t.z,
                Math.toDegrees(o), Math.toDegrees(a), Math.toDegrees(t),
            )
        }

        /**
         * Lê uma linha de `.TRANS` ou a parte de posição do `WHERE`: "nome X Y Z O A T [E7…]"
         * ou só os números. Devolve null se não houver 6 números.
         */
        fun parse(line: String): KawasakiPose? {
            val nums = line.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (nums.size < 6) return null
            return KawasakiPose(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
        }
    }
}
