package my.robots.core.kinematics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JogTest {

    private val modelo = RoboTeste.modelo(tool = KawasakiPose(0.0, 0.0, 250.0, 0.0, 0.0, 0.0).toTransform())
    private val q = doubleArrayOf(10.0, 20.0, -10.0, 0.0, -30.0, 0.0)

    @Test
    fun base_anda_no_x_do_robo_sem_girar() {
        val before = modelo.tcp(q)
        val after = modelo.tcp(Jog.step(modelo, q, JogFrame.BASE, 0, 10.0)!!)
        assertEquals(10.0, after.t.x - before.t.x, 0.1)
        assertEquals(0.0, after.t.y - before.t.y, 0.1)
        assertEquals(0.0, after.t.z - before.t.z, 0.1)
        assertTrue(Math.toDegrees(before.rotationErrorTo(after).length()) < 0.05)
    }

    @Test
    fun tool_anda_no_z_da_ferramenta() {
        val before = modelo.tcp(q)
        val after = modelo.tcp(Jog.step(modelo, q, JogFrame.TOOL, 2, 10.0)!!)
        val moved = after.t - before.t
        assertEquals(10.0, moved.dot(before.zAxis), 0.1)
        assertEquals(10.0, moved.length(), 0.1)
    }

    @Test
    fun giro_em_volta_do_tcp_nao_tira_o_tcp_do_lugar() {
        val before = modelo.tcp(q)
        val after = modelo.tcp(Jog.step(modelo, q, JogFrame.BASE, 5, 5.0)!!)
        assertEquals(0.0, (after.t - before.t).length(), 0.1)
        assertEquals(5.0, Math.toDegrees(before.rotationErrorTo(after).length()), 0.05)
    }

    @Test
    fun mundo_com_a_base_girada() {
        // robô girado 90° em Z no espaço: o X do mundo é o −Y do robô
        val girado = modelo.copy(placement = Transform.rotZ(Math.PI / 2))
        val t = Jog.target(girado, q, JogFrame.WORLD, 0, 10.0)
        val d = t.t - girado.tcp(q).t
        assertEquals(0.0, d.x, 1e-9); assertEquals(-10.0, d.y, 1e-9)
    }

    @Test
    fun fora_do_alcance_nao_anda() {
        assertNotNull(Jog.step(modelo, q, JogFrame.BASE, 0, 1.0))
        assertNull(Jog.step(modelo, q, JogFrame.BASE, 0, 5000.0))
    }
}
