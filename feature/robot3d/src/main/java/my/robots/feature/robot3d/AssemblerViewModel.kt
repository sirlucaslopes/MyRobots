package my.robots.feature.robot3d

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.core.kinematics.KawasakiPose
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Etapas do montador, na ordem da barra de etapas. */
enum class AssemblerStep(val label: String) {
    PECAS("1 Peças"), BASE("2 Base"), EIXOS("3 Eixos"), FLANGE("4 Flange"), TESTAR("5 Testar"),
}

/** Como o toque marca um eixo: face (Círculo) ou dois pontos. */
enum class PickMode(val label: String) { CIRCULO("Círculo"), DOIS_PONTOS("2 pontos") }

/** Robô montado salvo no aparelho. */
data class SavedRobot(val id: String, val name: String)

/**
 * Estado do "Montador de robô" (Plano Mestre F3d): o .glb aberto, o [RobotAssembly] que vai
 * sendo preenchido e o que o toque faz em cada etapa. As contas pesadas (ler o .glb, achar a
 * face) rodam fora da thread da tela.
 *
 * Os robôs montados ficam em `files/robos3d/<id>/` (o `modelo.json` e uma cópia do `.glb`).
 */
class AssemblerViewModel(app: Application) : AndroidViewModel(app) {

    private val _file = MutableStateFlow<Robot3dViewModel.OpenedFile?>(null)
    val file: StateFlow<Robot3dViewModel.OpenedFile?> = _file.asStateFlow()

    private val _assembly = MutableStateFlow<RobotAssembly?>(null)
    val assembly: StateFlow<RobotAssembly?> = _assembly.asStateFlow()

    private val _step = MutableStateFlow(AssemblerStep.PECAS)
    val step: StateFlow<AssemblerStep> = _step.asStateFlow()

    private val _selectedPart = MutableStateFlow<String?>(null)
    val selectedPart: StateFlow<String?> = _selectedPart.asStateFlow()

    /** Eixo sendo marcado na etapa Eixos (null = só a lista). */
    private val _editingAxis = MutableStateFlow<Int?>(null)
    val editingAxis: StateFlow<Int?> = _editingAxis.asStateFlow()

    private val _pickMode = MutableStateFlow(PickMode.CIRCULO)
    val pickMode: StateFlow<PickMode> = _pickMode.asStateFlow()

    /** Pontos já tocados no modo 2 pontos: a peça e o ponto na pose do arquivo. */
    private val _pendingPoints = MutableStateFlow<List<Pair<String, Vec3>>>(emptyList())
    val pendingPoints: StateFlow<List<Pair<String, Vec3>>> = _pendingPoints.asStateFlow()

    private val _isolate = MutableStateFlow(false)
    val isolate: StateFlow<Boolean> = _isolate.asStateFlow()

    /** Ângulo de cada eixo marcado (graus), para testar. */
    private val _angles = MutableStateFlow(List(6) { 0.0 })
    val angles: StateFlow<List<Double>> = _angles.asStateFlow()

    /** O último eixo achado pelo toque, para mostrar o erro da face. */
    private val _lastGuess = MutableStateFlow<AxisGuess?>(null)
    val lastGuess: StateFlow<AxisGuess?> = _lastGuess.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _saved = MutableStateFlow<List<SavedRobot>>(emptyList())
    val saved: StateFlow<List<SavedRobot>> = _saved.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val root get() = File(getApplication<Application>().filesDir, "robos3d")

    init {
        refreshSaved()
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    // ---------- abrir ----------

    fun open(file: Robot3dViewModel.OpenedFile) {
        val parts = file.parts
        if (parts == null) {
            say("Não deu para ler as peças desse .glb.")
            return
        }
        val names = parts.parts.map { it.name }
        _file.value = file
        _assembly.value = RobotAssembly(
            name = file.name.substringBeforeLast('.').ifBlank { "Robô" },
            fileName = file.name,
            parts = names,
            roles = RobotAssembly.suggestRoles(names),
        )
        resetView(AssemblerStep.PECAS)
        parts.warnings.firstOrNull()?.let { say(it) }
    }

    private fun resetView(step: AssemblerStep) {
        _step.value = step
        _selectedPart.value = null
        _editingAxis.value = null
        _pendingPoints.value = emptyList()
        _isolate.value = false
        _lastGuess.value = null
        _angles.value = List(6) { 0.0 }
    }

    // ---------- etapas e peças ----------

    fun setStep(step: AssemblerStep) {
        _step.value = step
        _editingAxis.value = null
        _pendingPoints.value = emptyList()
        _lastGuess.value = null
        _isolate.value = false
        if (step != AssemblerStep.TESTAR) _angles.value = List(6) { 0.0 }
    }

    fun selectPart(name: String?) {
        _selectedPart.value = name
    }

    fun setName(name: String) = _assembly.update { it?.copy(name = name) }

    fun setRole(part: String, assignment: PartAssignment?) = _assembly.update { it?.withRole(part, assignment) }

    fun suggestRoles() = _assembly.update { a -> a?.copy(roles = RobotAssembly.suggestRoles(a.parts)) }

    fun setBase(x: Double? = null, y: Double? = null, z: Double? = null, rotDeg: Double? = null) = _assembly.update { a ->
        a?.copy(baseX = x ?: a.baseX, baseY = y ?: a.baseY, baseZ = z ?: a.baseZ, baseRotDeg = rotDeg ?: a.baseRotDeg)
    }

    fun baseToZero() = _assembly.update { it?.copy(baseX = 0.0, baseY = 0.0, baseZ = 0.0, baseRotDeg = 0.0) }

    fun setFront(front: RobotFront) = _assembly.update { it?.copy(front = front) }

    fun toggleIsolate() = _isolate.update { !it }

    // ---------- eixos ----------

    fun editAxis(number: Int?) {
        _editingAxis.value = number
        _pendingPoints.value = emptyList()
        _lastGuess.value = null
        _angles.value = List(6) { 0.0 }
    }

    fun setPickMode(mode: PickMode) {
        _pickMode.value = mode
        _pendingPoints.value = emptyList()
    }

    fun invertAxis(number: Int) = _assembly.update { a ->
        val def = a?.axes?.get(number) ?: return@update a
        a.withAxis(number, def.copy(direction = -def.direction, minDeg = -def.maxDeg, maxDeg = -def.minDeg))
    }

    fun clearAxis(number: Int) = _assembly.update { it?.withAxis(number, null) }

    fun setLimits(number: Int, minDeg: Double?, maxDeg: Double?) = _assembly.update { a ->
        val def = a?.axes?.get(number) ?: return@update a
        val lo = minDeg ?: def.minDeg
        val hi = maxDeg ?: def.maxDeg
        if (lo > hi) return@update a
        a.withAxis(number, def.copy(minDeg = lo, maxDeg = hi))
    }

    fun invertFlange() = _assembly.update { a ->
        val f = a?.flange ?: return@update a
        a.copy(flange = f.copy(direction = -f.direction))
    }

    fun clearFlange() = _assembly.update { it?.copy(flange = null) }

    // ---------- teste ----------

    fun setAngle(index: Int, deg: Double) {
        val model = _assembly.value?.model() ?: return
        if (index >= model.axisCount) return
        _angles.update { list -> list.toMutableList().also { it[index] = model.joints[index].clamp(deg) } }
    }

    fun zeroAll() {
        _angles.value = List(6) { 0.0 }
    }

    fun tcpPose(): KawasakiPose? {
        val model = _assembly.value?.model() ?: return null
        val a = _angles.value
        return model.tcpPose(DoubleArray(model.axisCount) { a.getOrElse(it) { 0.0 } })
    }

    /** Onde cada peça está agora (para desenhar e para o toque). */
    fun poses(): Map<String, Transform> {
        val a = _assembly.value ?: return emptyMap()
        val angles = _angles.value
        return a.poses(DoubleArray(a.definedAxisCount) { angles.getOrElse(it) { 0.0 } })
    }

    // ---------- toque ----------

    /** O toque bateu numa peça: em cada etapa faz uma coisa. */
    fun onHit(hit: GlbParts.Hit?) {
        val step = _step.value
        val axis = _editingAxis.value
        when {
            step == AssemblerStep.EIXOS && axis != null -> if (hit != null) markAxis(hit) { def -> _assembly.update { it?.withAxis(axis, keepLimits(it.axes[axis], def)) } }
            step == AssemblerStep.FLANGE -> if (hit != null) markAxis(hit) { def -> _assembly.update { it?.copy(flange = def) } }
            else -> _selectedPart.value = hit?.part
        }
    }

    private fun keepLimits(old: AxisDef?, new: AxisDef) =
        if (old == null) new else new.copy(minDeg = old.minDeg, maxDeg = old.maxDeg)

    private fun markAxis(hit: GlbParts.Hit, apply: (AxisDef) -> Unit) {
        val parts = _file.value?.parts ?: return
        when (_pickMode.value) {
            PickMode.CIRCULO -> {
                val mesh = parts.part(hit.part) ?: return
                _busy.value = true
                viewModelScope.launch {
                    val guess = withContext(Dispatchers.Default) { AxisFinder.fromFace(mesh, mesh.faceAround(hit.triangle)) }
                    _busy.value = false
                    if (guess == null) {
                        say("Essa face não gira em volta de um eixo. Toque numa face redonda ou plana da junta, ou use 2 pontos.")
                        return@launch
                    }
                    _lastGuess.value = guess
                    apply(AxisDef(guess.point, guess.direction, kind = guess.kind, radiusMm = guess.radiusMm))
                    if (guess.errorMm > max(0.5, guess.radiusMm * 0.03)) {
                        say(String.format(Locale.US, "A face foge %.1f mm do círculo: confira o eixo ou toque noutra face.", guess.errorMm))
                    }
                }
            }
            PickMode.DOIS_PONTOS -> {
                val points = _pendingPoints.value + (hit.part to hit.pointInFile)
                if (points.size < 2) {
                    _pendingPoints.value = points
                    return
                }
                _pendingPoints.value = emptyList()
                val guess = AxisFinder.fromTwoPoints(points[0].second, points[1].second)
                if (guess == null) {
                    say("Os dois pontos estão muito perto: toque em pontos mais afastados.")
                    return
                }
                _lastGuess.value = guess
                apply(AxisDef(guess.point, guess.direction, kind = guess.kind))
            }
        }
    }

    // ---------- salvar ----------

    fun save() {
        val a = _assembly.value ?: return
        val f = _file.value ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(root, slug(a.name))
                    val existed = dir.exists()
                    dir.mkdirs()
                    File(dir, "robo.glb").writeBytes(f.bytes)
                    // escreve num temporário e troca: um salvamento cortado não estraga o anterior
                    val tmp = File(dir, "modelo.json.tmp")
                    tmp.writeText(a.toJson())
                    val target = File(dir, "modelo.json")
                    if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                    existed
                }
            }
            result.fold(
                onSuccess = { existed -> say(if (existed) "Salvo: \"${a.name}\" (substituiu o anterior)." else "Salvo: \"${a.name}\".") },
                onFailure = { say("Não deu para salvar: ${it.message}") },
            )
            refreshSaved()
        }
    }

    fun refreshSaved() {
        viewModelScope.launch {
            _saved.value = withContext(Dispatchers.IO) {
                root.listFiles()?.filter { File(it, "modelo.json").isFile }?.mapNotNull { dir ->
                    val name = runCatching { RobotAssembly.fromJson(File(dir, "modelo.json").readText()).name }.getOrNull()
                    name?.let { SavedRobot(dir.name, it) }
                }?.sortedBy { it.name.lowercase() }.orEmpty()
            }
        }
    }

    fun openSaved(id: String) {
        _busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(root, id)
                    val assembly = RobotAssembly.fromJson(File(dir, "modelo.json").readText())
                    val bytes = File(dir, "robo.glb").readBytes()
                    val parts = GlbReader.read(bytes)
                    Triple(assembly, bytes, parts)
                }
            }
            _busy.value = false
            result.fold(
                onSuccess = { (assembly, bytes, parts) ->
                    val names = parts.parts.map { it.name }
                    if (names != assembly.parts) say("As peças do arquivo mudaram desde que o robô foi salvo: confira os tipos.")
                    _file.value = Robot3dViewModel.OpenedFile(assembly.fileName, bytes, parts)
                    _assembly.value = assembly.copy(parts = names)
                    resetView(if (assembly.model() != null) AssemblerStep.TESTAR else AssemblerStep.PECAS)
                },
                onFailure = { say("Não deu para abrir o robô salvo: ${it.message}") },
            )
        }
    }

    fun deleteSaved(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(root, id).deleteRecursively() }
            refreshSaved()
        }
    }

    companion object {
        /** Nome de pasta seguro a partir do nome do robô. */
        internal fun slug(name: String): String {
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "robo" }
        }
    }
}
