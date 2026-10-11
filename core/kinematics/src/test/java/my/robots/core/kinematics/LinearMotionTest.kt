package my.robots.core.kinematics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearMotionTest {

    private val modelo = RoboTeste.modelo(tool = KawasakiPose(0.0, 0.0, 250.0, 0.0, 0.0, 0.0).toTransform())

    @Test
    fun meio_do_caminho_fica_na_reta_e_com_meio_giro() {
        val a = Transform.translation(Vec3(0.0, 0.0, 0.0))
        val b = Transform(Transform.rotZ(Math.PI / 2).r, Vec3(100.0, 200.0, 0.0))
        val m = LinearMotion.interpolate(a, b, 0.5)
        assertEquals(50.0, m.t.x, 1e-9); assertEquals(100.0, m.t.y, 1e-9)
        assertTrue(m.isClose(Transform(Transform.rotZ(Math.PI / 4).r, m.t), 1e-9, 1e-9))
        assertTrue(LinearMotion.interpolate(a, b, 1.0).isClose(b, 1e-9, 1e-9))
    }

    @Test
    fun tcp_anda_em_linha_reta() {
        val q0 = doubleArrayOf(10.0, 20.0, -10.0, 0.0, -30.0, 0.0)
        val q1 = doubleArrayOf(-20.0, 35.0, 5.0, 10.0, -45.0, 15.0)
        val a = modelo.tcp(q0)
        val b = modelo.tcp(q1)
        val path = LinearMotion.plan(modelo, q0, b)
        assertTrue(path.problem, path.ok)
        assertEquals((b.t - a.t).length(), path.lengthMm, 1e-9)
        // cada pedaço do caminho está na reta de a até b (o JMOVE sairia dela)
        val dir = (b.t - a.t).normalized()
        for (q in path.joints) {
            val p = modelo.tcp(q).t - a.t
            assertEquals("fora da reta", 0.0, (p - dir * p.dot(dir)).length(), 0.1)
        }
        // chega no alvo
        assertTrue(modelo.tcp(path.joints.last()).isClose(b, 0.1, Math.toRadians(0.05)))
        // e o meio por amostra também
        val mid = modelo.tcp(LinearMotion.sample(path, 0.5)).t - a.t
        assertEquals(0.0, (mid - dir * mid.dot(dir)).length(), 0.5)
    }

    @Test
    fun fora_do_alcance_avisa() {
        val q0 = DoubleArray(6)
        val far = Transform(modelo.tcp(q0).r, Vec3(6000.0, 0.0, 1000.0))
        val path = LinearMotion.plan(modelo, q0, far)
        assertFalse(path.ok)
        assertTrue(path.problem!!.contains("alcance"))
    }
}
