package my.robots.core.kinematics

/** Sistema em que o JOG move o robô, como no controlador. */
enum class JogFrame(val label: String) {
    /** Cada eixo sozinho (os ângulos). */
    JOINT("Junta"),
    /** X, Y, Z do sistema da base em uso (o do `WHERE`). */
    BASE("Base"),
    /** X, Y, Z da ferramenta (o TCP). */
    TOOL("Tool"),
    /** X, Y, Z do espaço 3D (a grade). */
    WORLD("Mundo"),
}

/**
 * JOG cartesiano: anda o TCP em X, Y, Z ou gira em volta de X, Y, Z (sempre em volta do próprio
 * TCP) no sistema escolhido, e acha os ângulos com a cinemática inversa a partir dos atuais.
 */
object Jog {

    /**
     * Pose do TCP (sistema do robô) depois de um passo. [axis]: 0, 1, 2 = X, Y, Z (anda [amount] mm);
     * 3, 4, 5 = em volta de X, Y, Z (gira [amount] graus). [frame] não pode ser [JogFrame.JOINT].
     */
    fun target(model: RobotModel, deg: DoubleArray, frame: JogFrame, axis: Int, amount: Double): Transform {
        require(frame != JogFrame.JOINT) { "Junta não é um sistema cartesiano" }
        require(axis in 0..5) { "Eixo do JOG de 0 a 5" }
        val tcp = model.tcp(deg)
        // direções do sistema escolhido, escritas no sistema do robô
        val axes: Transform = when (frame) {
            JogFrame.BASE -> Transform.IDENTITY
            JogFrame.TOOL -> Transform(tcp.r, Vec3.ZERO)
            JogFrame.WORLD -> Transform((model.placement * model.robotFrame).inverse().r, Vec3.ZERO)
            JogFrame.JOINT -> error("")
        }
        val e = when (axis % 3) { 0 -> Vec3.X; 1 -> Vec3.Y; else -> Vec3.Z }
        val dir = axes.rotate(e)
        return if (axis < 3) {
            Transform.translation(dir * amount) * tcp
        } else {
            val p = tcp.t
            Transform.translation(p) * Transform.rotation(dir, Math.toRadians(amount)) * Transform.translation(-p) * tcp
        }
    }

    /** Um passo de JOG: os ângulos novos, ou null se o robô não alcança (limite ou fora do alcance). */
    fun step(model: RobotModel, deg: DoubleArray, frame: JogFrame, axis: Int, amount: Double): DoubleArray? {
        val goal = target(model, deg, frame, axis, amount)
        val r = InverseKinematics(model, toleranceMm = 0.05, toleranceDeg = 0.01).solve(goal, deg)
        if (!r.success) return null
        // perto de singularidade um passo pequeno vira um giro grande do punho: não aceita
        if (r.deg.indices.any { kotlin.math.abs(r.deg[it] - deg[it]) > 30.0 }) return null
        return r.deg
    }
}
