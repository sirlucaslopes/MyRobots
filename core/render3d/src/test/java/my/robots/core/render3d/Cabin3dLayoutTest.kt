package my.robots.core.render3d

import my.robots.core.kinematics.RoboTeste
import my.robots.core.kinematics.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Cabin3dLayoutTest {

    /** 2 linhas × 2 colunas com o transportador entre as linhas (posição 1), fluxo para a direita. */
    private val cabine = Cabin3dLayout(
        rows = 2, cols = 2,
        placed = mapOf(10 to (0 to 0), 11 to (0 to 1), 12 to (1 to 0), 13 to (1 to 1)),
        bands = listOf(1 to 1),
    )

    @Test
    fun vagas_em_volta_do_zero() {
        val a = cabine.cellCenter(0, 0)
        val b = cabine.cellCenter(1, 1)
        assertEquals(-Cabin3dLayout.COL_PITCH_MM / 2, a.x, 1e-9)
        assertEquals(Cabin3dLayout.ROW_PITCH_MM / 2, a.y, 1e-9)
        assertEquals(Cabin3dLayout.COL_PITCH_MM / 2, b.x, 1e-9)
        assertEquals(-Cabin3dLayout.ROW_PITCH_MM / 2, b.y, 1e-9)
        // o transportador entre as linhas fica no meio
        assertEquals(0.0, cabine.bandY(1), 1e-9)
    }

    @Test
    fun robos_olham_para_o_transportador() {
        assertEquals(-Vec3.Y, cabine.facing(0)) // linha de cima olha para baixo (−Y)
        assertEquals(Vec3.Y, cabine.facing(1))
        // braço em +X no arquivo, linha de cima: gira −90° para olhar −Y
        val p = cabine.placement(10, Vec3.ZERO, Vec3.X)!!
        val tip = p.apply(Vec3(1000.0, 0.0, 0.0)) - cabine.cellCenter(0, 0)
        assertEquals(0.0, tip.x, 1e-9); assertEquals(-1000.0, tip.y, 1e-9)
        // braço em +Y no arquivo (como o KJ264), linha de baixo: já olha para +Y
        val q = cabine.placement(12, Vec3.ZERO, Vec3.Y)!!
        val tip2 = q.apply(Vec3(0.0, 1000.0, 0.0)) - cabine.cellCenter(1, 0)
        assertEquals(0.0, tip2.x, 1e-9); assertEquals(1000.0, tip2.y, 1e-9)
        assertNull(cabine.placement(99, Vec3.ZERO, Vec3.X))
    }

    @Test
    fun ancora_do_arquivo_vai_para_o_meio_da_vaga() {
        val anchor = Vec3(50.0, -20.0, -12.0)
        val p = cabine.placement(13, anchor, Vec3.X)!!
        val c = cabine.cellCenter(1, 1)
        val got = p.apply(anchor)
        assertEquals(c.x, got.x, 1e-9); assertEquals(c.y, got.y, 1e-9); assertEquals(0.0, got.z, 1e-9)
    }

    @Test
    fun toque_acha_o_robo() {
        val c = cabine.cellCenter(1, 1)
        // de frente, na altura de 1 m, mirando o meio da vaga
        val origin = Vec3(c.x, c.y - 8000, 1000.0)
        assertEquals(13, cabine.pick(origin, Vec3(0.0, 1.0, 0.0)))
        // de cima
        assertEquals(10, cabine.pick(cabine.cellCenter(0, 0) + Vec3(0.0, 0.0, 9000.0), Vec3(0.0, 0.0, -1.0)))
        // no vazio
        assertNull(cabine.pick(Vec3(0.0, -8000.0, 1000.0), Vec3(0.0, 1.0, 0.0).let { Vec3(1.0, 0.2, 0.0).normalized() }))
    }

    @Test
    fun braco_do_robo_de_teste_aponta_para_x() {
        val d = Cabin3dLayout.armDirection(RoboTeste.modelo(), Vec3.ZERO)
        assertEquals(1.0, d.x, 0.01)
        assertEquals(Vec3.X, Cabin3dLayout.armDirection(null, Vec3.ZERO))
    }

    @Test
    fun desenho_da_cabine_e_glb() {
        val glb = SceneModels.cabinGlb(cabine, mapOf(10 to 0x6DD58C, 11 to 0x8E8E96), selected = 10)
        assertTrue(GlbBuilder.isGlb(glb))
        assertNotNull(SceneModels.cabinGlb(Cabin3dLayout(1, 1, emptyMap(), emptyList()), emptyMap(), null))
    }

    @Test
    fun projecao_volta_o_pixel_do_raio() {
        val cam = OrbitCamera(target = Vec3(1.0, 2.0, 0.5), distance = 6.0, yawDeg = -30.0, pitchDeg = 25.0)
        val (o, d) = cam.ray(300f, 200f, 1000, 800)
        val p = o + d * 4.0
        val (x, y) = cam.project(p, 1000, 800)!!
        assertEquals(300f, x, 0.01f); assertEquals(200f, y, 0.01f)
        // atrás da câmera: nada
        assertNull(cam.project(o - d * 2.0, 1000, 800))
    }
}
