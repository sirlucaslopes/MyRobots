package my.robots.feature.robot3d

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Tudo o que é do Filament nesta tela: motor, cena, câmera, luzes, os modelos e o laço de quadros.
 *
 * Roda inteiro na thread principal (o Filament não aceita ser chamado de várias threads).
 * Quem cria chama [destroy] ao sair da tela: libera os modelos, os materiais e o motor.
 * Os quadros só correm entre [resume] e [pause].
 */
class FilamentViewer(
    private val surfaceView: SurfaceView,
    /** Quadros desenhados no último segundo (para conferir os 60 por segundo). */
    private val onFps: (Int) -> Unit = {},
) {
    val camera = OrbitCamera()

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val filamentCamera: Camera
    private val cameraEntity: Int
    private val sunEntity: Int
    private val indirectLight: IndirectLight
    private val skybox: Skybox
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(surfaceView.context)
    private var swapChain: SwapChain? = null

    private val materialProvider: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader

    private var scenery: FilamentAsset? = null
    private var robot: FilamentAsset? = null
    private var robotVisible = false
    private var userModel: FilamentAsset? = null
    private var markers: FilamentAsset? = null

    /** Sistemas do robô e do TCP (setas X/Y/Z): entidade e se está na cena. */
    private var frames: FilamentAsset? = null
    private var robotFrameEntity = 0
    private var tcpFrameEntity = 0
    private val framesShown = HashSet<Int>()

    /** Letras X, Y e Z da origem (declaradas antes do init, que as carrega). */
    private var legend: FilamentAsset? = null
    private var legendEntities = IntArray(0)
    private val legendDirs = listOf(Vec3.X, Vec3.Y, Vec3.Z)

    /** Peça do robô → entidade do nó no Filament. */
    private val robotParts = HashMap<String, Int>()
    private val matrix = FloatArray(16)

    /**
     * Peça do arquivo aberto: o nó, a posição do pai e a do nó como vieram no arquivo (metros),
     * e as entidades que desenham (o nó e os filhos).
     */
    private class UserPart(val entity: Int, val parentWorld: Mat4, val parentWorldInv: Mat4, val local0: Mat4, val renderables: IntArray)

    private val userParts = HashMap<String, UserPart>()
    private val hiddenParts = HashSet<String>()

    /** Peça realçada e os materiais trocados nela (originais, para devolver). */
    private var highlighted: String? = null
    private val highlightSwaps = ArrayList<Triple<Int, Int, MaterialInstance>>() // (renderable, primitiva, original)
    private val highlightCopies = ArrayList<MaterialInstance>()

    /** Toque curto (sem arrastar) na tela, em pixels. */
    var onTap: ((Float, Float) -> Unit)? = null

    private var destroyed = false
    private var running = false
    private var viewWidth = 1
    private var viewHeight = 1
    private var aspect = 1.0
    private var lastProjectionDistance = -1.0

    private val choreographer = Choreographer.getInstance()
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    init {
        ensureNativeLoaded()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        filamentCamera = engine.createCamera(cameraEntity)
        view.scene = scene
        view.camera = filamentCamera
        // bordas lisas nas linhas finas da grade; 4x é barato nas GPUs de celular
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }

        skybox = Skybox.Builder().color(0.035f, 0.040f, 0.050f, 1f).build(engine)
        scene.skybox = skybox
        // luz ambiente uniforme (sem mapa de ambiente) + um "sol" vindo de cima, de frente-direita
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.65f, 0.66f, 0.70f))
            .intensity(30_000f)
            .build(engine)
        scene.indirectLight = indirectLight
        sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.98f, 0.95f)
            .intensity(90_000f)
            .direction(-0.4f, 0.5f, -0.77f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    // o Android pode destruir a superfície logo depois: espera o Filament largar
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                viewWidth = width.coerceAtLeast(1)
                viewHeight = height.coerceAtLeast(1)
                aspect = width.toDouble() / height.coerceAtLeast(1)
                lastProjectionDistance = -1.0
            }
        }
        uiHelper.attachTo(surfaceView)
        installGestures()

        scenery = loadAsset(SceneModels.sceneryGlb())?.also { scene.addEntities(it.entities) }
        legend = loadAsset(SceneModels.legendGlb())?.also { asset ->
            scene.addEntities(asset.entities)
            legendEntities = SceneModels.LEGEND_NODES.map { asset.getFirstEntityByName(it) }.toIntArray()
        }
        // os sistemas só entram na cena quando alguém diz onde ficam (setFrames)
        frames = loadAsset(SceneModels.framesGlb())?.also { asset ->
            robotFrameEntity = asset.getFirstEntityByName(SceneModels.FRAME_ROBOT)
            tcpFrameEntity = asset.getFirstEntityByName(SceneModels.FRAME_TCP)
        }
    }

    /**
     * Mostra o sistema do robô ([robot]: a base, no zero ou fora dele) e o do TCP ([tcp]) com as
     * setas X/Y/Z, nas poses dadas (mm, espaço do app). null esconde.
     */
    fun setFrames(robot: Transform?, tcp: Transform?) {
        val tm = engine.transformManager
        for ((entity, pose) in listOf(robotFrameEntity to robot, tcpFrameEntity to tcp)) {
            if (entity == 0) continue
            if (pose == null) {
                if (framesShown.remove(entity)) scene.removeEntity(entity)
                continue
            }
            tm.setTransform(tm.getInstance(entity), SceneModels.toFilamentMatrix(pose, matrix))
            if (framesShown.add(entity)) scene.addEntity(entity)
        }
    }

    /**
     * Põe as letras na ponta das setas, viradas para a câmera e com tamanho fixo na tela
     * (o glb tem as letras com 1 m de altura no plano XY).
     */
    private fun updateLegend() {
        if (legendEntities.isEmpty()) return
        val (right, up) = camera.screenAxes()
        val back = right.cross(up)
        val s = (camera.distance * 0.035).coerceIn(0.02, 20.0)
        val tm = engine.transformManager
        val arrow = SceneModels.ORIGIN_ARROW_MM * MeshData.MM
        for ((i, e) in legendEntities.withIndex()) {
            if (e == 0) continue
            val c = legendDirs[i] * (arrow + s * 0.8)
            matrix[0] = (right.x * s).toFloat(); matrix[1] = (right.y * s).toFloat(); matrix[2] = (right.z * s).toFloat(); matrix[3] = 0f
            matrix[4] = (up.x * s).toFloat(); matrix[5] = (up.y * s).toFloat(); matrix[6] = (up.z * s).toFloat(); matrix[7] = 0f
            matrix[8] = (back.x * s).toFloat(); matrix[9] = (back.y * s).toFloat(); matrix[10] = (back.z * s).toFloat(); matrix[11] = 0f
            matrix[12] = c.x.toFloat(); matrix[13] = c.y.toFloat(); matrix[14] = c.z.toFloat(); matrix[15] = 1f
            tm.setTransform(tm.getInstance(e), matrix)
        }
    }

    // ---------- robô de teste ----------

    /** Carrega o robô de teste (gerado em código). Os nós têm o nome das peças. */
    fun setTestRobot(glb: ByteArray, partNames: Collection<String>) {
        robot?.let { removeAsset(it) }
        robotParts.clear()
        robot = loadAsset(glb)?.also { asset ->
            for (name in partNames) {
                val e = asset.getFirstEntityByName(name)
                if (e != 0) robotParts[name] = e
            }
            if (robotVisible) scene.addEntities(asset.entities)
        }
    }

    /** Põe cada peça na posição do `RobotModel.partTransforms` (mm, Z para cima). */
    fun setPartTransforms(parts: Map<String, Transform>) {
        val tm = engine.transformManager
        for ((name, t) in parts) {
            val entity = robotParts[name] ?: continue
            tm.setTransform(tm.getInstance(entity), SceneModels.toFilamentMatrix(t, matrix))
        }
    }

    // ---------- peças do arquivo aberto ----------

    /** Nomes das peças do arquivo aberto que o desenho consegue mover. */
    val userPartNames: Set<String> get() = userParts.keys

    /**
     * Move as peças do arquivo aberto: cada [Transform] (mm, espaço do app) diz onde a peça fica
     * em relação à pose em que veio no arquivo. Peça que não está no mapa volta para a pose do arquivo.
     */
    fun setUserPoses(poses: Map<String, Transform>) {
        val tm = engine.transformManager
        for ((name, part) in userParts) {
            val pose = poses[name]
            val local = if (pose == null) part.local0 else {
                val d = Mat4.of(SceneModels.toFilamentMatrix(pose, matrix))
                part.parentWorldInv * d * part.parentWorld * part.local0
            }
            tm.setTransform(tm.getInstance(part.entity), local.toFloats(matrix))
        }
    }

    /** Peças escondidas agora (o toque passa por elas). */
    val hiddenPartNames: Set<String> get() = hiddenParts.toSet()

    /** Esconde as peças de [names] e mostra as outras (o "Isolar" do montador). */
    fun setHiddenParts(names: Set<String>) {
        for ((name, part) in userParts) {
            val hide = name in names
            if (hide == (name in hiddenParts)) continue
            if (hide) part.renderables.forEach { scene.removeEntity(it) } else scene.addEntities(part.renderables)
        }
        hiddenParts.clear()
        hiddenParts += names.filter { it in userParts }
    }

    /** Realça uma peça (do arquivo aberto ou do robô de teste) com cor laranja; null tira o realce. */
    fun highlight(name: String?) {
        if (name == highlighted) return
        clearHighlight()
        if (name == null) return
        val renderables = userParts[name]?.renderables
            ?: robotParts[name]?.let { renderablesUnder(it) }
            ?: return
        val rm = engine.renderableManager
        for (e in renderables) {
            val inst = rm.getInstance(e)
            for (p in 0 until rm.getPrimitiveCount(inst)) {
                val original = rm.getMaterialInstanceAt(inst, p)
                val copy = MaterialInstance.duplicate(original, "realce")
                copy.setParameter("baseColorFactor", Colors.RgbaType.SRGB, 1f, 0.55f, 0.10f, 1f)
                rm.setMaterialInstanceAt(inst, p, copy)
                highlightSwaps += Triple(inst, p, original)
                highlightCopies += copy
            }
        }
        highlighted = name
    }

    // cores escolhidas: materiais originais trocados por cópias coloridas
    private val colorSwaps = ArrayList<Triple<Int, Int, MaterialInstance>>()
    private val colorCopies = ArrayList<MaterialInstance>()
    private var appliedColors: Map<String, Int> = emptyMap()

    /**
     * Pinta as peças do arquivo aberto (0xRRGGBB, sRGB). Peça fora do mapa volta à cor do arquivo.
     * Fica por baixo do realce: a peça tocada continua laranja e volta à cor escolhida depois.
     */
    fun setPartColors(colors: Map<String, Int>) {
        if (colors == appliedColors) return
        val h = highlighted
        clearHighlight()
        clearColors()
        val rm = engine.renderableManager
        for ((name, rgb) in colors) {
            val part = userParts[name] ?: continue
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            for (e in part.renderables) {
                val inst = rm.getInstance(e)
                for (p in 0 until rm.getPrimitiveCount(inst)) {
                    val original = rm.getMaterialInstanceAt(inst, p)
                    val copy = MaterialInstance.duplicate(original, "cor")
                    copy.setParameter("baseColorFactor", Colors.RgbaType.SRGB, r, g, b, 1f)
                    rm.setMaterialInstanceAt(inst, p, copy)
                    colorSwaps += Triple(inst, p, original)
                    colorCopies += copy
                }
            }
        }
        appliedColors = colors
        highlight(h)
    }

    private fun clearColors() {
        val rm = engine.renderableManager
        for ((inst, p, original) in colorSwaps) rm.setMaterialInstanceAt(inst, p, original)
        colorSwaps.clear()
        colorCopies.forEach { engine.destroyMaterialInstance(it) }
        colorCopies.clear()
        appliedColors = emptyMap()
    }

    private fun clearHighlight() {
        val rm = engine.renderableManager
        for ((inst, p, original) in highlightSwaps) rm.setMaterialInstanceAt(inst, p, original)
        highlightSwaps.clear()
        highlightCopies.forEach { engine.destroyMaterialInstance(it) }
        highlightCopies.clear()
        highlighted = null
    }

    /** O nó e os filhos dele que desenham alguma coisa. */
    private fun renderablesUnder(entity: Int): IntArray {
        val tm = engine.transformManager
        val rm = engine.renderableManager
        val out = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        stack += entity
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            if (rm.hasComponent(e)) out += e
            val inst = tm.getInstance(e)
            val n = tm.getChildCount(inst)
            if (n > 0) tm.getChildren(inst, IntArray(n)).forEach { stack += it }
        }
        return out.toIntArray()
    }

    /** Raio do toque em ([xPx], [yPx]), em mm no espaço do app. */
    fun pickRay(xPx: Float, yPx: Float): Pair<Vec3, Vec3> {
        val (o, d) = camera.ray(xPx, yPx, viewWidth, viewHeight)
        return o * 1000.0 to d
    }

    /**
     * Marcas do montador (eixo, pontos tocados): um .glb gerado em código, já com Z para cima.
     * null tira as marcas.
     */
    fun setMarkers(glb: ByteArray?) {
        markers?.let { removeAsset(it) }
        markers = glb?.let { loadAsset(it) }?.also { scene.addEntities(it.entities) }
    }

    // ---------- o que aparece ----------

    /** Mostra o robô de teste e tira o arquivo aberto (se houver). */
    fun showTestRobot() {
        userModel?.let {
            clearUserModel()
            // a câmera estava enquadrando o arquivo: volta para o robô
            camera.apply(OrbitCamera.Preset.ISO)
            camera.frame(ROBOT_CENTER, ROBOT_RADIUS)
        }
        if (!robotVisible) robot?.let { scene.addEntities(it.entities) }
        robotVisible = true
    }

    /** Resultado de abrir um .glb: [shown] = está na tela; [message] = aviso ou erro para mostrar. */
    data class GlbResult(val shown: Boolean, val message: String? = null)

    /**
     * Abre um .glb do usuário no lugar do robô de teste. Converte Y para cima (glTF) em Z para
     * cima e enquadra a câmera. [partNames]: os nós que são peças (do [GlbReader]), para poder
     * mover, esconder e realçar cada uma.
     */
    fun showGlb(bytes: ByteArray, partNames: Collection<String> = emptyList()): GlbResult {
        if (!GlbBuilder.isGlb(bytes)) return GlbResult(false, "O arquivo não é um .glb (glTF binário).")
        val asset = loadAsset(bytes)
            ?: return GlbResult(false, "Não deu para ler o arquivo (.glb inválido ou com recurso não suportado).")
        val missing = asset.resourceUris.filter { it.isNotBlank() }
        clearUserModel()
        clearHighlight()
        if (robotVisible) robot?.let { scene.removeEntities(it.entities) }
        robotVisible = false
        userModel = asset

        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), SceneModels.toFilamentMatrix(SceneModels.GLTF_TO_Z_UP, matrix))
        scene.addEntities(asset.entities)
        for (name in partNames) {
            val e = asset.getFirstEntityByName(name)
            if (e == 0) continue
            val inst = tm.getInstance(e)
            val parent = tm.getParent(inst)
            val parentWorld = if (parent == 0) Mat4() else Mat4.of(tm.getWorldTransform(tm.getInstance(parent), FloatArray(16)))
            val local0 = Mat4.of(tm.getTransform(inst, FloatArray(16)))
            userParts[name] = UserPart(e, parentWorld, parentWorld.inverse(), local0, renderablesUnder(e))
        }

        val box = asset.boundingBox
        val c = box.center
        val h = box.halfExtent
        val center = SceneModels.gltfToZUp(Vec3(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()))
        val radius = sqrt((h[0] * h[0] + h[1] * h[1] + h[2] * h[2]).toDouble())
        camera.apply(OrbitCamera.Preset.ISO)
        camera.frame(center, radius)
        return GlbResult(true, if (missing.isNotEmpty()) "Faltam arquivos externos: ${missing.joinToString()}" else null)
    }

    // ---------- laço de quadros ----------

    fun resume() {
        if (destroyed || running) return
        running = true
        fpsWindowStart = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || destroyed) return
            choreographer.postFrameCallback(this)
            renderFrame(frameTimeNanos)
        }
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        updateCamera()
        updateLegend()
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            countFrame(frameTimeNanos)
        }
    }

    private fun updateCamera() {
        val d = camera.distance
        if (d != lastProjectionDistance) {
            // perto e longe acompanham a distância: dá para ver de um punho a uma linha inteira
            filamentCamera.setProjection(camera.fovDeg, aspect, (d * 0.005).coerceAtLeast(0.005), d * 50 + 50, Camera.Fov.VERTICAL)
            lastProjectionDistance = d
        }
        val e = camera.eye
        val t = camera.target
        filamentCamera.lookAt(e.x, e.y, e.z, t.x, t.y, t.z, 0.0, 0.0, 1.0)
    }

    private fun countFrame(now: Long) {
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            fpsFrames = 0
            return
        }
        fpsFrames++
        if (now - fpsWindowStart >= 1_000_000_000L) {
            onFps(fpsFrames)
            fpsFrames = 0
            fpsWindowStart = now
        }
    }

    // ---------- gestos ----------

    /** Um dedo gira, pinça aproxima, dois dedos arrastam. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        val scaleDetector = ScaleGestureDetector(surfaceView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoom(detector.scaleFactor)
                return true
            }
        })
        var lastX = 0f
        var lastY = 0f
        var lastCount = 0
        // toque curto: um dedo, sem passar do "slop" do Android e sem segurar
        val slop = android.view.ViewConfiguration.get(surfaceView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var tapPossible = false
        surfaceView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; downTime = event.eventTime; tapPossible = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> tapPossible = false
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(event.x - downX, event.y - downY) > slop) tapPossible = false
                MotionEvent.ACTION_UP -> if (tapPossible && event.eventTime - downTime < TAP_MS) onTap?.invoke(event.x, event.y)
            }
            val count = event.pointerCount
            var cx = 0f
            var cy = 0f
            for (i in 0 until count) {
                cx += event.getX(i); cy += event.getY(i)
            }
            cx /= count; cy /= count
            // ao pôr ou tirar um dedo, o centro pula: recomeça a medir sem mexer a câmera
            val pointerChanged = count != lastCount
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> if (!pointerChanged) {
                    val dx = cx - lastX
                    val dy = cy - lastY
                    if (count == 1) camera.orbit(dx, dy) else camera.pan(dx, dy, viewHeight)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            // no POINTER_UP/DOWN o centro ainda conta o dedo que saiu: o próximo MOVE só recomeça
            val masked = event.actionMasked
            lastCount = if (masked == MotionEvent.ACTION_POINTER_UP || masked == MotionEvent.ACTION_POINTER_DOWN) -1 else count
            lastX = cx
            lastY = cy
            true
        }
    }

    // ---------- modelos ----------

    private fun loadAsset(bytes: ByteArray): FilamentAsset? {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        val asset = assetLoader.createAsset(buffer) ?: return null
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        return asset
    }

    private fun removeAsset(asset: FilamentAsset) {
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
    }

    private fun clearUserModel() {
        val asset = userModel ?: return
        // o realce pode estar numa peça do arquivo: devolve os materiais antes de destruir
        if (highlighted in userParts) clearHighlight()
        clearColors()
        removeAsset(asset)
        userModel = null
        userParts.clear()
        hiddenParts.clear()
    }

    /** Libera tudo do Filament. Pode ser chamado mais de uma vez. */
    fun destroy() {
        if (destroyed) return
        pause()
        destroyed = true
        surfaceView.setOnTouchListener(null)
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null

        clearHighlight()
        clearUserModel()
        listOfNotNull(robot, scenery, markers, legend, frames).forEach { removeAsset(it) }
        robot = null; scenery = null; markers = null; legend = null; frames = null
        framesShown.clear()
        legendEntities = IntArray(0)
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        engine.destroyEntity(sunEntity)
        EntityManager.get().destroy(sunEntity)
        engine.destroyIndirectLight(indirectLight)
        engine.destroySkybox(skybox)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    companion object {
        /** Enquadramento do robô de teste (metros). */
        private val ROBOT_CENTER = Vec3(0.6, 0.0, 0.8)
        private const val ROBOT_RADIUS = 1.3

        /** Mais que isso segurando já não é toque. */
        private const val TAP_MS = 350L

        private var nativeLoaded = false

        /** Carrega as bibliotecas nativas do Filament, do gltfio e do utils (uma vez por processo). */
        private fun ensureNativeLoaded() {
            if (nativeLoaded) return
            Utils.init() // também inicia o Filament
            Gltfio.init()
            nativeLoaded = true
        }
    }
}
