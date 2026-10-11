package my.robots.feature.robot3d

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    PROGRAMA("6 Programa"),
}

/**
 * Como o toque marca um eixo: face redonda ou plana (Círculo), borda da face (Aresta), dois
 * pontos soltos (2 pontos) ou dois cantos da malha (Vértice).
 */
enum class PickMode(val label: String) {
    CIRCULO("Círculo"), ARESTA("Aresta"), DOIS_PONTOS("2 pontos"), VERTICE("Vértice");

    /** Modos que pedem dois toques. */
    val twoTaps get() = this == DOIS_PONTOS || this == VERTICE
}

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

    /** Programa de teste rodando (6ª etapa) e o ponto para onde está indo. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _runTarget = MutableStateFlow<Int?>(null)
    val runTarget: StateFlow<Int?> = _runTarget.asStateFlow()
    private var runJob: Job? = null

    private val root get() = File(getApplication<Application>().filesDir, "robos3d")

    init {
        refreshSaved()
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    // ---------- abrir ----------

    fun open(file: Robot3dViewModel.OpenedFile) {
        stopProgram()
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
        ).let { it.copy(baseFloorZ = floorOf(it.basePart)) }
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
        stopProgram()
        _step.value = step
        _editingAxis.value = null
        _pendingPoints.value = emptyList()
        _lastGuess.value = null
        _isolate.value = false
        if (step != AssemblerStep.TESTAR && step != AssemblerStep.PROGRAMA) _angles.value = List(6) { 0.0 }
    }

    fun selectPart(name: String?) {
        _selectedPart.value = name
    }

    fun setName(name: String) = _assembly.update { it?.copy(name = name) }

    fun setRole(part: String, assignment: PartAssignment?) = _assembly.update { a ->
        a?.withRole(part, assignment)?.let { it.copy(baseFloorZ = floorOf(it.basePart)) }
    }

    /** O ponto mais baixo (Z) da peça base no arquivo: o piso onde o robô é fixado. */
    private fun floorOf(basePart: String?): Double {
        val mesh = basePart?.let { _file.value?.parts?.part(it) } ?: return 0.0
        var z = Double.MAX_VALUE
        for (i in 0 until mesh.vertexCount) z = minOf(z, mesh.positions[i * 3 + 2].toDouble())
        return if (z == Double.MAX_VALUE) 0.0 else z
    }

    fun setOrigin(origin: RobotOrigin) = _assembly.update { a ->
        // ao passar para Personalizada, começa de onde a origem está agora
        if (a == null) return@update a
        val start = if (origin == RobotOrigin.PERSONALIZADA && a.origin != origin) a.nullBase()?.t ?: a.originCustom else a.originCustom
        a.copy(origin = origin, originCustom = start)
    }

    fun setOriginField(index: Int, value: Double) = _assembly.update { a ->
        val c = a?.originCustom ?: return@update a
        a.copy(originCustom = when (index) { 0 -> c.copy(x = value); 1 -> c.copy(y = value); else -> c.copy(z = value) })
    }

    /** Um campo do BASE do controlador (0 = X … 5 = T); começa todo em 0. */
    fun setBaseTransField(index: Int, value: Double) = _assembly.update { a ->
        val t = a?.baseTrans ?: KawasakiPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val v = listOf(t.x, t.y, t.z, t.o, t.a, t.t).toMutableList().also { it[index] = value }
        a?.copy(baseTrans = KawasakiPose(v[0], v[1], v[2], v[3], v[4], v[5]))
    }

    fun clearBaseTrans() = _assembly.update { it?.copy(baseTrans = null) }

    fun suggestRoles() = _assembly.update { a -> a?.copy(roles = RobotAssembly.suggestRoles(a.parts)) }

    fun setBase(x: Double? = null, y: Double? = null, z: Double? = null, rotDeg: Double? = null) = _assembly.update { a ->
        a?.copy(baseX = x ?: a.baseX, baseY = y ?: a.baseY, baseZ = z ?: a.baseZ, baseRotDeg = rotDeg ?: a.baseRotDeg)
    }

    fun baseToZero() = _assembly.update { it?.copy(baseX = 0.0, baseY = 0.0, baseZ = 0.0, baseRotDeg = 0.0) }

    fun setFront(front: RobotFront) = _assembly.update { it?.copy(front = front) }

    fun toggleIsolate() = _isolate.update { !it }

    /** Cor de uma peça (0xRRGGBB); null volta à cor do arquivo. */
    fun setColor(part: String, rgb: Int?) = _assembly.update { a ->
        a?.copy(colors = if (rgb == null) a.colors - part else a.colors + (part to rgb))
    }

    // ---------- programa de teste (6ª etapa) ----------

    private fun currentAngles(): List<Double>? {
        val n = _assembly.value?.model()?.axisCount ?: return null
        return _angles.value.take(n)
    }

    private fun updateProgram(change: (TestProgram) -> TestProgram) =
        _assembly.update { a -> a?.copy(program = change(a.program)) }

    /** Guarda a posição atual dos eixos como um ponto novo no fim. */
    fun addPoint() {
        val angles = currentAngles() ?: return
        updateProgram { p ->
            // nome livre: P1, P2… sem repetir os que já existem
            var n = p.points.size + 1
            while (p.points.any { it.name == "P$n" }) n++
            p.copy(points = p.points + TestPoint("P$n", angles))
        }
    }

    /** Troca os ângulos do ponto [index] pela posição atual. */
    fun updatePoint(index: Int) {
        val angles = currentAngles() ?: return
        updateProgram { p -> p.copy(points = p.points.mapIndexed { i, pt -> if (i == index) pt.copy(angles = angles) else pt }) }
    }

    fun deletePoint(index: Int) {
        stopProgram()
        updateProgram { p -> p.copy(points = p.points.filterIndexed { i, _ -> i != index }) }
    }

    fun movePoint(index: Int, delta: Int) = updateProgram { p ->
        val to = index + delta
        if (index !in p.points.indices || to !in p.points.indices) return@updateProgram p
        val list = p.points.toMutableList()
        val pt = list.removeAt(index)
        list.add(to, pt)
        p.copy(points = list)
    }

    /** Leva o robô direto ao ponto (sem animar). */
    fun goToPoint(index: Int) {
        val pt = _assembly.value?.program?.points?.getOrNull(index) ?: return
        val model = _assembly.value?.model() ?: return
        _angles.value = List(6) { i -> if (i < model.axisCount) model.joints[i].clamp(pt.angles.getOrElse(i) { 0.0 }) else 0.0 }
    }

    fun setSpeed(degS: Double) = updateProgram { it.copy(speedDegS = degS.coerceIn(1.0, 360.0)) }

    fun setPause(seconds: Double) = updateProgram { it.copy(pauseS = seconds.coerceIn(0.0, 30.0)) }

    /**
     * Percorre os pontos na ordem e volta ao primeiro, em loop, até [stopProgram]. Cada trecho
     * sai de onde o robô está; os ângulos passam pelos limites de cada eixo.
     */
    fun runProgram() {
        if (_running.value) return
        val a = _assembly.value ?: return
        val model = a.model() ?: return
        if (a.program.points.size < 2) {
            say("Adicione pelo menos 2 pontos para executar.")
            return
        }
        _running.value = true
        runJob = viewModelScope.launch {
            try {
                var i = 0
                while (isActive) {
                    val prog = _assembly.value?.program ?: break
                    if (prog.points.size < 2) break
                    if (i >= prog.points.size) i = 0
                    val target = prog.points[i].angles.take(model.axisCount).mapIndexed { k, v -> model.joints[k].clamp(v) }
                    _runTarget.value = i
                    val from = _angles.value.take(model.axisCount)
                    val total = TestProgram.durationS(from, target, prog.speedDegS)
                    val start = System.nanoTime()
                    while (isActive) {
                        val t = (System.nanoTime() - start) / 1e9 / total
                        val now = TestProgram.interpolate(from, target, t)
                        _angles.value = List(6) { k -> now.getOrElse(k) { 0.0 } }
                        if (t >= 1) break
                        delay(FRAME_MS)
                    }
                    delay((prog.pauseS * 1000).toLong())
                    i++
                }
            } finally {
                _running.value = false
                _runTarget.value = null
            }
        }
    }

    fun stopProgram() {
        runJob?.cancel()
        runJob = null
    }

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
        a.withAxis(number, def.copy(direction = -def.direction, minDeg = -def.maxDeg, maxDeg = -def.minDeg, zeroDeg = -def.zeroDeg))
    }

    fun clearAxis(number: Int) = _assembly.update { it?.withAxis(number, null) }

    /** Ângulo que o controlador mostra com a peça na pose do arquivo. */
    fun setZero(number: Int, deg: Double) = _assembly.update { a ->
        val def = a?.axes?.get(number) ?: return@update a
        a.withAxis(number, def.copy(zeroDeg = deg))
    }

    /** Um campo do TOOL (0 = X … 5 = T); o TOOL começa todo em 0. */
    fun setToolField(index: Int, value: Double) = _assembly.update { a ->
        val t = a?.tool ?: KawasakiPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val v = listOf(t.x, t.y, t.z, t.o, t.a, t.t).toMutableList().also { it[index] = value }
        a?.copy(tool = KawasakiPose(v[0], v[1], v[2], v[3], v[4], v[5]))
    }

    fun clearTool() = _assembly.update { it?.copy(tool = null) }

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

    /** TCP no espaço 3D com os ângulos atuais (para as setas do TCP). */
    fun tcpInWorld(): Transform? {
        val model = _assembly.value?.model() ?: return null
        val a = _angles.value
        return model.tcpInWorld(DoubleArray(model.axisCount) { a.getOrElse(it) { 0.0 } })
    }

    /** Onde cada peça montada está agora (para as marcas dos eixos e pontos). */
    fun poses(): Map<String, Transform> {
        val a = _assembly.value ?: return emptyMap()
        val angles = _angles.value
        return a.poses(DoubleArray(a.definedAxisCount) { angles.getOrElse(it) { 0.0 } })
    }

    /** Onde desenhar cada peça, com o ajuste de Mover e Girar (para o desenho e o toque). */
    fun displayPoses(): Map<String, Transform> {
        val a = _assembly.value ?: return emptyMap()
        val angles = _angles.value
        return a.displayPoses(DoubleArray(a.definedAxisCount) { angles.getOrElse(it) { 0.0 } })
    }

    // ---------- Mover, Girar e Fixar ----------

    /** Move a peça [mm] ao longo de [axis] (X, Y ou Z do espaço). */
    fun movePart(part: String, axis: Vec3, mm: Double) = adjust(part) { Transform.translation(axis * mm) * it }

    /** Gira a peça [deg] em volta de [axis] (X, Y ou Z do espaço), pelo centro dela. */
    fun rotatePart(part: String, axis: Vec3, deg: Double) {
        val mesh = _file.value?.parts?.part(part) ?: return
        val a = _assembly.value ?: return
        val center = a.toAssembled(part, mesh.bounds().first)
        adjust(part) { Transform.translation(center) * Transform.rotation(axis, Math.toRadians(deg)) * Transform.translation(-center) * it }
    }

    fun resetPart(part: String) = adjust(part) { Transform.IDENTITY }

    fun toggleLock(part: String) = _assembly.update { a ->
        a?.copy(locked = if (part in a.locked) a.locked - part else a.locked + part)
    }

    private fun adjust(part: String, change: (Transform) -> Transform) = _assembly.update { a ->
        if (a == null || part in a.locked) return@update a
        val next = change(a.offsets[part] ?: Transform.IDENTITY)
        a.copy(offsets = if (next.isClose(Transform.IDENTITY, 1e-9)) a.offsets - part else a.offsets + (part to next))
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
        if (old == null) new else new.copy(minDeg = old.minDeg, maxDeg = old.maxDeg, zeroDeg = old.zeroDeg)

    private fun markAxis(hit: GlbParts.Hit, apply: (AxisDef) -> Unit) {
        val parts = _file.value?.parts ?: return
        val mode = _pickMode.value
        when (mode) {
            PickMode.CIRCULO, PickMode.ARESTA -> {
                val mesh = parts.part(hit.part) ?: return
                _busy.value = true
                viewModelScope.launch {
                    val guess = withContext(Dispatchers.Default) {
                        val face = mesh.faceAround(hit.triangle)
                        if (mode == PickMode.CIRCULO) AxisFinder.fromFace(mesh, face) else AxisFinder.fromEdge(mesh, face, hit.pointInFile)
                    }
                    _busy.value = false
                    if (guess == null) {
                        say(
                            if (mode == PickMode.CIRCULO) "Essa face não gira em volta de um eixo. Toque numa face redonda ou plana da junta, ou use Aresta ou 2 pontos."
                            else "Não achei uma borda nessa face. Toque mais perto da borda redonda.",
                        )
                        return@launch
                    }
                    _lastGuess.value = guess
                    // a face foi lida na peça como está no arquivo: leva para a posição montada
                    val a = _assembly.value ?: return@launch
                    apply(AxisDef(a.toAssembled(hit.part, guess.point), a.dirToAssembled(hit.part, guess.direction),
                        kind = guess.kind, radiusMm = guess.radiusMm))
                    if (guess.errorMm > max(0.5, guess.radiusMm * 0.03)) {
                        say(String.format(Locale.US, "A face foge %.1f mm do círculo: confira o eixo ou toque noutra face.", guess.errorMm))
                    }
                }
            }
            PickMode.DOIS_PONTOS, PickMode.VERTICE -> {
                // Vértice: o toque vai para o canto mais perto do triângulo tocado
                val point = if (mode == PickMode.VERTICE) {
                    parts.part(hit.part)?.let { AxisFinder.nearestVertex(it, hit.triangle, hit.pointInFile) } ?: hit.pointInFile
                } else hit.pointInFile
                val assembled = _assembly.value?.toAssembled(hit.part, point) ?: point
                val points = _pendingPoints.value + (hit.part to assembled)
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
        /** Passo da animação do programa (~60 por segundo). */
        private const val FRAME_MS = 16L

        /** Nome de pasta seguro a partir do nome do robô. */
        internal fun slug(name: String): String {
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "robo" }
        }
    }
}
