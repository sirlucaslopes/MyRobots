package my.robots.feature.robot3d

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
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

    /** Peça do robô → entidade do nó no Filament. */
    private val robotParts = HashMap<String, Int>()
    private val matrix = FloatArray(16)

    private var destroyed = false
    private var running = false
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
                viewHeight = height.coerceAtLeast(1)
                aspect = width.toDouble() / height.coerceAtLeast(1)
                lastProjectionDistance = -1.0
            }
        }
        uiHelper.attachTo(surfaceView)
        installGestures()

        scenery = loadAsset(SceneModels.sceneryGlb())?.also { scene.addEntities(it.entities) }
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

    // ---------- o que aparece ----------

    /** Mostra o robô de teste e tira o arquivo aberto (se houver). */
    fun showTestRobot() {
        userModel?.let {
            removeAsset(it)
            // a câmera estava enquadrando o arquivo: volta para o robô
            camera.apply(OrbitCamera.Preset.ISO)
            camera.frame(ROBOT_CENTER, ROBOT_RADIUS)
        }
        userModel = null
        if (!robotVisible) robot?.let { scene.addEntities(it.entities) }
        robotVisible = true
    }

    /** Resultado de abrir um .glb: [shown] = está na tela; [message] = aviso ou erro para mostrar. */
    data class GlbResult(val shown: Boolean, val message: String? = null)

    /**
     * Abre um .glb do usuário no lugar do robô de teste. Converte Y para cima (glTF) em Z para
     * cima e enquadra a câmera.
     */
    fun showGlb(bytes: ByteArray): GlbResult {
        if (!GlbBuilder.isGlb(bytes)) return GlbResult(false, "O arquivo não é um .glb (glTF binário).")
        val asset = loadAsset(bytes)
            ?: return GlbResult(false, "Não deu para ler o arquivo (.glb inválido ou com recurso não suportado).")
        val missing = asset.resourceUris.filter { it.isNotBlank() }
        userModel?.let { removeAsset(it) }
        if (robotVisible) robot?.let { scene.removeEntities(it.entities) }
        robotVisible = false
        userModel = asset

        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), SceneModels.toFilamentMatrix(SceneModels.GLTF_TO_Z_UP, matrix))
        scene.addEntities(asset.entities)

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
        surfaceView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
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

    /** Libera tudo do Filament. Pode ser chamado mais de uma vez. */
    fun destroy() {
        if (destroyed) return
        pause()
        destroyed = true
        surfaceView.setOnTouchListener(null)
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null

        listOfNotNull(userModel, robot, scenery).forEach { removeAsset(it) }
        userModel = null; robot = null; scenery = null
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
