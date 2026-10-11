package my.robots.feature.project

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.robots.core.designsystem.HeartbeatDot
import my.robots.core.designsystem.label
import my.robots.core.kinematics.KawasakiPose
import my.robots.core.kinematics.RoboTeste
import my.robots.core.kinematics.RobotModel
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import my.robots.core.render3d.Cabin3dLayout
import my.robots.core.render3d.FilamentViewer
import my.robots.core.render3d.OrbitCamera
import my.robots.core.render3d.RobotAssembly
import my.robots.core.render3d.RobotLibrary
import my.robots.core.render3d.SavedRobot
import my.robots.core.render3d.SceneModels
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot
import kotlin.math.roundToInt

/**
 * Um modelo pronto para a cabine: as peças (para mover), as poses na posição zero (em relação ao
 * arquivo), o ponto do arquivo que vai para o meio da vaga, para onde o braço aponta e o sistema
 * do robô (BASE 0) no arquivo. [assembly] null = o robô genérico (o robô de teste).
 */
private class CabinModel(
    val name: String,
    val glb: ByteArray,
    val yUp: Boolean,
    val partNames: List<String>,
    val zeroPoses: Map<String, Transform>,
    val anchor: Vec3,
    val armDir: Vec3,
    val nullBase: Transform,
) {
    companion object {
        /** O robô genérico: o robô de teste do :core:kinematics, desenhado em código. */
        fun generic(): CabinModel {
            val model = RoboTeste.modelo()
            val names = listOf(model.basePart) + model.joints.map { it.childPart }
            return CabinModel(
                name = "Genérico", glb = SceneModels.testRobotGlb(model.basePart, RoboTeste.eixos, RoboTeste.flange),
                yUp = false, partNames = names, zeroPoses = model.partTransforms(DoubleArray(model.axisCount)),
                anchor = Vec3.ZERO, armDir = Cabin3dLayout.armDirection(model, Vec3.ZERO), nullBase = model.robotFrame,
            )
        }

        /** Um robô montado no Montador; null se ainda não tem o eixo 1 marcado. */
        fun of(entry: RobotLibrary.Entry): CabinModel? {
            // a posição vem da cabine: a base do montador volta para o zero
            val a = entry.assembly.copy(baseX = 0.0, baseY = 0.0, baseZ = 0.0, baseRotDeg = 0.0)
            val a1 = a.axes[1]?.point ?: return null
            val model: RobotModel? = a.model()
            return CabinModel(
                name = a.name, glb = entry.glb, yUp = true, partNames = a.parts,
                zeroPoses = a.displayPoses(DoubleArray(a.definedAxisCount)),
                anchor = Vec3(a1.x, a1.y, a.baseFloorZ),
                armDir = Cabin3dLayout.armDirection(model, a1),
                nullBase = a.nullBase() ?: Transform.IDENTITY,
            )
        }
    }
}

/** Seletor do bloco da cabine: a grade 2D de sempre ou o 3D. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CabinModeSelector(is3d: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !is3d, onClick = { onChange(false) },
            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Cabine 2D") }
        SegmentedButton(
            selected = is3d, onClick = { onChange(true) },
            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("3D") }
    }
}

/** Cor do anel no chão: a mesma do LED do robô na cabine 2D. */
private fun ringColor(state: HeartbeatState): Int = when (state) {
    HeartbeatState.ALIVE -> 0x4CAF50
    HeartbeatState.STALE -> 0xFFA000
    HeartbeatState.DISCONNECTED -> 0x9E9E9E
}

/**
 * O modo 3D do bloco da cabine (Plano Mestre F3): os robôs da grade nas vagas, virados para o
 * transportador, com um anel no chão na cor do status e o nome por cima. Cada robô usa o modelo
 * 3D dele (um robô montado no Montador) ou o robô genérico. Tocar num robô abre o cartão dele
 * (abrir o painel, conectar, escolher o modelo e o BASE). Um dedo gira, pinça aproxima, dois
 * dedos arrastam.
 */
@Composable
internal fun Cabin3dBlock(
    view: CabinView,
    connected: Set<Int>,
    heartbeats: Map<Int, HeartbeatState>,
    onOpenRobot: (Robot) -> Unit,
    onToggleConnection: (Robot) -> Unit,
    onSetModel: (Robot, String?) -> Unit,
    onSetBase: (Robot, String?) -> Unit,
) {
    val context = LocalContext.current
    val library = remember { RobotLibrary(context.filesDir) }
    var viewer by remember { mutableStateOf<FilamentViewer?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var camTick by remember { mutableIntStateOf(0) }
    var sizePx by remember { mutableStateOf(0 to 0) }
    var saved by remember { mutableStateOf<List<SavedRobot>>(emptyList()) }
    var models by remember { mutableStateOf<Map<String, CabinModel?>>(emptyMap()) }
    var baseFor by remember { mutableStateOf<Robot?>(null) }
    val generic = remember { CabinModel.generic() }

    val layout = remember(view) {
        Cabin3dLayout(
            rows = view.cabin.rows, cols = view.cabin.cols,
            placed = view.cabin.placed.mapValues { it.value.row to it.value.col },
            bands = view.equipment.map { it.position to it.flowDirection },
        )
    }
    val placedRobots = view.robots.filter { it.id in view.cabin.placed }
    // o toque é criado uma vez (no SurfaceView): lê a grade e a seleção de agora
    val currentLayout by rememberUpdatedState(layout)
    val currentSelected by rememberUpdatedState(selectedId)

    // modelos salvos: a lista e os que os robôs desta cabine usam (lidos fora da thread da tela)
    LaunchedEffect(placedRobots.map { it.model3dId }) {
        val ids = placedRobots.mapNotNull { it.model3dId }.toSet()
        val loaded = withContext(Dispatchers.IO) {
            saved = library.list()
            ids.associateWith { id -> runCatching { CabinModel.of(library.load(id)) }.getOrNull() }
        }
        models = loaded
    }

    fun modelOf(r: Robot): CabinModel = r.model3dId?.let { models[it] } ?: generic
    fun placementOf(r: Robot): Transform? = modelOf(r).let { layout.placement(r.id, it.anchor, it.armDir) }

    // quadros só com a tela visível
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewer) {
        val v = viewer
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.resume()
                Lifecycle.Event.ON_PAUSE -> v?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v?.resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            v?.pause()
        }
    }

    // os robôs: um por vaga, recarregado quando muda o modelo ou a grade
    LaunchedEffect(viewer, layout, models, placedRobots.map { it.id to it.model3dId }) {
        val v = viewer ?: return@LaunchedEffect
        v.clearInstances()
        for (r in placedRobots) {
            val m = modelOf(r)
            val key = r.id.toString()
            if (!v.addInstance(key, m.glb, m.partNames, m.yUp)) continue
            val p = placementOf(r) ?: continue
            v.setInstancePoses(key, m.zeroPoses.mapValues { (_, pose) -> p * pose }, fallback = p)
        }
    }

    // transportador e anéis de status
    LaunchedEffect(viewer, layout, heartbeats, connected, selectedId) {
        val v = viewer ?: return@LaunchedEffect
        val colors = placedRobots.associate { r ->
            r.id to ringColor(heartbeats[r.id] ?: if (r.id in connected) HeartbeatState.STALE else HeartbeatState.DISCONNECTED)
        }
        v.setMarkers(SceneModels.cabinGlb(layout, colors, selectedId))
    }

    // setas do sistema do robô (BASE 0) e da base deslocada (BASE do robô) no robô tocado
    val selected = selectedId?.let { view.robotsById[it] }?.takeIf { it.id in view.cabin.placed }
    LaunchedEffect(viewer, selected, models) {
        val v = viewer ?: return@LaunchedEffect
        val r = selected ?: run { v.setFrames(null, null, null); return@LaunchedEffect }
        val p = placementOf(r) ?: return@LaunchedEffect
        val frame = p * modelOf(r).nullBase
        val base = r.robotBase?.let { KawasakiPose.parse(it) }?.let { frame * it.toTransform() }
        v.setFrames(frame, null, base)
    }

    val height = if (fullscreen) (LocalConfiguration.current.screenHeightDp * 0.72f).dp else 340.dp
    val density = LocalDensity.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF141417),
        modifier = Modifier.fillMaxWidth().height(height).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
    ) {
        Box(Modifier.fillMaxSize().onSizeChanged { sizePx = it.width to it.height }) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        viewer = FilamentViewer(sv).also { v ->
                            val (c, rad) = layout.bounds()
                            v.camera.apply(OrbitCamera.Preset.ISO)
                            v.camera.frame(c * 0.001, rad * 0.001)
                            v.camera.onChange = { camTick++ }
                            v.onTap = { x, y ->
                                val (o, d) = v.pickRay(x, y)
                                val hit = currentLayout.pick(o, d)
                                selectedId = if (hit == currentSelected) null else hit
                            }
                        }
                    }
                },
                onRelease = {
                    viewer?.destroy()
                    viewer = null
                },
            )

            // nome de cada robô por cima dele, com o LED (acompanha a câmera)
            val v = viewer
            val cameraVersion = camTick // ler aqui faz os nomes acompanharem a câmera
            if (v != null && sizePx.first > 0 && cameraVersion >= 0) {
                for (r in placedRobots) {
                    val (row, col) = layout.placed[r.id] ?: continue
                    val top = layout.cellCenter(row, col) + Vec3(0.0, 0.0, 2900.0)
                    val px = v.camera.project(top * 0.001, sizePx.first, sizePx.second) ?: continue
                    val state = heartbeats[r.id] ?: HeartbeatState.DISCONNECTED
                    Row(
                        Modifier
                            .offset { IntOffset(px.first.roundToInt() - with(density) { 28.dp.roundToPx() }, px.second.roundToInt()) }
                            .background(if (r.id == selectedId) Color(0xFFAEC6FF) else Color(0xE61B1B1F), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        HeartbeatDot(state)
                        Text(r.name, style = MaterialTheme.typography.labelMedium,
                            color = if (r.id == selectedId) Color(0xFF002E6C) else Color(0xFFE3E2E6),
                            fontWeight = if (r.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }

            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = {
                    val (c, rad) = layout.bounds()
                    viewer?.camera?.apply(OrbitCamera.Preset.ISO)
                    viewer?.camera?.frame(c * 0.001, rad * 0.001)
                }) { Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Recentralizar a vista") }
                FilledTonalIconButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "Diminuir" else "Aumentar")
                }
            }
            if (selected == null) {
                Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((preset, label) in listOf(OrbitCamera.Preset.ISO to "Iso", OrbitCamera.Preset.TOPO to "Topo", OrbitCamera.Preset.FRENTE to "Frente")) {
                        Surface(
                            onClick = { viewer?.camera?.apply(preset) },
                            shape = RoundedCornerShape(18.dp), color = Color(0xFF26262B),
                        ) { Text(label, color = Color(0xFFE3E2E6), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    }
                }
                if (placedRobots.isEmpty()) {
                    Text("Nenhum robô na grade: use Editar layout.", color = Color(0xFFB4B3BA),
                        modifier = Modifier.align(Alignment.Center))
                }
            } else {
                RobotCard(
                    robot = selected,
                    state = heartbeats[selected.id] ?: HeartbeatState.DISCONNECTED,
                    connected = selected.id in connected,
                    modelName = modelOf(selected).name,
                    missingModel = selected.model3dId != null && models.containsKey(selected.model3dId) && models[selected.model3dId] == null,
                    saved = saved,
                    onClose = { selectedId = null },
                    onOpen = { onOpenRobot(selected) },
                    onToggleConnection = { onToggleConnection(selected) },
                    onSetModel = { onSetModel(selected, it) },
                    onEditBase = { baseFor = selected },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    Text("Arraste para girar · pinça para aproximar · toque num robô", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

    baseFor?.let { r ->
        BaseDialog(r, onDismiss = { baseFor = null }, onSave = { onSetBase(r, it); baseFor = null })
    }
}

@Composable
private fun RobotCard(
    robot: Robot,
    state: HeartbeatState,
    connected: Boolean,
    modelName: String,
    missingModel: Boolean,
    saved: List<SavedRobot>,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onToggleConnection: () -> Unit,
    onSetModel: (String?) -> Unit,
    onEditBase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = Color(0xFF26262B), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(0xFF000000 or ringColor(state).toLong()), CircleShape))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(robot.name, style = MaterialTheme.typography.titleMedium, color = Color(0xFFE3E2E6))
                    Text("${state.label()} · $modelName" + (robot.robotBase?.let { " · BASE $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4B3BA), maxLines = 1)
                    if (missingModel) {
                        Text("O modelo 3D salvo não abriu (apagado ou sem o eixo 1): usando o genérico.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color(0xFFE3E2E6)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Abrir painel") }
                OutlinedButton(onClick = onToggleConnection, modifier = Modifier.weight(1f)) { Text(if (connected) "Desconectar" else "Conectar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Modelo 3D") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Genérico") }, onClick = { menu = false; onSetModel(null) })
                        for (s in saved) DropdownMenuItem(text = { Text(s.name) }, onClick = { menu = false; onSetModel(s.id) })
                        if (saved.isEmpty()) {
                            DropdownMenuItem(text = { Text("Nenhum robô montado: use o Montador de robô") }, onClick = { menu = false }, enabled = false)
                        }
                    }
                }
                TextButton(onClick = onEditBase) { Text("BASE do robô") }
            }
        }
    }
}

/** BASE do controlador deste robô (X Y Z O A T); vazio = BASE 0. */
@Composable
private fun BaseDialog(robot: Robot, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val start = robot.robotBase?.let { KawasakiPose.parse(it) }
    val labels = listOf("X (mm)", "Y (mm)", "Z (mm)", "O (°)", "A (°)", "T (°)")
    val texts = remember {
        val v = start?.let { listOf(it.x, it.y, it.z, it.o, it.a, it.t) } ?: List(6) { 0.0 }
        androidx.compose.runtime.mutableStateListOf(*v.map { if (it == Math.rint(it)) it.toLong().toString() else it.toString() }.toTypedArray())
    }
    val numbers = texts.map { it.replace(',', '.').toDoubleOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BASE de ${robot.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Os mesmos valores do BASE no controlador (o padrão é BASE 0). As setas na cabine mostram o BASE 0 e a base deslocada.",
                    style = MaterialTheme.typography.bodySmall)
                for (row in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in listOf(row, row + 3)) {
                            OutlinedTextField(
                                value = texts[k],
                                onValueChange = { texts[k] = it },
                                label = { Text(labels[k]) },
                                singleLine = true,
                                isError = numbers[k] == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = numbers.all { it != null }, onClick = {
                val n = numbers.map { it!! }
                onSave(if (n.all { it == 0.0 }) null else KawasakiPose(n[0], n[1], n[2], n[3], n[4], n[5]).format())
            }) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("BASE 0") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
