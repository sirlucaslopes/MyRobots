package my.robots.feature.robot3d

import my.robots.core.render3d.FilamentViewer
import my.robots.core.render3d.GlbBuilder
import my.robots.core.render3d.GlbReader
import my.robots.core.render3d.OrbitCamera
import android.net.Uri
import android.provider.OpenableColumns
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PrecisionManufacturing
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.robots.core.designsystem.AppTopBar
import my.robots.core.designsystem.BarAction
import my.robots.core.kinematics.Joint
import java.util.Locale

/** Maior .glb aceito (o arquivo inteiro vai para a memória). */
private const val MAX_GLB_BYTES = 150L * 1024 * 1024

/**
 * "Visualizador 3D (teste)": primeiro passo do 3D (Plano Mestre, F3). Mostra o robô de teste
 * montado pela cinemática, com um controle por eixo, ou um .glb aberto pelo seletor do Android.
 * Um dedo gira, pinça aproxima, dois dedos arrastam; Vistas tem Iso, Topo, Frente e Lado.
 * Num .glb aberto, tocar numa peça realça a peça e mostra o nome dela.
 */
@Composable
fun Robot3dScreen(viewModel: Robot3dViewModel, onBack: () -> Unit, onOpenAssembler: () -> Unit) {
    val angles by viewModel.angles.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val selectedPart by viewModel.selectedPart.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<FilamentViewer?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val openGlb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { readGlb(context, uri) }
            loading = false
            result.fold(
                onSuccess = { viewModel.openFile(it) },
                onFailure = { snackbar.showSnackbar(it.message ?: "Não deu para ler o arquivo.") },
            )
        }
    }

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

    // o que aparece: o robô de teste ou o arquivo aberto
    LaunchedEffect(viewer, opened) {
        val v = viewer ?: return@LaunchedEffect
        val file = opened
        if (file == null) {
            v.showTestRobot()
        } else {
            val result = v.showGlb(file.bytes, file.parts?.parts?.map { it.name }.orEmpty())
            if (!result.shown) viewModel.closeFile()
            result.message?.let { snackbar.showSnackbar(it) }
        }
    }

    LaunchedEffect(viewer, selectedPart) { viewer?.highlight(selectedPart) }

    // move o robô na hora em que o controle mexe; setas no sistema do robô e no TCP
    LaunchedEffect(viewer, angles, opened) {
        val v = viewer ?: return@LaunchedEffect
        val model = viewModel.model
        val deg = angles.toDoubleArray()
        v.setPartTransforms(model.partTransforms(deg))
        if (opened == null) v.setFrames(model.placement * model.robotFrame, model.tcpInWorld(deg)) else v.setFrames(null, null)
    }

    val file = opened
    Scaffold(
        topBar = {
            AppTopBar(
                title = "Visualizador 3D (teste)",
                subtitle = file?.name ?: "Robô de teste · ${viewModel.model.axisCount} eixos",
                onBack = onBack,
                menu = { close ->
                    DropdownMenuItem(
                        text = { Text("Montador de robô") },
                        leadingIcon = { Icon(Icons.Rounded.Build, contentDescription = null) },
                        onClick = { close(); onOpenAssembler() },
                    )
                },
                actions = listOf(
                    BarAction(Icons.Rounded.FolderOpen, "Abrir .glb", enabled = !loading && viewer != null, onClick = {
                        openGlb.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
                    }),
                    BarAction(
                        Icons.Rounded.PrecisionManufacturing, "Robô teste",
                        selected = file == null,
                        onClick = viewModel::closeFile,
                    ),
                    viewsAction { viewer },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            viewer = FilamentViewer(sv, onFps = { fps = it }).apply {
                                setTestRobot(viewModel.testRobotGlb, viewModel.partNames)
                                onTap = { x, y ->
                                    val parts = viewModel.opened.value?.parts
                                    if (parts != null) {
                                        val (o, d) = pickRay(x, y)
                                        // 170 mil triângulos: fora da thread da tela
                                        scope.launch {
                                            val hit = withContext(Dispatchers.Default) { parts.pick(o, d, emptyMap()) }
                                            viewModel.selectPart(hit?.part)
                                        }
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
                Text(
                    if (fps > 0) "$fps quadros/s" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x66000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (file != null) {
                    Text(
                        selectedPart?.let { "Peça: $it" } ?: "Toque numa peça para ver o nome",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                            .background(Color(0x66000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (loading) {
                    Text(
                        "Lendo o arquivo…",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            if (file == null) {
                HorizontalDivider()
                AxisSliders(
                    joints = viewModel.model.joints,
                    angles = angles,
                    tcp = remember(angles) { viewModel.tcpPose(angles).format() },
                    onChange = viewModel::setAngle,
                    onZero = viewModel::zeroAll,
                )
            }
        }
    }
}

/** Um controle por eixo, dentro dos limites, e a posição do TCP (como o `WHERE`). */
@Composable
internal fun AxisSliders(
    joints: List<Joint>,
    angles: List<Double>,
    tcp: String,
    onChange: (Int, Double) -> Unit,
    onZero: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TCP  $tcp",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onZero) { Text("Zerar") }
        }
        joints.forEachIndexed { i, j ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("JT${j.number}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
                Slider(
                    value = angles[i].toFloat(),
                    onValueChange = { onChange(i, it.toDouble()) },
                    valueRange = j.minDeg.toFloat()..j.maxDeg.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    String.format(Locale.US, "%7.1f°", angles[i]),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/** Ação "Vistas" (Iso, Topo, Frente, Lado), igual no visualizador e no montador. */
internal fun viewsAction(viewer: () -> FilamentViewer?) = BarAction(
    Icons.Rounded.ViewInAr, "Vistas",
    menu = { close ->
        for ((preset, label) in listOf(
            OrbitCamera.Preset.ISO to "Isométrica",
            OrbitCamera.Preset.TOPO to "Topo",
            OrbitCamera.Preset.FRENTE to "Frente",
            OrbitCamera.Preset.LADO to "Lado",
        )) {
            DropdownMenuItem(text = { Text(label) }, onClick = { close(); viewer()?.camera?.apply(preset) })
        }
    },
)

/** Lê o .glb escolhido no seletor (SAF): nome, bytes e as peças, com limite de tamanho. */
internal fun readGlb(context: android.content.Context, uri: Uri): Result<Robot3dViewModel.OpenedFile> = runCatching {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment ?: "modelo.glb"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size > MAX_GLB_BYTES) error("Arquivo grande demais (${size / (1024 * 1024)} MB; o limite é ${MAX_GLB_BYTES / (1024 * 1024)} MB).")
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_GLB_BYTES) error("Arquivo grande demais (o limite é ${MAX_GLB_BYTES / (1024 * 1024)} MB).")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: error("Não deu para abrir o arquivo.")
    if (!GlbBuilder.isGlb(bytes)) error("O arquivo não é um .glb (glTF binário). Arquivos .gltf com .bin separado ainda não abrem.")
    // as peças só servem para tocar: se não der para ler, o desenho abre mesmo assim
    Robot3dViewModel.OpenedFile(name, bytes, runCatching { GlbReader.read(bytes) }.getOrNull())
}
