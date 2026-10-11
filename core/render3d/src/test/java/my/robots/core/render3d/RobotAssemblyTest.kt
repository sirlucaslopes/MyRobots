package my.robots.core.render3d

import my.robots.core.kinematics.PartRole
import my.robots.core.kinematics.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotAssemblyTest {

    private val kjParts = listOf("KJ264J_B001_J0") + (1..6).map { "KJ264_B001_J$it" }

    @Test
    fun sugere_base_e_eixos_pelos_nomes() {
        val roles = RobotAssembly.suggestRoles(kjParts)
        assertEquals(PartAssignment(PartRole.BASE), roles["KJ264J_B001_J0"])
        for (n in 1..6) assertEquals(PartAssignment(PartRole.AXIS, n), roles["KJ264_B001_J$n"])
        val other = RobotAssembly.suggestRoles(listOf("Base", "Link 1", "eixo2", "Pistola", "cabo"))
        assertEquals(PartRole.BASE, other["Base"]!!.role)
        assertEquals(PartAssignment(PartRole.AXIS, 1), other["Link 1"])
        assertEquals(PartAssignment(PartRole.AXIS, 2), other["eixo2"])
        assertEquals(PartRole.TOOL, other["Pistola"]!!.role)
        assertNull(other["cabo"])
    }

    @Test
    fun aponta_o_que_falta() {
        val a = RobotAssembly("r", "r.glb", listOf("a", "b", "c"))
        assertTrue(a.problems().any { "base" in it })
        val b = a.withRole("a", PartAssignment(PartRole.BASE))
            .withRole("b", PartAssignment(PartRole.AXIS, 1))
            .withRole("c", PartAssignment(PartRole.AXIS, 3))
        assertTrue(b.problems().toString(), b.problems().any { "Falta o eixo 2" in it })
        val c = b.withRole("c", PartAssignment(PartRole.AXIS, 2))
        assertTrue(c.problems().toString(), c.problems().isEmpty())
        assertTrue(RobotAssembly("r", "r.glb", listOf("so")).problems().any { "uma peça só" in it })
    }

    /** Base, coluna (eixo 1 vertical no zero) e braço (eixo 2 horizontal em Y a 500 mm). */
    private fun twoAxes() = RobotAssembly("teste", "t.glb", listOf("base", "coluna", "braco", "pistola"))
        .withRole("base", PartAssignment(PartRole.BASE))
        .withRole("coluna", PartAssignment(PartRole.AXIS, 1))
        .withRole("braco", PartAssignment(PartRole.AXIS, 2))
        .withRole("pistola", PartAssignment(PartRole.TOOL))
        .withAxis(1, AxisDef(Vec3.ZERO, Vec3.Z))
        .withAxis(2, AxisDef(Vec3(0.0, 0.0, 500.0), Vec3.Y, -90.0, 90.0))

    @Test
    fun monta_e_move_as_pecas() {
        val a = twoAxes()
        assertEquals(2, a.definedAxisCount)
        val model = a.model()!!
        assertEquals(2, model.axisCount)
        // na pose do arquivo, nada sai do lugar
        a.poses().values.forEach { assertTrue(it.isClose(my.robots.core.kinematics.Transform.IDENTITY, 1e-9)) }
        // eixo 1 a 90°: a coluna, o braço e a pistola giram em volta de Z
        val p = a.poses(doubleArrayOf(90.0, 0.0))
        val moved = p.getValue("braco").apply(Vec3(1000.0, 0.0, 500.0))
        assertEquals(0.0, moved.x, 1e-9); assertEquals(1000.0, moved.y, 1e-9); assertEquals(500.0, moved.z, 1e-9)
        assertTrue(p.getValue("pistola").isClose(p.getValue("braco"), 1e-9))
        assertTrue(p.getValue("base").isClose(my.robots.core.kinematics.Transform.IDENTITY, 1e-9))
    }

    @Test
    fun eixo_sem_marca_vai_junto_com_o_anterior() {
        val a = twoAxes().withAxis(2, null)
        assertEquals(1, a.definedAxisCount)
        val p = a.poses(doubleArrayOf(45.0))
        assertTrue(p.getValue("braco").isClose(p.getValue("coluna"), 1e-9))
    }

    @Test
    fun base_fora_do_zero() {
        val a = twoAxes().copy(baseX = 1000.0, baseRotDeg = 90.0)
        val p = a.poses(doubleArrayOf(0.0, 0.0))
        val origin = p.getValue("base").apply(Vec3(100.0, 0.0, 0.0))
        assertEquals(1000.0, origin.x, 1e-9); assertEquals(100.0, origin.y, 1e-9)
    }

    @Test
    fun programa_move_os_eixos_juntos_e_suave() {
        val a = listOf(0.0, 0.0)
        val b = listOf(90.0, -30.0)
        // o eixo que mais anda (90°) define: 1,5 s a 60 °/s
        assertEquals(1.5, TestProgram.durationS(a, b, 60.0), 1e-12)
        assertEquals(0.05, TestProgram.durationS(a, a, 60.0), 1e-12)
        assertEquals(a, TestProgram.interpolate(a, b, 0.0))
        assertEquals(b, TestProgram.interpolate(a, b, 1.0))
        val mid = TestProgram.interpolate(a, b, 0.5)
        assertEquals(45.0, mid[0], 1e-12); assertEquals(-15.0, mid[1], 1e-12)
        // curva em S: no começo anda menos que a reta
        assertTrue(TestProgram.interpolate(a, b, 0.1)[0] < 9.0)
    }

    @Test
    fun tool_e_zero_do_eixo() {
        val base = twoAxes()
        val flange = base.model()!!.tcpInWorld(doubleArrayOf(0.0, 0.0)).t
        // TOOL de 100 mm em Z do flange: o TCP anda 100 mm no Z do flange
        val withTool = base.copy(tool = my.robots.core.kinematics.KawasakiPose(0.0, 0.0, 100.0, 0.0, 0.0, 0.0))
        val z = withTool.model()!!.tcpInWorld(doubleArrayOf(0.0, 0.0))
        assertEquals(100.0, (z.t - flange).length(), 1e-9)
        assertEquals(100.0, (z.t - flange).dot(z.zAxis), 1e-9)
        // arquivo veio com o eixo 1 em 30°: no zero do robô a coluna gira −30° a partir do arquivo
        val zero = base.withAxis(1, base.axes.getValue(1).copy(zeroDeg = 30.0))
        val p = zero.poses(doubleArrayOf(0.0, 0.0)).getValue("coluna").apply(Vec3(1000.0, 0.0, 0.0))
        assertEquals(1000.0 * kotlin.math.cos(Math.toRadians(-30.0)), p.x, 1e-9)
        assertEquals(1000.0 * kotlin.math.sin(Math.toRadians(-30.0)), p.y, 1e-9)
        // e em 30° volta à pose do arquivo
        assertTrue(zero.poses(doubleArrayOf(30.0, 0.0)).getValue("coluna").isClose(my.robots.core.kinematics.Transform.IDENTITY, 1e-9))
        assertNotNull(zero.robotFrameInWorld())
    }

    @Test
    fun peca_fora_da_posicao_ganha_ajuste() {
        // o braço veio 100 mm para cima no arquivo: o ajuste desce 100 mm
        val down = my.robots.core.kinematics.Transform.translation(Vec3(0.0, 0.0, -100.0))
        val a = twoAxes().copy(offsets = mapOf("braco" to down), locked = setOf("braco"))
        assertEquals(Vec3(0.0, 0.0, 500.0), a.toAssembled("braco", Vec3(0.0, 0.0, 600.0)))
        assertEquals(Vec3(0.0, 0.0, 600.0), a.toAssembled("coluna", Vec3(0.0, 0.0, 600.0)))
        // desenho = pose montada · ajuste
        val p = a.displayPoses(doubleArrayOf(90.0, 0.0))
        val q = a.poses(doubleArrayOf(90.0, 0.0))
        assertTrue(p.getValue("braco").isClose(q.getValue("braco") * down, 1e-9))
        assertTrue(p.getValue("coluna").isClose(q.getValue("coluna"), 1e-9))
        // JSON guarda o ajuste e a peça fixada
        val back = RobotAssembly.fromJson(a.toJson())
        assertTrue(back.offsets.getValue("braco").isClose(down, 1e-9))
        assertEquals(setOf("braco"), back.locked)
    }

    /** Como o KJ264: JT1 vertical no zero, JT2 horizontal (em X) a 140 mm à frente e 900 mm do piso. */
    private fun kjLike() = RobotAssembly("kj", "kj.glb", listOf("j0", "j1", "j2"))
        .withRole("j0", PartAssignment(PartRole.BASE))
        .withRole("j1", PartAssignment(PartRole.AXIS, 1))
        .withRole("j2", PartAssignment(PartRole.AXIS, 2))
        .withAxis(1, AxisDef(Vec3(0.0, 0.0, 700.0), Vec3.Z))
        .withAxis(2, AxisDef(Vec3(0.0, 140.0, 900.0), Vec3.X))

    @Test
    fun origem_do_robo_na_altura_do_eixo_2_como_o_k_roset() {
        val nb = kjLike().nullBase()!!
        assertEquals(Vec3(0.0, 0.0, 900.0), nb.t)
        assertEquals(Vec3.Z, nb.zAxis)
        assertEquals(Vec3.X, nb.xAxis) // X do robô = X do arquivo (frente +X)
        // o JT1 com o sentido invertido não vira o robô de cabeça para baixo
        val inv = kjLike().let { it.withAxis(1, it.axes.getValue(1).copy(direction = -Vec3.Z)) }
        assertEquals(Vec3.Z, inv.nullBase()!!.zAxis)
        // no piso e personalizada
        assertEquals(Vec3(0.0, 0.0, 0.0), kjLike().copy(origin = RobotOrigin.PISO, baseFloorZ = 0.0).nullBase()!!.t)
        assertEquals(Vec3(1.0, 2.0, 3.0), kjLike().copy(origin = RobotOrigin.PERSONALIZADA, originCustom = Vec3(1.0, 2.0, 3.0)).nullBase()!!.t)
    }

    @Test
    fun base_do_controlador_desloca_o_where() {
        val a = kjLike()
        val tcp0 = a.model()!!.tcpPose(doubleArrayOf(0.0, 0.0))
        // BASE 100 mm em X: o mesmo ponto físico fica 100 mm a menos em X no WHERE
        val shifted = a.copy(baseTrans = my.robots.core.kinematics.KawasakiPose(100.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        val tcp1 = shifted.model()!!.tcpPose(doubleArrayOf(0.0, 0.0))
        assertEquals(tcp0.x - 100.0, tcp1.x, 1e-9)
        assertEquals(tcp0.z, tcp1.z, 1e-9)
        // as setas da base deslocada ficam 100 mm em X do BASE 0
        assertEquals(Vec3(100.0, 0.0, 900.0), shifted.baseFrameInWorld()!!.t)
        assertNull(a.baseFrameInWorld())
        // JSON guarda origem, piso e BASE
        val back = RobotAssembly.fromJson(shifted.copy(origin = RobotOrigin.PISO, baseFloorZ = -12.0).toJson())
        assertEquals(RobotOrigin.PISO, back.origin)
        assertEquals(-12.0, back.baseFloorZ, 0.0)
        assertEquals(shifted.baseTrans, back.baseTrans)
    }

    @Test
    fun cores() {
        assertEquals("#F26B1D", RobotAssembly.colorHex(0xF26B1D))
        assertEquals(0x1F5AA6, RobotAssembly.parseColor("#1f5aa6"))
        assertEquals(0x1F5AA6, RobotAssembly.parseColor("1F5AA6"))
        assertNull(RobotAssembly.parseColor("#12345"))
        assertNull(RobotAssembly.parseColor("#GGGGGG"))
    }

    @Test
    fun json_ida_e_volta() {
        val a = twoAxes().copy(
            flange = AxisDef(Vec3(800.0, 0.0, 500.0), Vec3.X, kind = AxisGuess.Kind.PLANA, radiusMm = 40.0),
            baseZ = 12.5, front = RobotFront.PY,
            colors = mapOf("coluna" to 0xF26B1D, "base" to 0x151618),
            program = TestProgram(listOf(TestPoint("P1", listOf(0.0, 10.0)), TestPoint("P2", listOf(-45.5, 30.0), MotionType.LMOVE)), 90.0, 1.0, linearSpeedMmS = 400.0),
            tool = my.robots.core.kinematics.KawasakiPose(10.0, -5.0, 250.0, 0.0, 30.0, 90.0),
        ).withAxis(2, twoAxes().axes.getValue(2).copy(zeroDeg = -90.0))
        val back = RobotAssembly.fromJson(a.toJson())
        assertEquals(a, back)
        assertNotNull(back.model())
    }
}
