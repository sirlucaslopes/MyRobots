package my.robots.feature.robot3d

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.kinematics.KawasakiPose
import my.robots.core.kinematics.RoboTeste
import my.robots.core.kinematics.RobotModel

/**
 * Estado do visualizador de teste: os ângulos do robô de teste e o .glb aberto.
 * Fica no ViewModel para sobreviver a girar a tela (o Filament é recriado, o estado não).
 */
class Robot3dViewModel : ViewModel() {

    val model: RobotModel = RoboTeste.modelo()

    /** O .glb do robô de teste, gerado uma vez. */
    val testRobotGlb: ByteArray by lazy {
        SceneModels.testRobotGlb(model.basePart, RoboTeste.eixos, RoboTeste.flange)
    }

    val partNames: List<String> get() = listOf(model.basePart) + model.joints.map { it.childPart }

    private val _angles = MutableStateFlow(List(model.axisCount) { 0.0 })
    /** Ângulo de cada eixo (graus), do eixo 1 ao último. */
    val angles: StateFlow<List<Double>> = _angles.asStateFlow()

    /**
     * Arquivo aberto pelo usuário: nome, bytes e as peças lidas (para tocar e realçar);
     * null = mostra o robô de teste.
     */
    data class OpenedFile(val name: String, val bytes: ByteArray, val parts: GlbParts?)

    private val _opened = MutableStateFlow<OpenedFile?>(null)
    val opened: StateFlow<OpenedFile?> = _opened.asStateFlow()

    fun setAngle(axisIndex: Int, deg: Double) {
        val joint = model.joints[axisIndex]
        _angles.value = _angles.value.toMutableList().also { it[axisIndex] = joint.clamp(deg) }
    }

    fun zeroAll() {
        _angles.value = List(model.axisCount) { 0.0 }
    }

    fun tcpPose(angles: List<Double>): KawasakiPose = model.tcpPose(angles.toDoubleArray())

    private val _selectedPart = MutableStateFlow<String?>(null)
    /** Peça tocada no arquivo aberto (realçada no desenho). */
    val selectedPart: StateFlow<String?> = _selectedPart.asStateFlow()

    fun openFile(file: OpenedFile) {
        _opened.value = file
        _selectedPart.value = null
    }

    fun closeFile() {
        _opened.value = null
        _selectedPart.value = null
    }

    fun selectPart(name: String?) {
        _selectedPart.value = name
    }
}
