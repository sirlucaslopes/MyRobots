package my.robots.core.kinematics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Movimento linear (como o LMOVE): o TCP anda em linha reta de um ponto ao outro e a orientação
 * gira por igual no caminho (em volta de um eixo só). Os ângulos dos eixos saem da cinemática
 * inversa a cada pedaço do caminho, partindo do pedaço anterior.
 */
object LinearMotion {

    /**
     * Caminho calculado: os ângulos em cada pedaço (o primeiro é o ponto de partida), o
     * comprimento da reta e o giro da orientação. Com [ok] falso, [joints] vai até onde deu e
     * [problem] diz o porquê.
     */
    data class Path(
        val joints: List<DoubleArray>,
        val lengthMm: Double,
        val rotationDeg: Double,
        val ok: Boolean,
        val problem: String? = null,
    )

    /** Pose no instante [s] (0 a 1) da reta de [a] até [b]: posição em reta, giro em volta de um eixo. */
    fun interpolate(a: Transform, b: Transform, s: Double): Transform {
        val p = a.t + (b.t - a.t) * s
        val v = a.rotationErrorTo(b) // Rb = Rerr · Ra, como eixo × ângulo
        val angle = v.length()
        val rot = if (angle < 1e-12) Transform(a.r, Vec3.ZERO) else Transform.rotation(v, angle * s) * Transform(a.r, Vec3.ZERO)
        return Transform(rot.r, p)
    }

    /**
     * Calcula a reta de [from] (ângulos atuais) até [target] (sistema do robô, como o `WHERE`).
     * Pedaços de até [stepMm] e [stepDeg]. Falha se um pedaço fica fora do alcance ou se algum
     * eixo pula mais que [maxJumpDeg] entre pedaços (perto de singularidade o robô "vira" o punho).
     */
    fun plan(
        model: RobotModel,
        from: DoubleArray,
        target: Transform,
        stepMm: Double = 5.0,
        stepDeg: Double = 2.0,
        maxJumpDeg: Double = 20.0,
    ): Path {
        val ik = InverseKinematics(model, toleranceMm = 0.05, toleranceDeg = 0.01)
        val start = model.tcp(from)
        val length = (target.t - start.t).length()
        val rotation = Math.toDegrees(start.rotationErrorTo(target).length())
        val n = max(1, max(ceil(length / stepMm), ceil(rotation / stepDeg)).toInt())
        val out = ArrayList<DoubleArray>(n + 1)
        out += from.copyOf()
        var prev = from.copyOf()
        for (i in 1..n) {
            val pose = interpolate(start, target, i.toDouble() / n)
            val r = ik.solve(pose, prev)
            if (!r.success) {
                return Path(out, length, rotation, false,
                    String.format(java.util.Locale.US, "Fora do alcance em linha reta a %.0f%% do caminho (erro de %.1f mm).", 100.0 * i / n, r.positionErrorMm))
            }
            val jump = r.deg.indices.maxOf { abs(r.deg[it] - prev[it]) }
            if (jump > maxJumpDeg) {
                return Path(out, length, rotation, false,
                    String.format(java.util.Locale.US, "Um eixo pula %.0f° a %.0f%% do caminho: perto de uma singularidade.", jump, 100.0 * i / n))
            }
            out += r.deg
            prev = r.deg
        }
        return Path(out, length, rotation, true)
    }

    /** Ângulos no ponto [u] (0 a 1) do caminho, entre os dois pedaços mais perto. */
    fun sample(path: Path, u: Double): DoubleArray {
        val j = path.joints
        if (j.size == 1) return j[0].copyOf()
        val x = u.coerceIn(0.0, 1.0) * (j.size - 1)
        val i = x.toInt().coerceAtMost(j.size - 2)
        val f = x - i
        return DoubleArray(j[i].size) { k -> j[i][k] + (j[i + 1][k] - j[i][k]) * f }
    }
}
