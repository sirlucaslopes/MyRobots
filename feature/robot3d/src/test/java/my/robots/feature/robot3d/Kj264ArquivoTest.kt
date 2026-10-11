package my.robots.feature.robot3d

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Confere o leitor e o "Círculo" no KJ264 convertido do STEP. O arquivo fica em
 * `Arquivos_Kawasaki/` (fora do git): sem ele, o teste é pulado. Escreve as maiores faces
 * redondas de cada peça em `build/kj264_faces.txt`, para conferir os eixos à mão.
 */
class Kj264ArquivoTest {

    @Test
    fun kj264_tem_sete_pecas_e_faces_redondas() {
        val file = File("../../Arquivos_Kawasaki/KJ264.glb")
        assumeTrue("sem o KJ264.glb", file.exists())
        val parts = GlbReader.read(file.readBytes())
        assertEquals((0..6).map { if (it == 0) "KJ264J_B001_J0" else "KJ264_B001_J$it" }, parts.parts.map { it.name })

        val out = StringBuilder()
        for (part in parts.parts) {
            val seen = BooleanArray(part.triangleCount)
            data class Face(val axis: AxisGuess, val area: Double, val tris: Int)
            val faces = ArrayList<Face>()
            for (t in 0 until part.triangleCount) {
                if (seen[t]) continue
                val face = part.faceAround(t)
                face.forEach { seen[it] = true }
                val axis = AxisFinder.fromFace(part, face) ?: continue
                if (axis.kind != AxisGuess.Kind.REDONDA) continue
                faces += Face(axis, face.sumOf { part.triangleArea(it) }, face.size)
            }
            out.appendLine("${part.name}: ${part.triangleCount} triângulos, ${faces.size} faces redondas")
            for (f in faces.sortedByDescending { it.area }.take(6)) {
                val a = f.axis
                out.appendLine(String.format(Locale.US,
                    "  área %9.0f mm²  r %7.1f  erro %5.2f  ponto (%8.1f %8.1f %8.1f)  dir (%6.3f %6.3f %6.3f)",
                    f.area, a.radiusMm, a.errorMm, a.point.x, a.point.y, a.point.z, a.direction.x, a.direction.y, a.direction.z))
            }
        }
        File("build/kj264_faces.txt").writeText(out.toString())
    }
}
