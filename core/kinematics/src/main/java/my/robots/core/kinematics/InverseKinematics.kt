package my.robots.core.kinematics

import kotlin.math.abs

/** Resultado da cinemática inversa. [deg] vem sempre dentro dos limites, mesmo sem sucesso. */
data class IkResult(
    val deg: DoubleArray,
    val success: Boolean,
    val positionErrorMm: Double,
    val rotationErrorDeg: Double,
    val iterations: Int,
)

/**
 * Cinemática inversa numérica: acha os ângulos dos eixos que levam o TCP até um alvo.
 *
 * Usa mínimos quadrados amortecidos com Jacobiano numérico, então serve para qualquer robô
 * montado no montador, inclusive punho com offset (como o do KJ264), que não tem fórmula fechada.
 * Parte de [seed] (normalmente a posição atual), por isso devolve a solução mais próxima dela:
 * nos eixos que dão mais de uma volta (±720°) o robô não "desenrola" à toa.
 */
class InverseKinematics(
    private val model: RobotModel,
    private val toleranceMm: Double = 0.01,
    private val toleranceDeg: Double = 0.001,
    private val maxIterations: Int = 300,
    /** Peso do erro de orientação: 1 rad conta como este tanto de mm. */
    private val rotationWeightMm: Double = 500.0,
    private val maxStepDeg: Double = 10.0,
) {

    /** Alvo no sistema do robô (o mesmo do `WHERE` e do `.TRANS`). */
    fun solve(target: Transform, seed: DoubleArray): IkResult {
        val n = model.axisCount
        require(seed.size == n) { "São $n eixos e vieram ${seed.size} ângulos" }
        var q = model.clamp(seed)
        var lambda = 1.0
        var err = error(q, target)
        var errNorm = norm(err)

        for (iter in 1..maxIterations) {
            if (converged(q, target)) return result(q, target, true, iter - 1)

            val jac = jacobian(q)
            val step = dampedStep(jac, err, lambda, n)
            limitStep(step)
            val candidate = model.clamp(DoubleArray(n) { q[it] + step[it] })
            val candErr = error(candidate, target)
            val candNorm = norm(candErr)
            if (candNorm < errNorm) {
                q = candidate
                err = candErr
                errNorm = candNorm
                lambda = (lambda * 0.5).coerceAtLeast(1e-4)
            } else {
                lambda = (lambda * 4).coerceAtMost(1e4)
                if (lambda >= 1e4) break
            }
        }
        val ok = converged(q, target)
        return result(q, target, ok, maxIterations)
    }

    fun solve(target: KawasakiPose, seed: DoubleArray) = solve(target.toTransform(), seed)

    private fun converged(q: DoubleArray, target: Transform): Boolean {
        val cur = model.tcp(q)
        return (target.t - cur.t).length() <= toleranceMm &&
            Math.toDegrees(cur.rotationErrorTo(target).length()) <= toleranceDeg
    }

    private fun result(q: DoubleArray, target: Transform, ok: Boolean, iterations: Int): IkResult {
        val cur = model.tcp(q)
        return IkResult(
            deg = q,
            success = ok,
            positionErrorMm = (target.t - cur.t).length(),
            rotationErrorDeg = Math.toDegrees(cur.rotationErrorTo(target).length()),
            iterations = iterations,
        )
    }

    /** Erro em 6 números: posição (mm) e orientação (rad × peso). */
    private fun error(q: DoubleArray, target: Transform): DoubleArray {
        val cur = model.tcp(q)
        val p = target.t - cur.t
        val r = cur.rotationErrorTo(target) * rotationWeightMm
        return doubleArrayOf(p.x, p.y, p.z, r.x, r.y, r.z)
    }

    /** Como o TCP se mexe quando cada eixo gira um pouco (colunas por grau). */
    private fun jacobian(q: DoubleArray): Array<DoubleArray> {
        val n = q.size
        val base = model.tcp(q)
        val h = 1e-4
        val jac = Array(6) { DoubleArray(n) }
        for (k in 0 until n) {
            val qq = q.copyOf()
            qq[k] += h
            val moved = model.tcp(qq)
            val dp = (moved.t - base.t) * (1 / h)
            val dr = base.rotationErrorTo(moved) * (rotationWeightMm / h)
            jac[0][k] = dp.x; jac[1][k] = dp.y; jac[2][k] = dp.z
            jac[3][k] = dr.x; jac[4][k] = dr.y; jac[5][k] = dr.z
        }
        return jac
    }

    /** Resolve (JᵀJ + λ²I) Δ = Jᵀe. */
    private fun dampedStep(jac: Array<DoubleArray>, e: DoubleArray, lambda: Double, n: Int): DoubleArray {
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                var s = 0.0
                for (r in 0 until 6) s += jac[r][i] * jac[r][j]
                a[i][j] = s
            }
            a[i][i] += lambda * lambda
            var s = 0.0
            for (r in 0 until 6) s += jac[r][i] * e[r]
            b[i] = s
        }
        return solveLinear(a, b)
    }

    private fun limitStep(step: DoubleArray) {
        val biggest = step.maxOf { abs(it) }
        if (biggest > maxStepDeg) {
            val k = maxStepDeg / biggest
            for (i in step.indices) step[i] *= k
        }
    }

    private fun norm(v: DoubleArray) = kotlin.math.sqrt(v.sumOf { it * it })

    companion object {
        /** Eliminação de Gauss com pivô parcial (sistemas pequenos, n = número de eixos). */
        internal fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { a[it].copyOf() }
            val x = b.copyOf()
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-15) continue
                if (pivot != col) {
                    val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                    val tb = x[col]; x[col] = x[pivot]; x[pivot] = tb
                }
                for (r in col + 1 until n) {
                    val f = m[r][col] / m[col][col]
                    if (f == 0.0) continue
                    for (c in col until n) m[r][c] -= f * m[col][c]
                    x[r] -= f * x[col]
                }
            }
            val out = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = x[r]
                for (c in r + 1 until n) s -= m[r][c] * out[c]
                out[r] = if (abs(m[r][r]) < 1e-15) 0.0 else s / m[r][r]
            }
            return out
        }
    }
}
