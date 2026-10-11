package my.robots.core.render3d

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

    /** Traço de letra de [a] até [b] (mm), com as pontas esticadas meia largura para os cantos fecharem. */
    fun stroke(a: Vec3, b: Vec3, width: Double): MeshData {
        val d = b - a
        if (d.length() < 1e-6) return this
        val e = d.normalized() * (width / 2)
        return bone(a - e, b + e, width)
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

    /** Cone com a base no plano Z = 0 de [frame] (raio [radius]) e a ponta em Z = [length]. */
    fun cone(frame: Transform, radius: Double, length: Double, segments: Int = 24): MeshData {
        val ez = frame.zAxis
        val tip = frame.apply(Vec3(0.0, 0.0, length))
        val center = frame.apply(Vec3.ZERO)
        // a normal da lateral inclina para a ponta na proporção raio / comprimento
        val slope = radius / length
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val r0 = Vec3(cos(a0), sin(a0), 0.0); val r1 = Vec3(cos(a1), sin(a1), 0.0)
            val p0 = frame.apply(r0 * radius); val p1 = frame.apply(r1 * radius)
            val n0 = frame.rotate(Vec3(r0.x, r0.y, slope).normalized())
            val n1 = frame.rotate(Vec3(r1.x, r1.y, slope).normalized())
            val nt = frame.rotate(Vec3((r0.x + r1.x) / 2, (r0.y + r1.y) / 2, slope).normalized())
            val s = vertex(p0, n0); vertex(p1, n1); vertex(tip, nt)
            idx += s; idx += s + 1; idx += s + 2
            val b = vertex(center, -ez); vertex(p1, -ez); vertex(p0, -ez)
            idx += b; idx += b + 1; idx += b + 2
        }
        return this
    }

    /** Esfera de raio [radius] (mm) em volta de [center]. */
    fun sphere(center: Vec3, radius: Double, rings: Int = 12, segments: Int = 24): MeshData {
        val first = vertexCount
        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            for (s in 0..segments) {
                val th = 2 * Math.PI * s / segments
                val n = Vec3(sin(phi) * cos(th), sin(phi) * sin(th), cos(phi))
                vertex(center + n * radius, n)
            }
        }
        val row = segments + 1
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = first + r * row + s
            val b = a + row
            idx += a; idx += b; idx += a + 1
            idx += a + 1; idx += b; idx += b + 1
        }
        return this
    }

    /**
     * Tubo em arco em volta do Z de [frame]: raio do arco [arcRadius], do tubo [tubeRadius],
     * de [startRad] girando [sweepRad] (positivo = anti-horário visto de +Z).
     */
    fun arc(frame: Transform, arcRadius: Double, tubeRadius: Double, startRad: Double, sweepRad: Double, segments: Int = 48, sides: Int = 10): MeshData {
        val first = vertexCount
        for (i in 0..segments) {
            val a = startRad + sweepRad * i / segments
            val radial = Vec3(cos(a), sin(a), 0.0)
            for (k in 0..sides) {
                val b = 2 * Math.PI * k / sides
                val n = radial * cos(b) + Vec3.Z * sin(b)
                vertex(frame.apply(radial * arcRadius + n * tubeRadius), frame.rotate(n))
            }
        }
        val row = sides + 1
        for (i in 0 until segments) for (k in 0 until sides) {
            val a = first + i * row + k
            val b = a + row
            // vira a ordem quando o arco gira para trás, para as faces ficarem para fora
            if (sweepRad >= 0) { idx += a; idx += b; idx += a + 1; idx += a + 1; idx += b; idx += b + 1 }
            else { idx += a; idx += a + 1; idx += b; idx += a + 1; idx += b + 1; idx += b }
        }
        return this
    }

    /** Seta de [from] na direção [dir]: haste de raio [shaft] e ponta em cone de [headLength]. */
    fun arrow(from: Vec3, dir: Vec3, length: Double, shaft: Double, headRadius: Double, headLength: Double): MeshData {
        val d = dir.normalized()
        val shaftLen = (length - headLength).coerceAtLeast(0.0)
        if (shaftLen > 0) cylinder(Transform.fromAxis(from + d * (shaftLen / 2), d), shaft, shaftLen / 2, 16)
        cone(Transform.fromAxis(from + d * shaftLen, d), headRadius, headLength, 20)
        return this
    }

    companion object {
        /** mm → m. */
        const val MM = 0.001
    }
}
