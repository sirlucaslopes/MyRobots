package my.robots.feature.robot3d

import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Malha de triângulos (posições, normais e índices) feita com formas simples: caixa e cilindro.
 * As formas recebem medidas em **mm** (como o `:core:kinematics`) e guardam em **metros**
 * (como o glTF e o Filament).
 */
class MeshData {
    private val pos = ArrayList<Float>()
    private val nor = ArrayList<Float>()
    private val idx = ArrayList<Int>()

    val vertexCount get() = pos.size / 3
    val indexCount get() = idx.size
    fun isEmpty() = idx.isEmpty()

    fun positions() = pos.toFloatArray()
    fun normals() = nor.toFloatArray()
    fun indices() = idx.toIntArray()

    /** Menor e maior coordenada (o glTF exige no acessor de posição). */
    fun bounds(): Pair<FloatArray, FloatArray> {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in pos.indices) {
            val k = i % 3
            if (pos[i] < min[k]) min[k] = pos[i]
            if (pos[i] > max[k]) max[k] = pos[i]
        }
        if (pos.isEmpty()) return FloatArray(3) to FloatArray(3)
        return min to max
    }

    private fun vertex(pMm: Vec3, n: Vec3): Int {
        pos += (pMm.x * MM).toFloat(); pos += (pMm.y * MM).toFloat(); pos += (pMm.z * MM).toFloat()
        nor += n.x.toFloat(); nor += n.y.toFloat(); nor += n.z.toFloat()
        return vertexCount - 1
    }

    /** Quadrilátero a-b-c-d (anti-horário visto do lado da normal). */
    private fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, n: Vec3) {
        val i = vertex(a, n); vertex(b, n); vertex(c, n); vertex(d, n)
        idx += i; idx += i + 1; idx += i + 2
        idx += i; idx += i + 2; idx += i + 3
    }

    /** Caixa com centro e eixos de [frame] e meias medidas hx, hy, hz (mm). */
    fun box(frame: Transform, hx: Double, hy: Double, hz: Double): MeshData {
        fun p(x: Double, y: Double, z: Double) = frame.apply(Vec3(x * hx, y * hy, z * hz))
        val ex = frame.xAxis; val ey = frame.yAxis; val ez = frame.zAxis
        quad(p(1.0, -1.0, -1.0), p(1.0, 1.0, -1.0), p(1.0, 1.0, 1.0), p(1.0, -1.0, 1.0), ex)
        quad(p(-1.0, 1.0, -1.0), p(-1.0, -1.0, -1.0), p(-1.0, -1.0, 1.0), p(-1.0, 1.0, 1.0), -ex)
        quad(p(1.0, 1.0, -1.0), p(-1.0, 1.0, -1.0), p(-1.0, 1.0, 1.0), p(1.0, 1.0, 1.0), ey)
        quad(p(-1.0, -1.0, -1.0), p(1.0, -1.0, -1.0), p(1.0, -1.0, 1.0), p(-1.0, -1.0, 1.0), -ey)
        quad(p(-1.0, -1.0, 1.0), p(1.0, -1.0, 1.0), p(1.0, 1.0, 1.0), p(-1.0, 1.0, 1.0), ez)
        quad(p(-1.0, 1.0, -1.0), p(1.0, 1.0, -1.0), p(1.0, -1.0, -1.0), p(-1.0, -1.0, -1.0), -ez)
        return this
    }

    /** Caixa alinhada aos eixos, de [min] a [max] (mm). */
    fun box(min: Vec3, max: Vec3): MeshData {
        val c = (min + max) * 0.5
        return box(Transform.translation(c), (max.x - min.x) / 2, (max.y - min.y) / 2, (max.z - min.z) / 2)
    }

    /** "Osso" de [a] até [b] com seção quadrada de lado [width] (mm). Pontos iguais: nada. */
    fun bone(a: Vec3, b: Vec3, width: Double): MeshData {
        val d = b - a
        val len = d.length()
        if (len < 1e-6) return this
        val frame = Transform.fromAxis((a + b) * 0.5, d)
        return box(frame, width / 2, width / 2, len / 2)
    }

    /** Cilindro em volta do Z de [frame], centrado na origem dele. */
    fun cylinder(frame: Transform, radius: Double, halfLength: Double, segments: Int = 24): MeshData {
        val ez = frame.zAxis
        fun ring(i: Int) = Vec3(cos(2 * Math.PI * i / segments), sin(2 * Math.PI * i / segments), 0.0)
        for (i in 0 until segments) {
            val r0 = ring(i); val r1 = ring(i + 1)
            val a = frame.apply(r0 * radius + Vec3(0.0, 0.0, -halfLength))
            val b = frame.apply(r1 * radius + Vec3(0.0, 0.0, -halfLength))
            val c = frame.apply(r1 * radius + Vec3(0.0, 0.0, halfLength))
            val d = frame.apply(r0 * radius + Vec3(0.0, 0.0, halfLength))
            // lado: normal de cada vértice aponta para fora (fica liso)
            val n0 = frame.rotate(r0); val n1 = frame.rotate(r1)
            val i0 = vertex(a, n0); vertex(b, n1); vertex(c, n1); vertex(d, n0)
            idx += i0; idx += i0 + 1; idx += i0 + 2
            idx += i0; idx += i0 + 2; idx += i0 + 3
            // tampas
            val top = frame.apply(Vec3(0.0, 0.0, halfLength))
            val bottom = frame.apply(Vec3(0.0, 0.0, -halfLength))
            val t = vertex(top, ez); vertex(d, ez); vertex(c, ez)
            idx += t; idx += t + 1; idx += t + 2
            val u = vertex(bottom, -ez); vertex(b, -ez); vertex(a, -ez)
            idx += u; idx += u + 1; idx += u + 2
        }
        return this
    }

    companion object {
        /** mm → m. */
        const val MM = 0.001
    }
}
