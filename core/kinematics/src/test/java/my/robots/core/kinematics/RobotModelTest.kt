package my.robots.core.kinematics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Robô de teste com 6 eixos e punho com offset. As medidas são inventadas, não são do KJ264. */
internal object RoboTeste {
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

class RobotModelTest {

    @Test
    fun braco_planar_de_dois_eixos_bate_com_a_conta() {
        // Dois eixos em Z: elos de 1000 e 500 mm no plano XY.
        val m = RobotModel.assembled(
            "planar", "base",
            listOf(
                RobotModel.AssembledAxis(1, "elo1", Vec3.ZERO, Vec3.Z, -180.0, 180.0),
                RobotModel.AssembledAxis(2, "elo2", Vec3(1000.0, 0.0, 0.0), Vec3.Z, -180.0, 180.0),
            ),
            flange = Transform.translation(Vec3(1500.0, 0.0, 0.0)),
            robotFrame = Transform.IDENTITY,
        )
        val p = m.tcp(doubleArrayOf(90.0, -90.0)).t
        assertEquals(500.0, p.x, 1e-9)
        assertEquals(1000.0, p.y, 1e-9)
        assertEquals(0.0, p.z, 1e-9)
    }

    @Test
    fun na_posicao_zero_as_pecas_ficam_onde_vieram_do_arquivo() {
        val m = RoboTeste.modelo()
        val pecas = m.partTransforms(DoubleArray(6))
        for (t in pecas.values) assertTrue(t.isClose(Transform.IDENTITY))
        assertEquals(listOf("base", "coluna", "braco", "antebraco", "punho1", "punho2", "punho3"), pecas.keys.toList())
    }

    @Test
    fun arquivo_fora_do_zero_usa_o_angulo_informado() {
        // Mesmo robô, mas o arquivo veio com o eixo 2 em 30°.
        val eixos = RoboTeste.eixos.map { if (it.number == 2) it.copy(angleInFileDeg = 30.0) else it }
        val m = RobotModel.assembled("teste", "base", eixos, RoboTeste.flange)
        // Com os eixos nos ângulos do arquivo, tudo fica onde veio.
        val pecas = m.partTransforms(doubleArrayOf(0.0, 30.0, 0.0, 0.0, 0.0, 0.0))
        for (t in pecas.values) assertTrue(t.isClose(Transform.IDENTITY))
    }

    @Test
    fun pecas_soltas_encaixam_igual_as_montadas() {
        val montado = RoboTeste.modelo()
        // Cada peça veio do CAD num lugar diferente: arquivo = P⁻¹ · montagem.
        val deslocadas = mapOf(
            "base" to Transform.translation(Vec3(-300.0, 50.0, 0.0)),
            "coluna" to Transform(Transform.rotation(Vec3(1.0, 2.0, 3.0), 0.7).r, Vec3(10.0, 900.0, -40.0)),
            "braco" to Transform.translation(Vec3(2000.0, 0.0, 0.0)),
            "antebraco" to Transform(Transform.rotation(Vec3.Y, 1.2).r, Vec3(0.0, -500.0, 300.0)),
            "punho1" to Transform.rotation(Vec3.Z, -2.0),
            "punho2" to Transform.translation(Vec3(0.0, 0.0, -1000.0)),
            "punho3" to Transform(Transform.rotation(Vec3(1.0, 1.0, 0.0), 2.5).r, Vec3(77.0, 88.0, 99.0)),
        )
        fun noArquivo(peca: String, t: Transform) = deslocadas.getValue(peca).inverse() * t
        val juntas = montado.joints.map {
            it.copy(
                parentFrame = noArquivo(it.parentPart, it.parentFrame),
                childFrame = noArquivo(it.childPart, it.childFrame),
            )
        }
        val solto = montado.copy(
            joints = juntas,
            flange = noArquivo("punho3", montado.flange),
            robotFrame = noArquivo("base", montado.robotFrame),
        )
        val q = doubleArrayOf(25.0, -40.0, 60.0, 100.0, -200.0, 33.0)
        assertTrue(montado.tcp(q).isClose(solto.tcp(q), mm = 1e-6, rad = 1e-9))
    }

    @Test
    fun inverter_o_sentido_troca_o_sinal_do_angulo() {
        val m = RoboTeste.modelo()
        val juntas = m.joints.toMutableList()
        juntas[1] = juntas[1].inverted()
        val invertido = m.copy(joints = juntas)
        val q = doubleArrayOf(10.0, 45.0, -20.0, 0.0, 30.0, 0.0)
        val qInv = q.copyOf().also { it[1] = -45.0 }
        assertTrue(m.tcp(q).isClose(invertido.tcp(qInv)))
        assertEquals(-130.0, juntas[1].minDeg, 0.0)
        assertEquals(80.0, juntas[1].maxDeg, 0.0)
    }

    @Test
    fun eixos_fora_de_ordem_sao_recusados() {
        val m = RoboTeste.modelo()
        try {
            m.copy(joints = m.joints.reversed())
            fail("devia recusar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fora de ordem"))
        }
    }

    @Test
    fun limites() {
        val m = RoboTeste.modelo()
        assertFalse(m.withinLimits(doubleArrayOf(130.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val c = m.clamp(doubleArrayOf(130.0, -90.0, 0.0, 0.0, 0.0, 500.0))
        assertEquals(120.0, c[0], 0.0)
        assertEquals(-80.0, c[1], 0.0)
        assertEquals(410.0, c[5], 0.0)
    }
}
