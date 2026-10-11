package my.robots.feature.robot3d

import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.core.designsystem.ActionTone
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.kinematics.PartRole
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import java.util.Locale

/**
 * "Montador de robô" (Plano Mestre F3d): abre o .glb do robô dividido em peças e, em etapas,
 * diz o que é cada peça, onde fica a base, marca cada eixo tocando na face redonda da junta
 * (ou em dois pontos), marca o flange, testa os eixos e roda um programa de pontos em loop.
 * Cada peça pode ganhar uma cor. O robô montado é salvo no aparelho.
 */
@Composable
fun AssemblerScreen(viewModel: AssemblerViewModel, onBack: () -> Unit) {
    val file by viewModel.file.collectAsStateWithLifecycle()
    val assembly by viewModel.assembly.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val selectedPart by viewModel.selectedPart.collectAsStateWithLifecycle()
    val editingAxis by viewModel.editingAxis.collectAsStateWithLifecycle()
    val pickMode by viewModel.pickMode.collectAsStateWithLifecycle()
    val pending by viewModel.pendingPoints.collectAsStateWithLifecycle()
    val isolate by viewModel.isolate.collectAsStateWithLifecycle()
    val angles by viewModel.angles.collectAsStateWithLifecycle()
    val lastGuess by viewModel.lastGuess.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val runTarget by viewModel.runTarget.collectAsStateWithLifecycle()
    var colorFor by remember { mutableStateOf<String?>(null) }
    // setas azuis dos eixos já marcados (o eixo sendo marcado aparece sempre)
    var showAxes by rememberSaveable { mutableStateOf(true) }

    var viewer by remember { mutableStateOf<FilamentViewer?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val openGlb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { readGlb(context, uri) }
            loading = false
            result.fold(
                onSuccess = { viewModel.open(it) },
                onFailure = { snackbar.showSnackbar(it.message ?: "Não deu para ler o arquivo.") },
            )
        }
    }

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

    val a = assembly
    // a peça que fica sozinha na tela com "Isolar"
    val isolatedPart = when {
        a == null -> null
        step == AssemblerStep.EIXOS && editingAxis != null -> a.partOf(editingAxis!!)
        step == AssemblerStep.FLANGE -> a.axisParts.lastOrNull()?.second
        else -> selectedPart
    }
    val hidden = if (isolate && a != null && isolatedPart != null) a.parts.toSet() - isolatedPart else emptySet()
    val poses = remember(a, angles) { viewModel.poses() }
    val drawPoses = remember(a, angles) { viewModel.displayPoses() }

    LaunchedEffect(viewer, file) {
        val v = viewer ?: return@LaunchedEffect
        val f = file ?: return@LaunchedEffect
        val result = v.showGlb(f.bytes, f.parts?.parts?.map { it.name }.orEmpty())
        result.message?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(viewer, file, drawPoses) { viewer?.setUserPoses(drawPoses) }
    LaunchedEffect(viewer, file, a?.colors) { viewer?.setPartColors(a?.colors.orEmpty()) }
    // setas do sistema do robô (na base) e do TCP, nas etapas em que importam
    LaunchedEffect(viewer, a, angles, step) {
        val showRobot = step in setOf(AssemblerStep.BASE, AssemblerStep.TESTAR, AssemblerStep.PROGRAMA)
        val showTcp = step in setOf(AssemblerStep.FLANGE, AssemblerStep.TESTAR, AssemblerStep.PROGRAMA)
        viewer?.setFrames(
            robot = if (showRobot) a?.robotFrameInWorld() else null,
            tcp = if (showTcp) viewModel.tcpInWorld() else null,
        )
    }
    LaunchedEffect(viewer, file, hidden) { viewer?.setHiddenParts(hidden) }
    LaunchedEffect(viewer, file, isolatedPart, step) {
        viewer?.highlight(if (step == AssemblerStep.EIXOS && editingAxis == null) null else isolatedPart)
    }
    // marcas: os eixos (o editado em amarelo) e os pontos tocados, na pose atual
    LaunchedEffect(viewer, a, poses, step, editingAxis, pending, showAxes) {
        val v = viewer ?: return@LaunchedEffect
        if (a == null) { v.setMarkers(null); return@LaunchedEffect }
        val axes = ArrayList<SceneModels.AxisMarker>()
        when (step) {
            AssemblerStep.EIXOS, AssemblerStep.TESTAR -> for ((n, def) in a.axes) {
                if (!showAxes && n != editingAxis) continue
                val pose = a.parentOf(n)?.let { poses[it] } ?: Transform.IDENTITY
                axes += SceneModels.AxisMarker(pose.apply(def.point), pose.rotate(def.direction), def.radiusMm, n == editingAxis)
            }
            AssemblerStep.FLANGE -> {
                val last = a.axisParts.lastOrNull()?.second
                val pose = last?.let { poses[it] } ?: Transform.IDENTITY
                val f = a.flange ?: a.axes[a.definedAxisCount]
                if (f != null) axes += SceneModels.AxisMarker(pose.apply(f.point), pose.rotate(f.direction), f.radiusMm, true)
            }
            else -> Unit
        }
        val points = pending.map { (part, p) -> (poses[part] ?: Transform.IDENTITY).apply(p) }
        v.setMarkers(SceneModels.markersGlb(axes, points))
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Montador de robô",
                subtitle = a?.let { "${it.name} · ${step.label.drop(2)}" } ?: "Abra o .glb do robô",
                onBack = onBack,
                menu = if (saved.isEmpty()) null else { close ->
                    DropdownMenuItem(
                        text = { Text("Excluir robô salvo…") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = { close(); showDelete = true },
                    )
                },
                actions = listOf(
                    BarAction(Icons.Rounded.FolderOpen, "Abrir .glb", enabled = !loading && viewer != null, onClick = {
                        openGlb.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
                    }),
                    BarAction(
                        Icons.Rounded.Inventory2, "Salvos", enabled = saved.isNotEmpty() && !busy,
                        menu = { close ->
                            for (s in saved) {
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { close(); viewModel.openSaved(s.id) })
                            }
                        },
                    ),
                    viewsAction { viewer },
                    BarAction(
                        Icons.Rounded.Autorenew, "Eixos",
                        selected = showAxes,
                        onClick = { showAxes = !showAxes },
                    ),
                    BarAction(
                        Icons.Rounded.CenterFocusStrong, "Isolar",
                        enabled = isolatedPart != null, selected = isolate,
                        onClick = viewModel::toggleIsolate,
                    ),
                    BarAction(
                        Icons.Rounded.Save, "Salvar",
                        enabled = a != null && file != null, tone = ActionTone.Primary,
                        onClick = viewModel::save,
                    ),
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // com o teclado aberto, o painel sobe junto e o desenho encolhe
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            viewer = FilamentViewer(sv, onFps = { fps = it }).also { v ->
                                v.camera.apply(OrbitCamera.Preset.ISO)
                                v.onTap = tap@{ x, y ->
                                    val parts = viewModel.file.value?.parts ?: return@tap
                                    val (o, d) = v.pickRay(x, y)
                                    val posesNow = viewModel.displayPoses()
                                    val hiddenNow = v.hiddenPartNames
                                    scope.launch {
                                        val hit = withContext(Dispatchers.Default) { parts.pick(o, d, posesNow) { it !in hiddenNow } }
                                        viewModel.onHit(hit)
                                    }
                                }
                            }
                        }
                    },
                    onRelease = {
                        viewer?.destroy()
                        viewer = null
                    },
                )
                Overlay(
                    text = hint(a, step, editingAxis, pickMode, pending.size, selectedPart),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                if (fps > 0) Overlay("$fps quadros/s", Modifier.align(Alignment.TopStart))
                if (loading || busy) Overlay(if (loading) "Lendo o arquivo…" else "Calculando…", Modifier.align(Alignment.Center))
            }
            HorizontalDivider()
            if (a == null) {
                EmptyPanel(saved, onOpen = { openGlb.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*")) }, onOpenSaved = viewModel::openSaved)
            } else {
                StepBar(step, viewModel::setStep)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (step) {
                        AssemblerStep.PECAS -> PartsStep(a, selectedPart, viewModel, onColor = { colorFor = it })
                        AssemblerStep.BASE -> BaseStep(a, viewModel)
                        AssemblerStep.EIXOS -> AxesStep(a, editingAxis, pickMode, lastGuess, angles, viewModel)
                        AssemblerStep.FLANGE -> FlangeStep(a, pickMode, viewModel)
                        AssemblerStep.TESTAR -> TestStep(a, angles, selectedPart, viewModel, onColor = { colorFor = it })
                        AssemblerStep.PROGRAMA -> ProgramStep(a, angles, running, runTarget, viewModel)
                    }
                }
            }
        }
    }

    colorFor?.let { part ->
        ColorDialog(
            part = part,
            current = a?.colors?.get(part),
            onPick = { viewModel.setColor(part, it); colorFor = null },
            onDismiss = { colorFor = null },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Excluir robô salvo") },
            text = {
                Column {
                    if (saved.isEmpty()) Text("Nenhum robô salvo.")
                    for (s in saved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f))
                            IconButton(onClick = { viewModel.deleteSaved(s.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Excluir ${s.name}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDelete = false }) { Text("Fechar") } },
        )
    }
}

/** O que fazer agora, escrito embaixo do desenho. */
private fun hint(a: RobotAssembly?, step: AssemblerStep, editing: Int?, mode: PickMode, pending: Int, selected: String?): String {
    if (a == null) return "Abra o .glb do robô com as peças separadas"
    return when (step) {
        AssemblerStep.PECAS -> selected?.let { "Peça: $it · ${a.roles[it]?.label ?: "sem tipo"}" } ?: "Toque numa peça para ver o tipo dela"
        AssemblerStep.BASE -> "Posição da base e frente do robô"
        AssemblerStep.EIXOS -> when {
            editing == null -> "Escolha o eixo para marcar"
            mode == PickMode.CIRCULO -> "Eixo $editing: toque na face redonda da junta"
            mode == PickMode.ARESTA -> "Eixo $editing: toque perto da borda redonda da junta"
            mode == PickMode.VERTICE -> "Eixo $editing: toque no canto ${pending + 1} de 2 na linha do eixo"
            else -> "Eixo $editing: toque no ponto ${pending + 1} de 2 na linha do eixo"
        }
        AssemblerStep.FLANGE -> when (mode) {
            PickMode.CIRCULO -> "Toque na face do flange (a ponta do último eixo)"
            PickMode.ARESTA -> "Toque perto da borda redonda do flange"
            else -> "Flange: toque no ponto ${pending + 1} de 2"
        }
        AssemblerStep.TESTAR -> selected?.let { "Peça: $it" } ?: "Mexa os eixos para conferir"
        AssemblerStep.PROGRAMA -> "Posicione os eixos, adicione pontos e execute em loop"
    }
}

@Composable
private fun Overlay(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 2,
        modifier = modifier
            .padding(8.dp)
            .background(Color(0x88000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyPanel(saved: List<SavedRobot>, onOpen: () -> Unit, onOpenSaved: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("O robô precisa vir dividido: a base, uma peça por eixo e a ferramenta. Um robô de uma peça só não monta.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Converta o STEP do fabricante para .glb no PC, mantendo as peças com nome (ex.: J0 a J6).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpen) { Text("Abrir .glb") }
        if (saved.isNotEmpty()) {
            Text("Robôs salvos", style = MaterialTheme.typography.titleSmall)
            for (s in saved) {
                Text(s.name, Modifier.fillMaxWidth().clickable { onOpenSaved(s.id) }.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun StepBar(step: AssemblerStep, onStep: (AssemblerStep) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in AssemblerStep.entries) {
            FilterChip(selected = s == step, onClick = { onStep(s) }, label = { Text(s.label) })
        }
    }
}

// ---------- 1. Peças ----------

@Composable
private fun PartsStep(a: RobotAssembly, selected: String?, vm: AssemblerViewModel, onColor: (String) -> Unit) {
    NameField(a.name, vm::setName)
    val problems = a.problems()
    if (problems.isEmpty()) {
        Text("Tudo certo: ${a.axisParts.size} eixos. Siga para a Base.", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
    } else {
        for (p in problems) Text("• $p", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Peças (${a.parts.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::suggestRoles) { Text("Sugerir pelos nomes") }
    }
    if (selected != null) PartAdjust(a, selected, vm)
    val maxAxis = maxOf(6, a.parts.size - 1)
    for (part in a.parts) {
        var open by remember { mutableStateOf(false) }
        val role = a.roles[part]
        Box {
            Surface(
                color = if (part == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { vm.selectPart(part); open = true },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(a.colors[part]) { onColor(part) }
                    Spacer(Modifier.width(10.dp))
                    Text(part, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        role?.label ?: "Sem tipo",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (role == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val options = listOf(PartAssignment(PartRole.BASE)) +
                    (1..maxAxis).map { PartAssignment(PartRole.AXIS, it) } +
                    listOf(PartAssignment(PartRole.TOOL), PartAssignment(PartRole.OTHER))
                for (o in options) {
                    DropdownMenuItem(text = { Text(o.label) }, onClick = { open = false; vm.setRole(part, o) })
                }
                DropdownMenuItem(text = { Text("Sem tipo") }, onClick = { open = false; vm.setRole(part, null) })
            }
        }
    }
}

/**
 * Mover, Girar e Fixar a peça tocada: para peças que vieram fora da posição de montagem (um
 * arquivo por peça, ou exportadas cada uma no seu zero). Passos fixos, nos eixos do espaço.
 */
@Composable
private fun PartAdjust(a: RobotAssembly, part: String, vm: AssemblerViewModel) {
    var stepMm by remember { mutableStateOf(10.0) }
    var stepDeg by remember { mutableStateOf(15.0) }
    val locked = part in a.locked
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustar $part", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilterChip(selected = locked, onClick = { vm.toggleLock(part) }, label = { Text(if (locked) "Fixada" else "Fixar") })
            }
            val off = a.offsets[part]
            Text(
                off?.let { "Ajuste: ${my.robots.core.kinematics.KawasakiPose.fromTransform(it).format()}" } ?: "Na posição do arquivo",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            )
            if (locked) return@Column
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Passo", style = MaterialTheme.typography.labelMedium)
                for (mm in listOf(1.0, 10.0, 100.0)) FilterChip(selected = stepMm == mm, onClick = { stepMm = mm }, label = { Text("${mm.toInt()} mm") })
                for (deg in listOf(1.0, 15.0, 90.0)) FilterChip(selected = stepDeg == deg, onClick = { stepDeg = deg }, label = { Text("${deg.toInt()}°") })
            }
            val axes = listOf("X" to Vec3.X, "Y" to Vec3.Y, "Z" to Vec3.Z)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Mover", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                for ((name, v) in axes) {
                    TextButton(onClick = { vm.movePart(part, v, -stepMm) }) { Text("$name−") }
                    TextButton(onClick = { vm.movePart(part, v, stepMm) }) { Text("$name+") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Girar", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                for ((name, v) in axes) {
                    TextButton(onClick = { vm.rotatePart(part, v, -stepDeg) }) { Text("$name↻") }
                    TextButton(onClick = { vm.rotatePart(part, v, stepDeg) }) { Text("$name↺") }
                }
            }
            if (off != null) TextButton(onClick = { vm.resetPart(part) }) { Text("Voltar à posição do arquivo") }
        }
    }
}

// ---------- 2. Base ----------

@Composable
private fun BaseStep(a: RobotAssembly, vm: AssemblerViewModel) {
    Text("Onde a base fica no espaço 3D (em relação ao zero da grade).", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("X (mm)", a.baseX, Modifier.weight(1f)) { vm.setBase(x = it) }
        NumberField("Y (mm)", a.baseY, Modifier.weight(1f)) { vm.setBase(y = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Z (mm)", a.baseZ, Modifier.weight(1f)) { vm.setBase(z = it) }
        NumberField("Giro em Z (°)", a.baseRotDeg, Modifier.weight(1f)) { vm.setBase(rotDeg = it) }
    }
    TextButton(onClick = vm::baseToZero) { Text("Pôr a base no zero") }
    HorizontalDivider()
    Text("Frente do robô: para onde aponta o X do robô (o do WHERE), no arquivo.", style = MaterialTheme.typography.bodyMedium)
    Text("No STEP do KJ264 o braço aponta para +Y.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in RobotFront.entries) FilterChip(selected = a.front == f, onClick = { vm.setFront(f) }, label = { Text(f.label) })
    }
}

// ---------- 3. Eixos ----------

@Composable
private fun AxesStep(
    a: RobotAssembly,
    editing: Int?,
    mode: PickMode,
    lastGuess: AxisGuess?,
    angles: List<Double>,
    vm: AssemblerViewModel,
) {
    if (editing == null) {
        if (a.axisParts.isEmpty()) {
            Text("Nenhuma peça marcada como eixo: volte em Peças.", color = MaterialTheme.colorScheme.error)
            return
        }
        Text("Marque os eixos na ordem, da base para a ponta. Toque num eixo para marcar ou corrigir.",
            style = MaterialTheme.typography.bodyMedium)
        for ((n, part) in a.axisParts) {
            val def = a.axes[n]
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { vm.editAxis(n) },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Eixo $n", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
                        Text(part, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (def != null) "marcado" else "falta",
                            color = if (def != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    if (def != null) Text(describe(def), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (a.definedAxisCount > 0) {
            OutlinedButton(onClick = { vm.setStep(AssemblerStep.TESTAR) }) { Text("Testar todos") }
        }
        return
    }

    val part = a.partOf(editing)
    val parent = a.parentOf(editing)
    val def = a.axes[editing]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Eixo $editing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.editAxis(null) }) { Text("Pronto") }
    }
    Text("Liga ${parent ?: "a base"} a ${part ?: "?"}.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    PickModeRow(mode, vm::setPickMode)
    if (def == null) {
        Text("Toque na face redonda da junta (o encaixe, um furo ou a tampa).", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(describe(def), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    lastGuess?.takeIf { it.kind != AxisGuess.Kind.DOIS_PONTOS }?.let {
        Text(String.format(Locale.US, "Face: %s, raio %.1f mm, foge %.2f mm do círculo", it.kind.label, it.radiusMm, it.errorMm),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.invertAxis(editing) }) { Text("Inverter sentido") }
        TextButton(onClick = { vm.clearAxis(editing) }) { Text("Limpar") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Mínimo (°)", def.minDeg, Modifier.weight(1f)) { vm.setLimits(editing, it, null) }
        NumberField("Máximo (°)", def.maxDeg, Modifier.weight(1f)) { vm.setLimits(editing, null, it) }
    }
    NumberField("Ângulo do eixo na pose do arquivo (°)", def.zeroDeg, Modifier.fillMaxWidth()) { vm.setZero(editing, it) }
    Text("0 se o CAD veio com este eixo no zero. Senão, o ângulo que o controlador mostraria nessa pose.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // testar só este eixo (os anteriores precisam estar marcados para ele mexer)
    val model = a.model()
    if (model != null && editing <= model.axisCount) {
        val j = model.joints[editing - 1]
        Text("Testar: o sentido positivo deve bater com o do robô (JOG +).", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = angles[editing - 1].toFloat(),
                onValueChange = { vm.setAngle(editing - 1, it.toDouble()) },
                valueRange = j.minDeg.toFloat()..j.maxDeg.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(String.format(Locale.US, "%7.1f°", angles[editing - 1]), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    } else if (model == null || editing > model.axisCount) {
        Text("Para testar, marque antes os eixos de 1 a ${editing - 1}.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickModeRow(mode: PickMode, onMode: (PickMode) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in PickMode.entries) FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
    }
}

private fun describe(def: AxisDef): String = String.format(
    Locale.US, "ponto (%.1f, %.1f, %.1f)  dir (%.3f, %.3f, %.3f)  %.0f°…%.0f°",
    def.point.x, def.point.y, def.point.z, def.direction.x, def.direction.y, def.direction.z, def.minDeg, def.maxDeg,
)

// ---------- 4. Flange ----------

@Composable
private fun FlangeStep(a: RobotAssembly, mode: PickMode, vm: AssemblerViewModel) {
    val last = a.axisParts.lastOrNull()
    if (last == null || a.definedAxisCount == 0) {
        Text("Marque os eixos antes do flange.", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("O flange é onde a ferramenta se prende, na ponta de ${last.second}. Toque na face do flange: o eixo dele sai para fora.",
        style = MaterialTheme.typography.bodyMedium)
    PickModeRow(mode, vm::setPickMode)
    val f = a.flange
    if (f == null) {
        Text("Sem flange marcado: vale o centro do eixo ${a.definedAxisCount}.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(describe(f).substringBefore("  ${String.format(Locale.US, "%.0f", f.minDeg)}"),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::invertFlange) { Text("Inverter") }
            TextButton(onClick = vm::clearFlange) { Text("Usar o eixo ${a.definedAxisCount}") }
        }
    }
    HorizontalDivider()
    Text("TOOL do controlador (do flange até a ponta da ferramenta)", style = MaterialTheme.typography.titleSmall)
    Text("Os mesmos valores do TOOL no robô. As setas pequenas mostram o TCP.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val t = a.tool
    val values = listOf(t?.x, t?.y, t?.z, t?.o, t?.a, t?.t).map { it ?: 0.0 }
    val labels = listOf("X (mm)", "Y (mm)", "Z (mm)", "O (°)", "A (°)", "T (°)")
    // X e O, Y e A, Z e T lado a lado (dois por linha, para caber o ±)
    for (row in 0 until 3) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in 0 until 2) {
                val i = row + k * 3
                NumberField(labels[i], values[i], Modifier.weight(1f)) { vm.setToolField(i, it) }
            }
        }
    }
    if (t != null) TextButton(onClick = vm::clearTool) { Text("Sem TOOL (TCP no flange)") }
}

// ---------- 5. Testar ----------

@Composable
private fun TestStep(a: RobotAssembly, angles: List<Double>, selected: String?, vm: AssemblerViewModel, onColor: (String) -> Unit) {
    val model = a.model()
    if (model == null) {
        Text("Ainda não dá para testar: marque a base e o eixo 1.", color = MaterialTheme.colorScheme.error)
        return
    }
    if (model.axisCount < a.axisParts.size) {
        Text("Marcados ${model.axisCount} de ${a.axisParts.size} eixos: os outros vão junto com o eixo ${model.axisCount}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AxisSliders(
        joints = model.joints,
        angles = angles.take(model.axisCount),
        tcp = vm.tcpPose()?.format() ?: "",
        onChange = vm::setAngle,
        onZero = vm::zeroAll,
    )
    if (selected != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(a.colors[selected]) { onColor(selected) }
            Spacer(Modifier.width(10.dp))
            Text("Cor de $selected", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Text("Toque numa peça para mudar a cor dela.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 6. Programa de teste ----------

@Composable
private fun ProgramStep(a: RobotAssembly, angles: List<Double>, running: Boolean, target: Int?, vm: AssemblerViewModel) {
    val model = a.model()
    if (model == null) {
        Text("Ainda não dá para testar: marque a base e o eixo 1.", color = MaterialTheme.colorScheme.error)
        return
    }
    val prog = a.program
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::addPoint, enabled = !running) { Text("Adicionar ponto") }
        if (running) {
            Button(onClick = vm::stopProgram, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Parar")
            }
        } else {
            Button(onClick = vm::runProgram, enabled = prog.points.size >= 2) { Text("Executar em loop") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.US, "Velocidade %.0f °/s", prog.speedDegS), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp))
        Slider(
            value = prog.speedDegS.toFloat(), onValueChange = { vm.setSpeed(it.toDouble()) },
            valueRange = 5f..180f, modifier = Modifier.weight(1f),
        )
    }
    NumberField("Pausa em cada ponto (s)", prog.pauseS, Modifier.fillMaxWidth()) { vm.setPause(it) }
    if (prog.points.isEmpty()) {
        Text("Mexa os eixos abaixo até a posição e toque em Adicionar ponto. Com 2 ou mais, Executar repete a sequência.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    prog.points.forEachIndexed { i, pt ->
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (i == target) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pt.name, style = MaterialTheme.typography.titleSmall)
                    Text(pt.angles.joinToString(" ") { String.format(Locale.US, "%.1f", it) },
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.goToPoint(i) }, enabled = !running) { Icon(Icons.Rounded.PlayArrow, "Ir para ${pt.name}") }
                IconButton(onClick = { vm.updatePoint(i) }, enabled = !running) { Icon(Icons.Rounded.Refresh, "Atualizar ${pt.name} com a posição atual") }
                IconButton(onClick = { vm.movePoint(i, -1) }, enabled = !running && i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir") }
                IconButton(onClick = { vm.movePoint(i, 1) }, enabled = !running && i < prog.points.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Descer") }
                IconButton(onClick = { vm.deletePoint(i) }) { Icon(Icons.Rounded.Delete, "Apagar ${pt.name}") }
            }
        }
    }
    if (!running) {
        HorizontalDivider()
        AxisSliders(
            joints = model.joints,
            angles = angles.take(model.axisCount),
            tcp = vm.tcpPose()?.format() ?: "",
            onChange = vm::setAngle,
            onZero = vm::zeroAll,
        )
    } else {
        Text("TCP  ${vm.tcpPose()?.format() ?: ""}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

// ---------- cores ----------

/** Cores prontas para as peças. */
private val PALETTE = listOf(
    0xF2F2F0, 0xC8C8C4, 0x8A8C90, 0x3A3C40, 0x151618, 0xF26B1D,
    0xF5C400, 0xC8102E, 0x1F5AA6, 0x4FA3E0, 0x2E8B57, 0x9ACD32,
)

private fun composeColor(rgb: Int) = Color(0xFF000000.toInt() or rgb)

/** Bolinha com a cor da peça; tracejada (contorno) quando é a cor do arquivo. */
@Composable
private fun ColorDot(rgb: Int?, size: androidx.compose.ui.unit.Dp = 22.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (rgb != null) composeColor(rgb) else Color.Transparent)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ColorDialog(part: String, current: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf(current?.let { RobotAssembly.colorHex(it) } ?: "#") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cor da peça") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(part, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (row in PALETTE.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in row) ColorDot(c, 34.dp) { onPick(c) }
                    }
                }
                OutlinedTextField(
                    value = hex, onValueChange = { hex = it }, singleLine = true,
                    label = { Text("Cor livre (#RRGGBB)") },
                    trailingIcon = {
                        RobotAssembly.parseColor(hex)?.let { c -> ColorDot(c, 24.dp) { onPick(c) } }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { RobotAssembly.parseColor(hex)?.let(onPick) }, enabled = RobotAssembly.parseColor(hex) != null) {
                Text("Usar")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Cor do arquivo") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

// ---------- campos ----------

@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    OutlinedTextField(
        value = name, onValueChange = onName, singleLine = true,
        label = { Text("Nome do robô") }, modifier = Modifier.fillMaxWidth(),
    )
}

/** Campo de número que aceita vírgula; só avisa quando o texto vira um número. */
@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(format(value)) }
    // o valor mudou por fora (ex.: "Pôr no zero"): mostra o novo
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = format(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValue)
        },
        singleLine = true,
        label = { Text(label) },
        // o teclado numérico de alguns aparelhos (Samsung) não tem o sinal de menos: ± troca o sinal
        trailingIcon = {
            TextButton(onClick = {
                val t = text.trim()
                text = if (t.startsWith("-")) t.removePrefix("-") else "-$t"
                text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValue)
            }) { Text("±", style = MaterialTheme.typography.titleMedium) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun format(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
