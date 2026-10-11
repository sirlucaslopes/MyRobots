package my.robots.core.render3d

import my.robots.core.kinematics.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Câmera que gira em volta de um alvo, com **Z para cima** (como o robô e o `WHERE`).
 * Distâncias em metros (o espaço do Filament); ângulos em graus.
 *
 * - [yawDeg]: giro em volta de Z, medido a partir de +X (0° = olhando do lado +X para o alvo).
 * - [pitchDeg]: altura do olho; 90° = de cima, 0° = na altura do alvo.
 *
 * Kotlin puro: os gestos só chamam [orbit], [zoom] e [pan], e o desenho lê [eye].
 */
class OrbitCamera(
    var target: Vec3 = Vec3(0.6, 0.0, 0.8),
    var distance: Double = 4.0,
    var yawDeg: Double = -45.0,
    var pitchDeg: Double = 30.0,
    /** Campo de visão vertical (graus), o mesmo passado para o Filament. */
    val fovDeg: Double = 45.0,
) {

    /** Vistas prontas da barra de ações. */
    enum class Preset(val yawDeg: Double, val pitchDeg: Double) {
        /** Isométrica: de frente-direita e de cima (as três faces de um cubo iguais). */
        ISO(-45.0, 35.264),
        /** De cima, com +X para a direita e +Y para cima na tela (o olho fica 0,1° fora do Z). */
        TOPO(-90.0, MAX_PITCH),
        /** Do lado +X (a frente do robô), na altura do alvo. */
        FRENTE(0.0, 0.0),
        /** Do lado −Y (a direita do robô, que olha para +X), na altura do alvo. */
        LADO(-90.0, 0.0),
    }

    /** Posição do olho. */
    val eye: Vec3
        get() {
            val yaw = Math.toRadians(yawDeg)
            val pitch = Math.toRadians(pitchDeg)
            return target + Vec3(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), sin(pitch)) * distance
        }

    /** Direita e cima da tela, no espaço 3D (para arrastar com dois dedos e virar a legenda). */
    fun screenAxes(): Pair<Vec3, Vec3> {
        val forward = (target - eye).normalized()
        val right = forward.cross(Vec3.Z).normalized()
        val up = right.cross(forward)
        return right to up
    }

    /** Um dedo: [dxPx] gira em volta de Z, [dyPx] sobe ou desce o olho. */
    fun orbit(dxPx: Float, dyPx: Float, degPerPx: Double = 0.3) {
        yawDeg = normalizeDeg(yawDeg - dxPx * degPerPx)
        pitchDeg = (pitchDeg + dyPx * degPerPx).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** Pinça: [scale] > 1 (dedos se afastando) aproxima. */
    fun zoom(scale: Float) {
        if (scale <= 0f) return
        distance = (distance / scale).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /**
     * Dois dedos: arrasta a cena junto com os dedos. [viewHeightPx] converte pixels em metros
     * na distância do alvo (o que está no alvo acompanha o dedo).
     */
    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        if (viewHeightPx <= 0) return
        val metersPerPx = 2 * distance * tan(Math.toRadians(fovDeg / 2)) / viewHeightPx
        val (right, up) = screenAxes()
        target = target - right * (dxPx * metersPerPx) + up * (dyPx * metersPerPx)
    }

    /**
     * Raio que sai do olho e passa pelo pixel ([xPx], [yPx]) de uma tela [widthPx] × [heightPx]
     * (y para baixo, como no toque). Devolve origem e direção (comprimento 1), em metros.
     */
    fun ray(xPx: Float, yPx: Float, widthPx: Int, heightPx: Int): Pair<Vec3, Vec3> {
        val e = eye
        val forward = (target - e).normalized()
        val (right, up) = screenAxes()
        val halfH = tan(Math.toRadians(fovDeg / 2))
        val halfW = halfH * widthPx / heightPx.coerceAtLeast(1)
        val sx = (2.0 * xPx / widthPx.coerceAtLeast(1) - 1) * halfW
        val sy = (1 - 2.0 * yPx / heightPx.coerceAtLeast(1)) * halfH
        return e to (forward + right * sx + up * sy).normalized()
    }

    fun apply(preset: Preset) {
        yawDeg = preset.yawDeg
        pitchDeg = preset.pitchDeg
    }

    /** Põe uma esfera ([center], [radius] em metros) inteira na tela, sem mudar o ângulo. */
    fun frame(center: Vec3, radius: Double) {
        target = center
        val r = radius.coerceAtLeast(0.05)
        distance = (r / sin(Math.toRadians(fovDeg / 2)) * 1.15).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    companion object {
        const val MIN_PITCH = -89.9
        const val MAX_PITCH = 89.9
        const val MIN_DISTANCE = 0.05
        const val MAX_DISTANCE = 2000.0

        private fun normalizeDeg(d: Double): Double {
            var a = d % 360.0
            if (a > 180) a -= 360
            if (a <= -180) a += 360
            return a
        }
    }
}
