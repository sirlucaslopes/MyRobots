package my.robots.feature.robot3d

import my.robots.core.kinematics.RoboTeste
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Robot3dTest {

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /** Lê o JSON de dentro de um .glb. */
    private fun glbJson(bytes: ByteArray): String {
        val b = le(bytes)
        val len = b.getInt(12)
        return String(bytes, 20, len, Charsets.UTF_8)
    }

    @Test
    fun glb_tem_cabecalho_e_blocos_alinhados() {
        val g = GlbBuilder()
        val m = g.addMaterial(GlbBuilder.Material("x", 1f, 0f, 0f))
        g.addNode("peca", listOf(MeshData().box(Vec3.ZERO, Vec3(1000.0, 1000.0, 1000.0)) to m))
        val bytes = g.build()
        val b = le(bytes)
        assertTrue(GlbBuilder.isGlb(bytes))
        assertEquals(2, b.getInt(4))
        assertEquals(bytes.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals(0, jsonLen % 4)
        val binHeader = 20 + jsonLen
        val binLen = b.getInt(binHeader)
        assertEquals(0, binLen % 4)
        assertEquals(0x004E4942, b.getInt(binHeader + 4))
        assertEquals(bytes.size, binHeader + 8 + binLen)
        // caixa: 6 faces × 4 vértices × (posição + normal) × 4 bytes + 36 índices × 4 bytes
        assertEquals(24 * 3 * 4 * 2 + 36 * 4, binLen)
    }

    @Test
    fun glb_guarda_nome_minimo_e_maximo_em_metros() {
        val g = GlbBuilder()
        val m = g.addMaterial(GlbBuilder.Material("x", 1f, 0f, 0f))
        g.addNode("coluna \"A\"", listOf(MeshData().box(Vec3(-500.0, 0.0, 0.0), Vec3(500.0, 200.0, 1500.0)) to m))
        val json = glbJson(g.build())
        assertTrue(json.contains("\"name\":\"coluna \\\"A\\\"\""))
        assertTrue(json, json.contains("\"min\":[-0.500000,0.000000,0.000000]"))
        assertTrue(json, json.contains("\"max\":[0.500000,0.200000,1.500000]"))
        assertFalse(json.contains("KHR_materials_unlit"))
    }

    @Test
    fun cenario_usa_material_sem_luz() {
        val json = glbJson(SceneModels.sceneryGlb())
        assertTrue(json.contains("\"extensionsUsed\":[\"KHR_materials_unlit\"]"))
        assertTrue(json.contains("\"name\":\"${SceneModels.SCENERY_NODE}\""))
    }

    @Test
    fun robo_de_teste_tem_um_no_por_peca() {
        val model = RoboTeste.modelo()
        val json = glbJson(SceneModels.testRobotGlb(model.basePart, RoboTeste.eixos, RoboTeste.flange))
        for (part in listOf(model.basePart) + model.joints.map { it.childPart }) {
            assertTrue("falta $part", json.contains("\"name\":\"$part\""))
        }
    }

    @Test
    fun matriz_do_filament_e_coluna_a_coluna_em_metros() {
        val t = Transform(Transform.rotZ(Math.PI / 2).r, Vec3(1000.0, 2000.0, 3000.0))
        val m = SceneModels.toFilamentMatrix(t)
        // coluna 0 = onde o X vai parar (Y); coluna 1 = onde o Y vai parar (−X)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f), m.copyOfRange(0, 4), 1e-6f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, 0f), m.copyOfRange(4, 8), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f), m.copyOfRange(8, 12), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 1f), m.copyOfRange(12, 16), 1e-6f)
    }

    @Test
    fun gltf_y_para_cima_vira_z_para_cima() {
        val up = SceneModels.gltfToZUp(Vec3(0.0, 1.0, 0.0))
        assertEquals(0.0, up.x, 1e-12); assertEquals(0.0, up.y, 1e-12); assertEquals(1.0, up.z, 1e-12)
        // a frente do glTF (+Z) fica para −Y
        val front = SceneModels.gltfToZUp(Vec3(0.0, 0.0, 1.0))
        assertEquals(-1.0, front.y, 1e-12)
    }

    @Test
    fun vistas_prontas() {
        val c = OrbitCamera(target = Vec3.ZERO, distance = 10.0)
        c.apply(OrbitCamera.Preset.FRENTE)
        assertEquals(10.0, c.eye.x, 1e-9); assertEquals(0.0, c.eye.z, 1e-9)
        c.apply(OrbitCamera.Preset.TOPO)
        assertEquals(10.0, c.eye.z, 1e-3)
        c.apply(OrbitCamera.Preset.ISO)
        // isométrica: o olho a igual distância nos três eixos (X+, Y−, Z+)
        assertEquals(c.eye.x, -c.eye.y, 1e-6)
        assertEquals(c.eye.x, c.eye.z, 1e-3)
    }

    @Test
    fun gestos_respeitam_limites() {
        val c = OrbitCamera(target = Vec3.ZERO, distance = 10.0, pitchDeg = 80.0)
        c.orbit(0f, 1000f)
        assertEquals(OrbitCamera.MAX_PITCH, c.pitchDeg, 0.0)
        c.zoom(1e6f)
        assertEquals(OrbitCamera.MIN_DISTANCE, c.distance, 0.0)
        c.orbit(1200f, 0f) // 360°: volta ao mesmo giro
        assertTrue(c.yawDeg > -180 && c.yawDeg <= 180)
    }

    @Test
    fun raio_do_toque_sai_do_olho() {
        val c = OrbitCamera(target = Vec3.ZERO, distance = 10.0)
        c.apply(OrbitCamera.Preset.FRENTE) // olho em +X, olhando para −X
        val (o, d) = c.ray(500f, 500f, 1000, 1000)
        assertEquals(10.0, o.x, 1e-9)
        assertEquals(-1.0, d.x, 1e-9) // o meio da tela vai direto no alvo
        // canto de cima à direita: sobe (Z+) e vai para a direita da tela (Y+ olhando do +X)
        val (_, corner) = c.ray(1000f, 0f, 1000, 1000)
        assertTrue(corner.z > 0 && corner.y > 0)
        val half = Math.tan(Math.toRadians(c.fovDeg / 2))
        assertEquals(half, corner.z / -corner.x, 1e-9)
    }

    @Test
    fun pasta_do_robo_salvo() {
        assertEquals("kj264_cabine_2", AssemblerViewModel.slug("KJ264 · Cabine 2"))
        assertEquals("robo_acao", AssemblerViewModel.slug("Robô ação"))
        assertEquals("robo", AssemblerViewModel.slug("///"))
    }

    @Test
    fun arrastar_move_o_alvo_no_plano_da_tela() {
        val c = OrbitCamera(target = Vec3.ZERO, distance = 10.0)
        c.apply(OrbitCamera.Preset.FRENTE) // olhando do +X: a direita da tela é +Y
        c.pan(100f, 0f, 1000)
        assertTrue(c.target.y < 0) // a cena vai para a direita com o dedo: o alvo vai para −Y
        assertEquals(0.0, c.target.z, 1e-9)
        c.pan(0f, 100f, 1000)
        assertTrue(c.target.z > 0) // dedo para baixo: a cena desce, o alvo sobe
    }
}
