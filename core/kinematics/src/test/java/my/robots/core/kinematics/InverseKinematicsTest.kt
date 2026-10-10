package my.robots.core.kinematics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InverseKinematicsTest {

    private val modelo = RoboTeste.modelo(tool = KawasakiPose(0.0, 0.0, 250.0, 0.0, 0.0, 0.0).toTransform())
    private val ik = InverseKinematics(modelo)

    @Test
    fun acha_os_angulos_de_uma_pose_alcancavel() {
        val casos = listOf(
            doubleArrayOf(20.0, 10.0, 15.0, 30.0, -40.0, 50.0),
            doubleArrayOf(-60.0, 40.0, -20.0, -90.0, 60.0, -120.0),
            doubleArrayOf(5.0, -30.0, 50.0, 200.0, 100.0, 10.0),
        )
        for (q in casos) {
            val alvo = modelo.tcp(q)
            // Parte de perto (como acontece ao arrastar um ponto no 3D).
            val seed = DoubleArray(6) { q[it] + if (it % 2 == 0) 8.0 else -6.0 }
            val r = ik.solve(alvo, seed)
            assertTrue("não convergiu para ${q.toList()}: $r", r.success)
            assertTrue(modelo.tcp(r.deg).isClose(alvo, mm = 0.01, rad = Math.toRadians(0.001)))
            assertTrue(modelo.withinLimits(r.deg))
        }
    }

    @Test
    fun aceita_alvo_no_formato_da_kawasaki() {
        val q = doubleArrayOf(15.0, 20.0, 10.0, 45.0, -30.0, 60.0)
        val pose = modelo.tcpPose(q)
        val r = ik.solve(pose, DoubleArray(6) { q[it] + 5.0 })
        assertTrue(r.success)
        assertTrue(r.positionErrorMm <= 0.01)
    }

    @Test
    fun fica_perto_da_posicao_atual_nos_eixos_de_varias_voltas() {
        val q = doubleArrayOf(10.0, 20.0, 10.0, 380.0, -30.0, 40.0)
        val alvo = modelo.tcp(q)
        val r = ik.solve(alvo, DoubleArray(6) { q[it] + 3.0 })
        assertTrue(r.success)
        // O eixo 4 continua perto de 380°, não volta para 20°.
        assertTrue(abs(r.deg[3] - 380.0) < 20.0)
    }

    @Test
    fun alvo_fora_do_alcance_nao_tem_sucesso_e_respeita_limites() {
        val longe = Transform.translation(Vec3(10_000.0, 0.0, 0.0))
        val r = ik.solve(longe, DoubleArray(6))
        assertFalse(r.success)
        assertTrue(modelo.withinLimits(r.deg))
        assertTrue(r.positionErrorMm > 1000.0)
    }

    @Test
    fun sistema_linear() {
        val x = InverseKinematics.solveLinear(
            arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)),
            doubleArrayOf(3.0, 5.0),
        )
        assertEquals(0.8, x[0], 1e-12)
        assertEquals(1.4, x[1], 1e-12)
    }
}
