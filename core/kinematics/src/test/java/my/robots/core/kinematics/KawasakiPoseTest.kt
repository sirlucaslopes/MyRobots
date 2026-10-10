package my.robots.core.kinematics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KawasakiPoseTest {

    @Test
    fun ida_e_volta_mantem_os_valores() {
        val casos = listOf(
            KawasakiPose(1200.0, -350.5, 980.25, 30.0, 45.0, 60.0),
            KawasakiPose(0.0, 0.0, 0.0, -170.0, 120.0, 10.0),
            KawasakiPose(10.0, 20.0, 30.0, 90.0, 5.0, -90.0),
            KawasakiPose(-800.0, 1500.0, 200.0, 0.0, 179.0, 135.0),
        )
        for (p in casos) {
            val volta = KawasakiPose.fromTransform(p.toTransform())
            assertEquals(p.x, volta.x, 1e-9)
            assertEquals(p.y, volta.y, 1e-9)
            assertEquals(p.z, volta.z, 1e-9)
            assertEquals(p.o, volta.o, 1e-7)
            assertEquals(p.a, volta.a, 1e-7)
            assertEquals(p.t, volta.t, 1e-7)
        }
    }

    @Test
    fun o_gira_em_volta_de_z() {
        val tr = KawasakiPose(0.0, 0.0, 0.0, 90.0, 0.0, 0.0).toTransform()
        val x = tr.xAxis
        assertEquals(0.0, x.x, 1e-12)
        assertEquals(1.0, x.y, 1e-12)
        assertEquals(0.0, x.z, 1e-12)
    }

    @Test
    fun a_gira_em_volta_de_y_depois_de_o() {
        // O = 0, A = 90: o Z da ferramenta passa a apontar para +X.
        val z = KawasakiPose(0.0, 0.0, 0.0, 0.0, 90.0, 0.0).toTransform().zAxis
        assertEquals(1.0, z.x, 1e-12)
        assertEquals(0.0, z.y, 1e-12)
        assertEquals(0.0, z.z, 1e-12)
    }

    @Test
    fun a_zero_junta_o_e_t_no_mesmo_giro() {
        val p = KawasakiPose.fromTransform(KawasakiPose(0.0, 0.0, 0.0, 30.0, 0.0, 20.0).toTransform())
        assertEquals(0.0, p.a, 1e-9)
        assertEquals(50.0, p.o + p.t, 1e-9)
    }

    @Test
    fun le_linha_de_trans() {
        val p = KawasakiPose.parse("a12 1200.5 -30 845.25 90 175.5 -12")!!
        assertEquals(1200.5, p.x, 0.0)
        assertEquals(-30.0, p.y, 0.0)
        assertEquals(-12.0, p.t, 0.0)
        assertNull(KawasakiPose.parse("a12 1 2 3"))
        assertTrue(p.format().startsWith("1200.500 -30.000 845.250"))
    }
}
