package my.robots.core.kinematics

import kotlin.math.sqrt

/** Vetor ou ponto 3D. Distâncias em milímetros, como no controlador. */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))

    /** Mesmo sentido, comprimento 1. Vetor nulo não tem direção: lança erro. */
    fun normalized(): Vec3 {
        val len = length()
        require(len > 1e-12) { "Vetor nulo não tem direção" }
        return this * (1.0 / len)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}
