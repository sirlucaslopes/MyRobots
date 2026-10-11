package my.robots.core.render3d

import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbPartsTest {

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double) {
        assertEquals("x de $actual", expected.x, actual.x, tol)
        assertEquals("y de $actual", expected.y, actual.y, tol)
        assertEquals("z de $actual", expected.z, actual.z, tol)
    }

    /**
     * Robô de duas peças desenhado com Y para cima (como um .glb de verdade): uma caixa e um
     * cilindro de raio 50 mm em volta do Y do glTF, com centro em (200, 300, −100) mm.
     * No app (Z para cima) o cilindro fica em (200, 100, 300), em volta do Z.
     */
    private fun twoParts(axisGltf: Vec3 = Vec3.Y): GlbParts {
        val g = GlbBuilder()
        val m = g.addMaterial(GlbBuilder.Material("x", 1f, 1f, 1f))
        g.addNode("J0", listOf(MeshData().box(Vec3(-300.0, 0.0, -300.0), Vec3(300.0, 100.0, 300.0)) to m))
        g.addNode("J1", listOf(MeshData().cylinder(Transform.fromAxis(Vec3(200.0, 300.0, -100.0), axisGltf), 50.0, 80.0, 48) to m))
        return GlbReader.read(g.build())
    }

    @Test
    fun le_uma_peca_por_no_em_mm_e_z_para_cima() {
        val parts = twoParts()
        assertEquals(listOf("J0", "J1"), parts.parts.map { it.name })
        assertTrue(parts.warnings.toString(), parts.warnings.isEmpty())
        val (center, _) = parts.part("J1")!!.bounds()
        assertVec(Vec3(200.0, 100.0, 300.0), center, 1e-3)
    }

    @Test
    fun toque_na_lateral_acha_o_eixo_do_cilindro() {
        val j1 = twoParts().part("J1")!!
        val (tri, dist) = j1.raycast(Vec3(1000.0, 100.0, 300.0), Vec3(-1.0, 0.0, 0.0))!!
        assertEquals(750.0, dist, 1.0) // a lateral com 48 lados fica a ~50 mm do centro
        val axis = AxisFinder.fromFace(j1, j1.faceAround(tri))!!
        assertEquals(AxisGuess.Kind.REDONDA, axis.kind)
        assertVec(Vec3(200.0, 100.0, 300.0), axis.point, 1e-3)
        assertVec(Vec3.Z, axis.direction, 1e-9)
        assertEquals(50.0, axis.radiusMm, 1e-3)
        assertTrue(axis.errorMm < 1e-3)
    }

    @Test
    fun toque_na_tampa_acha_o_centro_e_a_normal() {
        val j1 = twoParts().part("J1")!!
        val (tri, _) = j1.raycast(Vec3(210.0, 120.0, 1000.0), Vec3(0.0, 0.0, -1.0))!!
        val face = j1.faceAround(tri)
        assertEquals(48, face.size) // a tampa inteira, sem a lateral
        val axis = AxisFinder.fromFace(j1, face)!!
        assertEquals(AxisGuess.Kind.PLANA, axis.kind)
        assertVec(Vec3(200.0, 100.0, 380.0), axis.point, 1e-3)
        assertVec(Vec3.Z, axis.direction, 1e-9)
        assertEquals(50.0, axis.radiusMm, 1e-3)
    }

    @Test
    fun eixo_inclinado() {
        val tilt = Vec3(1.0, 0.0, -1.0).normalized() // no glTF
        val j1 = twoParts(tilt).part("J1")!!
        val expected = SceneModels.gltfToZUp(tilt) // (0,707; 0,707; 0)
        // raio saindo do centro, perpendicular ao eixo: bate na lateral
        val side = expected.cross(Vec3.Z).normalized()
        val center = Vec3(200.0, 100.0, 300.0)
        val (tri, _) = j1.raycast(center + side * 500.0, -side)!!
        val axis = AxisFinder.fromFace(j1, j1.faceAround(tri))!!
        assertEquals(AxisGuess.Kind.REDONDA, axis.kind)
        assertEquals(1.0, kotlin.math.abs(axis.direction.dot(expected)), 1e-9)
        // o ponto achado fica na linha do eixo
        val v = axis.point - center
        assertEquals(0.0, (v - expected * v.dot(expected)).length(), 1e-3)
    }

    @Test
    fun face_plana_da_caixa_da_o_centro() {
        val j0 = twoParts().part("J0")!!
        // face de cima da caixa (Y = 100 no glTF → Z = 100 no app), um quadrado de 600 mm
        val (tri, _) = j0.raycast(Vec3(10.0, 10.0, 1000.0), Vec3(0.0, 0.0, -1.0))!!
        val axis = AxisFinder.fromFace(j0, j0.faceAround(tri))!!
        assertEquals(AxisGuess.Kind.PLANA, axis.kind)
        assertVec(Vec3(0.0, 0.0, 100.0), axis.point, 1e-3)
        assertVec(Vec3.Z, axis.direction, 1e-9)
        // os 4 cantos ficam no círculo que passa por eles
        assertEquals(300.0 * kotlin.math.sqrt(2.0), axis.radiusMm, 1e-3)
    }

    @Test
    fun aresta_redonda_da_o_centro_e_aresta_reta_a_direcao() {
        val j1 = twoParts().part("J1")!!
        // toque na lateral do cilindro, perto da borda de cima (z = 380)
        val (tri, dist) = j1.raycast(Vec3(1000.0, 100.0, 375.0), Vec3(-1.0, 0.0, 0.0))!!
        val hit = Vec3(1000.0 - dist, 100.0, 375.0)
        val axis = AxisFinder.fromEdge(j1, j1.faceAround(tri), hit)!!
        assertEquals(AxisGuess.Kind.ARESTA, axis.kind)
        assertVec(Vec3(200.0, 100.0, 380.0), axis.point, 1e-3)
        assertVec(Vec3.Z, axis.direction, 1e-9)
        assertEquals(50.0, axis.radiusMm, 1e-3)

        // caixa: a face de cima é um quadrado, o contorno não é círculo: vale a aresta reta
        val j0 = twoParts().part("J0")!!
        val (t0, _) = j0.raycast(Vec3(290.0, 0.0, 1000.0), Vec3(0.0, 0.0, -1.0))!!
        val edge = AxisFinder.fromEdge(j0, j0.faceAround(t0), Vec3(290.0, 0.0, 100.0))!!
        assertEquals(1.0, kotlin.math.abs(edge.direction.y), 1e-9) // a aresta em x = 300 corre em Y
        assertEquals(300.0, edge.point.x, 1e-6)
    }

    @Test
    fun vertice_vai_para_o_canto_mais_perto() {
        val j0 = twoParts().part("J0")!!
        val (t0, _) = j0.raycast(Vec3(290.0, 290.0, 1000.0), Vec3(0.0, 0.0, -1.0))!!
        val v = AxisFinder.nearestVertex(j0, t0, Vec3(290.0, 290.0, 100.0))
        assertVec(Vec3(300.0, 300.0, 100.0), v, 1e-3)
    }

    @Test
    fun raio_que_nao_bate_devolve_nada() {
        val j1 = twoParts().part("J1")!!
        assertNull(j1.raycast(Vec3(1000.0, 1000.0, 1000.0), Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun dois_pontos() {
        val a = AxisFinder.fromTwoPoints(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 10.0))!!
        assertVec(Vec3(0.0, 0.0, 5.0), a.point, 1e-12)
        assertVec(Vec3.Z, a.direction, 1e-12)
        assertNull(AxisFinder.fromTwoPoints(Vec3.ZERO, Vec3(0.1, 0.0, 0.0)))
    }

    @Test
    fun autovalores_de_matriz_simetrica() {
        // diag(3, 1, 2) girada 30° em Z
        val c = kotlin.math.cos(Math.PI / 6); val s = kotlin.math.sin(Math.PI / 6)
        val r = doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        val d = doubleArrayOf(3.0, 1.0, 2.0)
        val m = DoubleArray(9) { i ->
            val row = i / 3; val col = i % 3
            (0 until 3).sumOf { k -> r[row * 3 + k] * d[k] * r[col * 3 + k] }
        }
        val (values, vectors) = AxisFinder.symmetricEigen(m)
        assertEquals(3.0, values[0], 1e-9); assertEquals(2.0, values[1], 1e-9); assertEquals(1.0, values[2], 1e-9)
        assertEquals(1.0, kotlin.math.abs(vectors[0].dot(Vec3(c, s, 0.0))), 1e-9)
        assertEquals(1.0, kotlin.math.abs(vectors[1].z), 1e-9)
    }

    @Test
    fun matriz_inversa_e_trs() {
        val m = Mat4.trs(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.0, 0.0, kotlin.math.sin(Math.PI / 4), kotlin.math.cos(Math.PI / 4)), doubleArrayOf(2.0, 2.0, 2.0))
        // 90° em Z, escala 2: X vira 2·Y
        assertVec(Vec3(1.0, 4.0, 3.0), m.applyPoint(1.0, 0.0, 0.0), 1e-12)
        val back = m.inverse().applyPoint(1.0, 4.0, 3.0)
        assertVec(Vec3(1.0, 0.0, 0.0), back, 1e-12)
    }

    @Test
    fun json_ida_e_volta() {
        val v = mapOf("a" to listOf(1, 2.5, "x\"y\n"), "b" to null, "c" to true, "d" to mapOf<String, Any?>())
        val text = MiniJson.write(v)
        assertEquals("""{"a":[1,2.5,"x\"y\n"],"b":null,"c":true,"d":{}}""", text)
        val back = MiniJson.parse(text).obj()
        assertEquals(2.5, back["a"].arr()[1].num()!!, 0.0)
        assertEquals("x\"y\n", back["a"].arr()[2].str())
        assertNotNull(back["d"])
        assertEquals(-1.5e-3, MiniJson.parse(" -1.5E-3 ").num()!!, 1e-15)
    }
}
