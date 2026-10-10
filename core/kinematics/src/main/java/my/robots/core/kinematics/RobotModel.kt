package my.robots.core.kinematics

/** O que cada peça do 3D é, como o usuário marca no montador ("dizer o que é cada peça"). */
enum class PartRole { BASE, AXIS, TOOL, OTHER }

/**
 * Um eixo do robô: liga a peça [parentPart] à peça [childPart] e gira em volta do eixo Z dos
 * sistemas [parentFrame] e [childFrame].
 *
 * - [parentFrame]: o eixo marcado na peça pai, nas coordenadas do arquivo da peça pai
 *   (origem num ponto do eixo, Z = sentido positivo).
 * - [childFrame]: o mesmo eixo marcado na peça filha, nas coordenadas do arquivo dela.
 *
 * Com o eixo marcado nas duas peças, elas se encaixam sozinhas: não importa em que posição cada
 * peça veio do CAD. Se todas vieram montadas no mesmo sistema, os dois sistemas são iguais.
 *
 * [zeroOffsetDeg] é somado ao ângulo do controlador antes de girar: serve para o "zero" do 3D
 * bater com o zero do robô.
 */
data class Joint(
    val number: Int,
    val parentPart: String,
    val childPart: String,
    val parentFrame: Transform,
    val childFrame: Transform,
    val minDeg: Double,
    val maxDeg: Double,
    val zeroOffsetDeg: Double = 0.0,
) {
    init {
        require(minDeg <= maxDeg) { "Eixo $number: limite mínimo maior que o máximo" }
    }

    /** Mesmo eixo com o sentido positivo invertido (gira Z de ponta-cabeça nos dois lados). */
    fun inverted(): Joint {
        val flip = Transform.rotation(Vec3.X, Math.PI)
        return copy(
            parentFrame = parentFrame * flip,
            childFrame = childFrame * flip,
            minDeg = -maxDeg,
            maxDeg = -minDeg,
            zeroOffsetDeg = -zeroOffsetDeg,
        )
    }

    fun clamp(deg: Double) = deg.coerceIn(minDeg, maxDeg)

    /** Onde a peça filha fica, no sistema da peça pai, com o eixo em [deg] graus. */
    fun childInParent(deg: Double): Transform =
        parentFrame * Transform.rotZ(Math.toRadians(deg + zeroOffsetDeg)) * childFrame.inverse()
}

/**
 * Robô montado: peças e eixos em cadeia, da base até o flange.
 *
 * - [placement]: onde a peça base fica no espaço 3D (em relação ao zero do espaço).
 * - [robotFrame]: o sistema de coordenadas do robô (o do `WHERE`), nas coordenadas da peça base.
 * - [flange]: o flange, nas coordenadas da última peça. [tool]: o TCP em relação ao flange,
 *   com os mesmos valores do TOOL do controlador.
 */
data class RobotModel(
    val name: String,
    val basePart: String,
    val joints: List<Joint>,
    val flange: Transform,
    val tool: Transform = Transform.IDENTITY,
    val robotFrame: Transform = Transform.IDENTITY,
    val placement: Transform = Transform.IDENTITY,
) {
    init {
        require(joints.isNotEmpty()) { "Robô sem eixos" }
        joints.forEachIndexed { i, j ->
            require(j.number == i + 1) { "Eixos fora de ordem: esperado ${i + 1}, veio ${j.number}" }
            val expectedParent = if (i == 0) basePart else joints[i - 1].childPart
            require(j.parentPart == expectedParent) {
                "Eixo ${j.number}: a peça pai devia ser $expectedParent, veio ${j.parentPart}"
            }
        }
    }

    val axisCount get() = joints.size
    val flangePart get() = joints.last().childPart

    fun clamp(deg: DoubleArray) = DoubleArray(deg.size) { joints[it].clamp(deg[it]) }

    fun withinLimits(deg: DoubleArray, toleranceDeg: Double = 1e-6) =
        deg.indices.all { deg[it] >= joints[it].minDeg - toleranceDeg && deg[it] <= joints[it].maxDeg + toleranceDeg }

    /**
     * Posição de cada peça no espaço 3D para os ângulos [deg] (um por eixo, em graus).
     * É o que o 3D usa para desenhar o robô.
     */
    fun partTransforms(deg: DoubleArray): Map<String, Transform> {
        checkSize(deg)
        val out = LinkedHashMap<String, Transform>()
        var current = placement
        out[basePart] = current
        for ((i, j) in joints.withIndex()) {
            current = current * j.childInParent(deg[i])
            out[j.childPart] = current
        }
        return out
    }

    /** Flange nas coordenadas da peça base. */
    fun flangeInBase(deg: DoubleArray): Transform {
        checkSize(deg)
        var current = Transform.IDENTITY
        for ((i, j) in joints.withIndex()) current = current * j.childInParent(deg[i])
        return current * flange
    }

    /** TCP no sistema do robô: o que o controlador mostra no `WHERE`. */
    fun tcp(deg: DoubleArray): Transform = robotFrame.inverse() * flangeInBase(deg) * tool

    fun tcpPose(deg: DoubleArray): KawasakiPose = KawasakiPose.fromTransform(tcp(deg))

    /** TCP no espaço 3D (para desenhar a ponta da ferramenta e as trajetórias). */
    fun tcpInWorld(deg: DoubleArray): Transform = placement * flangeInBase(deg) * tool

    /** Ponto do sistema do robô (ex.: um ponto de `.TRANS`) levado para o espaço 3D. */
    fun robotToWorld(t: Transform): Transform = placement * robotFrame * t

    private fun checkSize(deg: DoubleArray) =
        require(deg.size == joints.size) { "São ${joints.size} eixos e vieram ${deg.size} ângulos" }

    /**
     * Um eixo marcado com as peças já montadas no mesmo sistema (todas exportadas juntas).
     * [point] é um ponto da linha do eixo e [direction] o sentido positivo.
     * [angleInFileDeg] é o ângulo que o controlador mostraria na pose em que o arquivo veio
     * (0 se o arquivo está na posição zero).
     */
    data class AssembledAxis(
        val number: Int,
        val part: String,
        val point: Vec3,
        val direction: Vec3,
        val minDeg: Double,
        val maxDeg: Double,
        val angleInFileDeg: Double = 0.0,
    )

    companion object {


        /**
         * Monta o modelo quando todas as peças estão no mesmo sistema de coordenadas.
         * O sistema do robô fica no eixo 1 (origem no ponto marcado, Z no sentido do eixo),
         * até a validação com o `WHERE` ajustar.
         */
        fun assembled(
            name: String,
            basePart: String,
            axes: List<AssembledAxis>,
            flange: Transform,
            tool: Transform = Transform.IDENTITY,
            robotFrame: Transform? = null,
            placement: Transform = Transform.IDENTITY,
        ): RobotModel {
            val sorted = axes.sortedBy { it.number }
            val joints = sorted.mapIndexed { i, a ->
                val frame = Transform.fromAxis(a.point, a.direction)
                Joint(
                    number = a.number,
                    parentPart = if (i == 0) basePart else sorted[i - 1].part,
                    childPart = a.part,
                    parentFrame = frame,
                    childFrame = frame,
                    minDeg = a.minDeg,
                    maxDeg = a.maxDeg,
                    zeroOffsetDeg = -a.angleInFileDeg,
                )
            }
            // No arquivo montado, cada peça está na pose do arquivo; o flange e o sistema do robô
            // foram marcados nessa pose e precisam ir para a pose zero dos eixos.
            val draft = RobotModel(name, basePart, joints, Transform.IDENTITY, tool, Transform.IDENTITY, placement)
            val fileAngles = DoubleArray(sorted.size) { sorted[it].angleInFileDeg }
            val lastPartInFile = draft.flangeInBase(fileAngles) // flange = identidade aqui
            val flangeInLastPart = lastPartInFile.inverse() * flange
            val frame = robotFrame ?: Transform.fromAxis(sorted.first().point, sorted.first().direction)
            return draft.copy(flange = flangeInLastPart, robotFrame = frame)
        }
    }
}
