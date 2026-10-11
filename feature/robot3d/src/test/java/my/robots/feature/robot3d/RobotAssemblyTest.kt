package my.robots.feature.robot3d

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
    fun json_ida_e_volta() {
        val a = twoAxes().copy(
            flange = AxisDef(Vec3(800.0, 0.0, 500.0), Vec3.X, kind = AxisGuess.Kind.PLANA, radiusMm = 40.0),
            baseZ = 12.5, front = RobotFront.PY,
        )
        val back = RobotAssembly.fromJson(a.toJson())
        assertEquals(a, back)
        assertNotNull(back.model())
    }
}
