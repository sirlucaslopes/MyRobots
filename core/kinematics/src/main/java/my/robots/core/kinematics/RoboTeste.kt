package my.robots.core.kinematics

/**
 * Robô de teste com 6 eixos e punho com offset. As medidas são inventadas, não são do KJ264.
 * Usado nos testes da cinemática e no visualizador 3D de teste (`:feature:robot3d`).
 */
object RoboTeste {
    val eixos = listOf(
        RobotModel.AssembledAxis(1, "coluna", Vec3(0.0, 0.0, 0.0), Vec3.Z, -120.0, 120.0),
        RobotModel.AssembledAxis(2, "braco", Vec3(150.0, 0.0, 500.0), Vec3.Y, -80.0, 130.0),
        RobotModel.AssembledAxis(3, "antebraco", Vec3(150.0, 0.0, 1400.0), Vec3.Y, -65.0, 90.0),
        RobotModel.AssembledAxis(4, "punho1", Vec3(400.0, 0.0, 1550.0), Vec3.X, -720.0, 720.0),
        RobotModel.AssembledAxis(5, "punho2", Vec3(1300.0, 40.0, 1550.0), Vec3(1.0, 0.0, -1.0), -720.0, 720.0),
        RobotModel.AssembledAxis(6, "punho3", Vec3(1400.0, 40.0, 1450.0), Vec3.X, -410.0, 410.0),
    )
    val flange = Transform.fromAxis(Vec3(1480.0, 40.0, 1450.0), Vec3.X, Vec3.Z)

    fun modelo(tool: Transform = Transform.IDENTITY) =
        RobotModel.assembled("teste", "base", eixos, flange, tool)
}
