package my.robots.core.render3d

import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3

/**
 * Matriz 4x4 coluna a coluna (o formato do glTF e do Filament). Aceita escala, ao contrário do
 * [Transform], que é só giro + deslocamento.
 */
class Mat4(val m: DoubleArray = identity()) {

    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun applyPoint(x: Double, y: Double, z: Double) = Vec3(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    fun applyVector(x: Double, y: Double, z: Double) = Vec3(
        m[0] * x + m[4] * y + m[8] * z,
        m[1] * x + m[5] * y + m[9] * z,
        m[2] * x + m[6] * y + m[10] * z,
    )

    /** Inversa geral (cofatores). */
    fun inverse(): Mat4 {
        val a = m
        val inv = DoubleArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        require(kotlin.math.abs(det) > 1e-30) { "matriz sem inversa" }
        for (i in 0 until 16) inv[i] /= det
        return Mat4(inv)
    }

    fun toFloats(out: FloatArray = FloatArray(16)): FloatArray {
        for (i in 0 until 16) out[i] = m[i].toFloat()
        return out
    }

    companion object {
        fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

        fun of(floats: FloatArray) = Mat4(DoubleArray(16) { floats[it].toDouble() })

        fun scale(k: Double) = Mat4(doubleArrayOf(k, 0.0, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 0.0, 1.0))

        /** Giro + deslocamento do [Transform], sem mudar a unidade. */
        fun of(t: Transform): Mat4 {
            val r = t.r
            return Mat4(doubleArrayOf(
                r[0], r[3], r[6], 0.0,
                r[1], r[4], r[7], 0.0,
                r[2], r[5], r[8], 0.0,
                t.t.x, t.t.y, t.t.z, 1.0,
            ))
        }

        /** glTF: deslocamento, giro (quatérnio x, y, z, w) e escala. */
        fun trs(t: DoubleArray?, q: DoubleArray?, s: DoubleArray?): Mat4 {
            val (tx, ty, tz) = t ?: doubleArrayOf(0.0, 0.0, 0.0)
            val (qx, qy, qz, qw) = q ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            val (sx, sy, sz) = s ?: doubleArrayOf(1.0, 1.0, 1.0)
            val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
            val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
            val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
            return Mat4(doubleArrayOf(
                (1 - 2 * (yy + zz)) * sx, 2 * (xy + wz) * sx, 2 * (xz - wy) * sx, 0.0,
                2 * (xy - wz) * sy, (1 - 2 * (xx + zz)) * sy, 2 * (yz + wx) * sy, 0.0,
                2 * (xz + wy) * sz, 2 * (yz - wx) * sz, (1 - 2 * (xx + yy)) * sz, 0.0,
                tx, ty, tz, 1.0,
            ))
        }
    }
}
