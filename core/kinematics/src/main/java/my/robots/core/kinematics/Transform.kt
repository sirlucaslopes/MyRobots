package my.robots.core.kinematics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transformação rígida: rotação 3x3 (linha a linha em [r]) seguida de translação [t].
 * Leva um ponto do sistema "filho" para o sistema "pai": p_pai = R * p_filho + t.
 *
 * Composição: `a * b` aplica primeiro [b] e depois [a] (como matrizes 4x4).
 */
class Transform(r: DoubleArray, val t: Vec3) {

    val r: DoubleArray = r.copyOf()

    init {
        require(r.size == 9) { "Rotação precisa de 9 valores" }
    }

    operator fun times(o: Transform): Transform {
        val m = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            m[i * 3 + j] = r[i * 3] * o.r[j] + r[i * 3 + 1] * o.r[3 + j] + r[i * 3 + 2] * o.r[6 + j]
        }
        return Transform(m, apply(o.t))
    }

    /** Ponto no sistema filho → ponto no sistema pai (gira e desloca). */
    fun apply(p: Vec3) = rotate(p) + t

    /** Só gira (para direções, que não se deslocam). */
    fun rotate(v: Vec3) = Vec3(
        r[0] * v.x + r[1] * v.y + r[2] * v.z,
        r[3] * v.x + r[4] * v.y + r[5] * v.z,
        r[6] * v.x + r[7] * v.y + r[8] * v.z,
    )

    fun inverse(): Transform {
        val rt = doubleArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
        val inv = Transform(rt, Vec3.ZERO)
        return Transform(rt, -inv.rotate(t))
    }

    /** Eixos do sistema filho vistos do pai (colunas da rotação). */
    val xAxis get() = Vec3(r[0], r[3], r[6])
    val yAxis get() = Vec3(r[1], r[4], r[7])
    val zAxis get() = Vec3(r[2], r[5], r[8])

    /**
     * Rotação que leva esta orientação até [o], como eixo × ângulo (radianos) no sistema pai.
     * Usada para medir o erro de orientação na cinemática inversa.
     */
    fun rotationErrorTo(o: Transform): Vec3 {
        // Rerr = Ro * R^T
        val a = o.r
        val b = r
        val m = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            m[i * 3 + j] = a[i * 3] * b[j * 3] + a[i * 3 + 1] * b[j * 3 + 1] + a[i * 3 + 2] * b[j * 3 + 2]
        }
        return rotationVector(m)
    }

    fun isClose(o: Transform, mm: Double = 1e-6, rad: Double = 1e-9): Boolean =
        (t - o.t).length() <= mm && rotationErrorTo(o).length() <= rad

    override fun toString() = "Transform(t=$t, r=${r.joinToString(prefix = "[", postfix = "]")})"

    companion object {
        val IDENTITY = Transform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), Vec3.ZERO)

        fun translation(t: Vec3) = Transform(IDENTITY.r, t)

        /** Rotação de [angleRad] em volta do eixo [axis] (regra da mão direita), passando pela origem. */
        fun rotation(axis: Vec3, angleRad: Double): Transform {
            val u = axis.normalized()
            val c = cos(angleRad)
            val s = sin(angleRad)
            val k = 1 - c
            return Transform(
                doubleArrayOf(
                    c + u.x * u.x * k, u.x * u.y * k - u.z * s, u.x * u.z * k + u.y * s,
                    u.y * u.x * k + u.z * s, c + u.y * u.y * k, u.y * u.z * k - u.x * s,
                    u.z * u.x * k - u.y * s, u.z * u.y * k + u.x * s, c + u.z * u.z * k,
                ),
                Vec3.ZERO,
            )
        }

        fun rotZ(angleRad: Double) = rotation(Vec3.Z, angleRad)
        fun rotY(angleRad: Double) = rotation(Vec3.Y, angleRad)

        /**
         * Sistema com origem em [origin] e eixo Z em [zDir]. O eixo X sai perpendicular a Z,
         * o mais perto possível de [xHint] (se for paralelo a Z, escolhe outro).
         * É assim que um eixo marcado na peça (um ponto e uma direção) vira um sistema completo.
         */
        fun fromAxis(origin: Vec3, zDir: Vec3, xHint: Vec3 = Vec3.X): Transform {
            val z = zDir.normalized()
            var hint = xHint
            if (abs(hint.normalized().dot(z)) > 0.99) hint = if (abs(z.x) < 0.9) Vec3.X else Vec3.Y
            val x = (hint - z * hint.dot(z)).normalized()
            val y = z.cross(x)
            return Transform(doubleArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z), origin)
        }

        /** Matriz de rotação → eixo × ângulo (radianos). */
        internal fun rotationVector(m: DoubleArray): Vec3 {
            // atan2 em vez de acos: acos perde precisão perto de 0°, que é justo onde a
            // cinemática inversa decide se já chegou.
            val cosA = ((m[0] + m[4] + m[8] - 1) / 2).coerceIn(-1.0, 1.0)
            val v = Vec3(m[7] - m[5], m[2] - m[6], m[3] - m[1])
            val sinA = v.length() / 2
            val angle = atan2(sinA, cosA)
            if (angle < 1e-15) return Vec3.ZERO
            if (sinA > 1e-6) return v * (angle / (2 * sinA))
            // Perto de 180°: o eixo sai da diagonal.
            val xx = sqrt(((m[0] + 1) / 2).coerceAtLeast(0.0))
            val yy = sqrt(((m[4] + 1) / 2).coerceAtLeast(0.0))
            val zz = sqrt(((m[8] + 1) / 2).coerceAtLeast(0.0))
            val axis = when {
                xx >= yy && xx >= zz -> Vec3(xx, m[1] / (2 * xx), m[2] / (2 * xx))
                yy >= zz -> Vec3(m[1] / (2 * yy), yy, m[5] / (2 * yy))
                else -> Vec3(m[2] / (2 * zz), m[5] / (2 * zz), zz)
            }
            return axis.normalized() * angle
        }
    }
}
