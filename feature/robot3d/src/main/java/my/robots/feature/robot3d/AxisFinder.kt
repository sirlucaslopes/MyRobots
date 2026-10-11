package my.robots.feature.robot3d

import my.robots.core.kinematics.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Eixo achado numa face da peça (mm, espaço do app). [point] fica na linha do eixo, na altura do
 * meio da face; [direction] tem comprimento 1. [errorMm] diz o quanto a face foge do círculo
 * achado (0 = círculo perfeito).
 */
data class AxisGuess(
    val kind: Kind,
    val point: Vec3,
    val direction: Vec3,
    val radiusMm: Double,
    val errorMm: Double,
) {
    enum class Kind(val label: String) {
        /** Face que gira em volta do eixo: cilindro, cone, furo. */
        REDONDA("face redonda"),
        /** Face plana: o eixo é a normal, passando pelo centro do contorno. */
        PLANA("face plana"),
        /** Dois pontos tocados. */
        DOIS_PONTOS("2 pontos"),
    }

    fun reversed() = copy(direction = -direction)
}

/**
 * O "Círculo" do montador: acha o eixo de uma junta a partir de uma face tocada.
 *
 * - Face redonda (cilindro, cone): as normais de uma face que gira em volta de um eixo apontam
 *   todas para ele. A direção do eixo é a que fica mais perpendicular a todas as normais (o menor
 *   autovetor de Σ n·nᵀ) e o centro é o ponto mais perto de todas as retas das normais.
 * - Face plana (a tampa da junta): o eixo é a normal da face e passa pelo centro do círculo que
 *   melhor encaixa no contorno dela.
 */
object AxisFinder {

    /** Normais quase iguais (a segunda direção some): é plano. */
    private const val PLANE_LIMIT = 0.02
    /** Acima disso a face não gira em volta de um eixo só (esfera, canto). */
    private const val ROUND_LIMIT = 0.12
    /** Direções a menos disso (graus) de X, Y ou Z viram exatamente X, Y ou Z. */
    private const val SNAP_DEG = 0.3

    fun fromFace(mesh: PartMesh, triangles: IntArray): AxisGuess? {
        if (triangles.isEmpty()) return null
        val verts = LinkedHashSet<Int>()
        for (t in triangles) for (k in 0 until 3) verts += mesh.indices[t * 3 + k]
        val points = verts.map { mesh.vertex(it) }
        val normals = verts.map { v ->
            val n = mesh.normal(v)
            if (n.length() > 0.5) n else Vec3.ZERO
        }.toMutableList()
        // normal faltando: a do triângulo
        if (normals.any { it == Vec3.ZERO }) {
            val byVertex = HashMap<Int, Vec3>()
            for (t in triangles) for (k in 0 until 3) byVertex.putIfAbsent(mesh.indices[t * 3 + k], mesh.triangleNormal(t))
            verts.forEachIndexed { i, v -> if (normals[i] == Vec3.ZERO) normals[i] = byVertex[v] ?: Vec3.ZERO }
        }

        val cov = DoubleArray(9)
        var used = 0
        for (n in normals) {
            if (n == Vec3.ZERO) continue
            cov[0] += n.x * n.x; cov[1] += n.x * n.y; cov[2] += n.x * n.z
            cov[4] += n.y * n.y; cov[5] += n.y * n.z; cov[8] += n.z * n.z
            used++
        }
        if (used < 3) return null
        for (i in cov.indices) cov[i] /= used
        cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]
        val (values, vectors) = symmetricEigen(cov)

        return when {
            values[1] < PLANE_LIMIT -> planeAxis(mesh, triangles, points, normals)
            values[2] < ROUND_LIMIT -> roundAxis(points, normals, vectors[2])
            else -> null
        }
    }

    /** Eixo pelos dois pontos, de [a] para [b]. */
    fun fromTwoPoints(a: Vec3, b: Vec3): AxisGuess? {
        val d = b - a
        if (d.length() < 0.5) return null
        return AxisGuess(AxisGuess.Kind.DOIS_PONTOS, (a + b) * 0.5, snap(d.normalized()), 0.0, 0.0)
    }

    private fun roundAxis(points: List<Vec3>, normals: List<Vec3>, axis: Vec3): AxisGuess? {
        val d = snap(orient(axis))
        val (u, w) = basis(d)
        // mínimos quadrados no plano perpendicular ao eixo: Σ (I − n nᵀ) c = Σ (I − n nᵀ) p
        var a00 = 0.0; var a01 = 0.0; var a11 = 0.0; var b0 = 0.0; var b1 = 0.0
        var count = 0
        for (i in points.indices) {
            val n = normals[i]
            var nx = n.dot(u); var ny = n.dot(w)
            val len = sqrt(nx * nx + ny * ny)
            if (len < 0.2) continue // normal quase no sentido do eixo (borda, tampa)
            nx /= len; ny /= len
            val px = points[i].dot(u); val py = points[i].dot(w)
            val m00 = 1 - nx * nx; val m01 = -nx * ny; val m11 = 1 - ny * ny
            a00 += m00; a01 += m01; a11 += m11
            b0 += m00 * px + m01 * py
            b1 += m01 * px + m11 * py
            count++
        }
        if (count < 3) return null
        val det = a00 * a11 - a01 * a01
        if (abs(det) < 1e-9 * count * count) return null // normais paralelas: não há centro
        val cx = (a11 * b0 - a01 * b1) / det
        val cy = (a00 * b1 - a01 * b0) / det
        val height = points.sumOf { it.dot(d) } / points.size
        val center = u * cx + w * cy + d * height

        val dist = points.map { p -> radial(p, center, d) }
        val r = dist.average()
        val err = sqrt(dist.sumOf { (it - r) * (it - r) } / dist.size)
        return AxisGuess(AxisGuess.Kind.REDONDA, center, d, r, err)
    }

    private fun planeAxis(mesh: PartMesh, triangles: IntArray, points: List<Vec3>, normals: List<Vec3>): AxisGuess? {
        var sum = Vec3.ZERO
        for (n in normals) sum += n
        if (sum.length() < 1e-9) return null
        val d = snap(sum.normalized())
        val (u, w) = basis(d)
        // contorno: arestas usadas por um triângulo só (pela posição: vale com vértices repetidos)
        val edges = HashMap<Long, Int>()
        for (t in triangles) for (k in 0 until 3) {
            val a = mesh.positionId(mesh.indices[t * 3 + k])
            val b = mesh.positionId(mesh.indices[t * 3 + (k + 1) % 3])
            val key = if (a < b) a.toLong() shl 32 or b.toLong() else b.toLong() shl 32 or a.toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        val boundary = LinkedHashSet<Int>()
        for ((key, n) in edges) if (n == 1) { boundary += (key ushr 32).toInt(); boundary += (key and 0xFFFFFFFFL).toInt() }
        val ring = if (boundary.size >= 3) boundary.map { mesh.vertex(it) } else points

        // círculo de Kåsa: x² + y² + D x + E y + F = 0
        val m = DoubleArray(9)
        val rhs = DoubleArray(3)
        for (p in ring) {
            val x = p.dot(u); val y = p.dot(w)
            val row = doubleArrayOf(x, y, 1.0)
            val z = -(x * x + y * y)
            for (i in 0 until 3) {
                for (j in 0 until 3) m[i * 3 + j] += row[i] * row[j]
                rhs[i] += row[i] * z
            }
        }
        val sol = solve3(m, rhs) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0) return null
        val height = points.sumOf { it.dot(d) } / points.size
        val center = u * cx + w * cy + d * height
        val r = sqrt(r2)
        // erro: o quanto cada ponto do contorno foge do círculo (numa coroa, a metade da largura)
        val err = sqrt(ring.sumOf { val e = radial(it, center, d) - r; e * e } / ring.size)
        return AxisGuess(AxisGuess.Kind.PLANA, center, d, r, err)
    }

    /** Distância de [p] até a reta ([c], [d]). */
    private fun radial(p: Vec3, c: Vec3, d: Vec3): Double {
        val v = p - c
        return (v - d * v.dot(d)).length()
    }

    /** O maior componente da direção fica positivo (o usuário inverte depois, se precisar). */
    private fun orient(d: Vec3): Vec3 {
        val ax = abs(d.x); val ay = abs(d.y); val az = abs(d.z)
        val big = if (ax >= ay && ax >= az) d.x else if (ay >= az) d.y else d.z
        return if (big < 0) -d else d
    }

    internal fun snap(d: Vec3): Vec3 {
        val limit = kotlin.math.cos(Math.toRadians(SNAP_DEG))
        for (axis in listOf(Vec3.X, Vec3.Y, Vec3.Z)) {
            val c = d.dot(axis)
            if (c >= limit) return axis
            if (c <= -limit) return -axis
        }
        return d
    }

    /** Dois vetores perpendiculares a [d] e entre si. */
    internal fun basis(d: Vec3): Pair<Vec3, Vec3> {
        val helper = if (abs(d.x) < 0.9) Vec3.X else Vec3.Y
        val u = d.cross(helper).normalized()
        val w = d.cross(u)
        return u to w
    }

    private fun solve3(m: DoubleArray, b: DoubleArray): DoubleArray? {
        val a = m.copyOf()
        val x = b.copyOf()
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(a[r * 3 + col]) > abs(a[piv * 3 + col])) piv = r
            if (abs(a[piv * 3 + col]) < 1e-12) return null
            if (piv != col) {
                for (k in 0 until 3) { val t = a[col * 3 + k]; a[col * 3 + k] = a[piv * 3 + k]; a[piv * 3 + k] = t }
                val t = x[col]; x[col] = x[piv]; x[piv] = t
            }
            for (r in 0 until 3) {
                if (r == col) continue
                val f = a[r * 3 + col] / a[col * 3 + col]
                for (k in col until 3) a[r * 3 + k] -= f * a[col * 3 + k]
                x[r] -= f * x[col]
            }
        }
        return DoubleArray(3) { x[it] / a[it * 3 + it] }
    }

    /**
     * Autovalores (do maior ao menor) e autovetores de uma matriz simétrica 3x3 (Jacobi).
     */
    internal fun symmetricEigen(m: DoubleArray): Pair<DoubleArray, List<Vec3>> {
        val a = m.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(50) {
            var p = 0; var q = 1
            var biggest = abs(a[1])
            if (abs(a[2]) > biggest) { biggest = abs(a[2]); p = 0; q = 2 }
            if (abs(a[5]) > biggest) { biggest = abs(a[5]); p = 1; q = 2 }
            if (biggest < 1e-15) return@repeat
            val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
            val theta = (aqq - app) / (2 * apq)
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1)
            val s = t * c
            for (k in 0 until 3) {
                val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                a[k * 3 + p] = c * akp - s * akq
                a[k * 3 + q] = s * akp + c * akq
            }
            for (k in 0 until 3) {
                val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                a[p * 3 + k] = c * apk - s * aqk
                a[q * 3 + k] = s * apk + c * aqk
            }
            for (k in 0 until 3) {
                val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                v[k * 3 + p] = c * vkp - s * vkq
                v[k * 3 + q] = s * vkp + c * vkq
            }
        }
        val order = (0 until 3).sortedByDescending { a[it * 3 + it] }
        val values = DoubleArray(3) { a[order[it] * 3 + order[it]] }
        val vectors = order.map { Vec3(v[it], v[3 + it], v[6 + it]).normalized() }
        return values to vectors
    }
}
