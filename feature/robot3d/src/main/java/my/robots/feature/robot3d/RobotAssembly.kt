package my.robots.feature.robot3d

import my.robots.core.kinematics.KawasakiPose
import my.robots.core.kinematics.PartRole
import my.robots.core.kinematics.RobotModel
import my.robots.core.kinematics.Transform
import my.robots.core.kinematics.Vec3

/** O que uma peça é: o tipo e, se for eixo, o número dele (1, 2, 3…). */
data class PartAssignment(val role: PartRole, val axis: Int = 0) {
    val label: String
        get() = when (role) {
            PartRole.BASE -> "Base"
            PartRole.AXIS -> "Eixo $axis"
            PartRole.TOOL -> "Ferramenta"
            PartRole.OTHER -> "Outro"
        }
}

/**
 * Um eixo marcado no montador, nas coordenadas do arquivo (mm, Z para cima, a pose em que as
 * peças vieram). [direction] já tem o sentido positivo escolhido.
 */
data class AxisDef(
    val point: Vec3,
    val direction: Vec3,
    val minDeg: Double = -180.0,
    val maxDeg: Double = 180.0,
    /** Como foi marcado (face redonda, plana, 2 pontos) e o raio da face, para mostrar. */
    val kind: AxisGuess.Kind = AxisGuess.Kind.REDONDA,
    val radiusMm: Double = 0.0,
    /**
     * Ângulo que o controlador mostra com a peça na pose do arquivo (0 = o arquivo veio no zero
     * deste eixo). Com o robô em 0°, a peça gira −[zeroDeg] a partir da pose do arquivo.
     */
    val zeroDeg: Double = 0.0,
)

/** Um ponto do programa de teste: nome e o ângulo de cada eixo (graus). */
data class TestPoint(val name: String, val angles: List<Double>)

/**
 * Programa de teste do montador (6ª etapa): pontos percorridos na ordem, em loop. Cada trecho
 * move todos os eixos juntos (chegam ao mesmo tempo, como o JMOVE), com o eixo que mais anda na
 * [speedDegS], e para [pauseS] segundos em cada ponto.
 */
data class TestProgram(
    val points: List<TestPoint> = emptyList(),
    val speedDegS: Double = 60.0,
    val pauseS: Double = 0.5,
) {
    companion object {
        /** Tempo do trecho de [a] até [b] (s): o eixo que mais anda define. Mínimo de 0,05 s. */
        fun durationS(a: List<Double>, b: List<Double>, speedDegS: Double): Double {
            val n = minOf(a.size, b.size)
            var most = 0.0
            for (i in 0 until n) most = maxOf(most, kotlin.math.abs(b[i] - a[i]))
            return maxOf(0.05, most / speedDegS.coerceAtLeast(0.1))
        }

        /**
         * Ângulos no instante [t] (0 a 1) do trecho de [a] até [b], com saída e chegada suaves
         * (curva em S: começa e termina parado).
         */
        fun interpolate(a: List<Double>, b: List<Double>, t: Double): List<Double> {
            val x = t.coerceIn(0.0, 1.0)
            val s = x * x * (3 - 2 * x)
            return List(maxOf(a.size, b.size)) { i ->
                val from = a.getOrElse(i) { 0.0 }
                val to = b.getOrElse(i) { from }
                from + (to - from) * s
            }
        }
    }
}

/**
 * Onde fica a origem do sistema do robô ("null base", o BASE 0 do controlador), sempre no eixo 1.
 * Na Kawasaki ela muda com o modelo: no KJ264 de chão o K-ROSET põe a origem no eixo do JT1 a
 * 900 mm do piso, na altura do eixo do JT2 (`KJ264-A001.krprj`: robô em Z +900, base em −900).
 */
enum class RobotOrigin(val label: String) {
    EIXO2("Altura do eixo 2"),
    PISO("Piso da base"),
    PERSONALIZADA("Personalizada"),
}

/** Para onde aponta o X do sistema do robô, nas coordenadas do arquivo. */
enum class RobotFront(val label: String, val vector: Vec3) {
    PX("+X", Vec3.X), PY("+Y", Vec3.Y), NX("−X", -Vec3.X), NY("−Y", -Vec3.Y),
}

/**
 * Tudo o que o montador sabe de um robô: o arquivo, o que é cada peça, os eixos, o flange e
 * onde a base fica. Kotlin puro; vira [RobotModel] com [model] e é salvo com [toJson].
 *
 * O arquivo é a referência: todas as peças vieram montadas no mesmo sistema (como o STEP do
 * KJ264), então cada eixo é marcado uma vez só e vale para a peça pai e a filha. A pose do
 * arquivo conta como o zero de todos os eixos (o ajuste fino com o `WHERE` vem depois).
 */
data class RobotAssembly(
    val name: String,
    val fileName: String,
    /** Peças na ordem do arquivo. */
    val parts: List<String>,
    val roles: Map<String, PartAssignment> = emptyMap(),
    val axes: Map<Int, AxisDef> = emptyMap(),
    /** Flange: origem e direção (para fora) na peça do último eixo. null = no fim do último eixo. */
    val flange: AxisDef? = null,
    /** Onde a base fica no espaço 3D: X, Y, Z (mm) e giro em volta de Z (graus). */
    val baseX: Double = 0.0,
    val baseY: Double = 0.0,
    val baseZ: Double = 0.0,
    val baseRotDeg: Double = 0.0,
    val front: RobotFront = RobotFront.PX,
    /** Cor escolhida para cada peça (0xRRGGBB); peça fora do mapa fica com a cor do arquivo. */
    val colors: Map<String, Int> = emptyMap(),
    val program: TestProgram = TestProgram(),
    /** TOOL do controlador (X Y Z O A T do flange até o TCP); null = TCP no flange. */
    val tool: KawasakiPose? = null,
    /**
     * Ajuste de cada peça que veio fora da posição de montagem (Mover e Girar): leva a peça do
     * jeito que está no arquivo para a posição montada. Eixos, flange e pontos são marcados já na
     * posição montada.
     */
    val offsets: Map<String, Transform> = emptyMap(),
    /** Peças fixadas: o ajuste delas não muda mais. */
    val locked: Set<String> = emptySet(),
    /** Origem do sistema do robô (ver [RobotOrigin]); [originCustom] vale em PERSONALIZADA. */
    val origin: RobotOrigin = RobotOrigin.EIXO2,
    val originCustom: Vec3 = Vec3.ZERO,
    /** Z do piso da base no arquivo (o ponto mais baixo da peça base), para a origem no piso. */
    val baseFloorZ: Double = 0.0,
    /**
     * BASE do controlador (X Y Z O A T do sistema do robô até a base em uso); null = BASE 0.
     * Com BASE, o `WHERE` e os pontos passam a ser contados a partir da base deslocada.
     */
    val baseTrans: KawasakiPose? = null,
) {
    val basePart: String? get() = roles.entries.firstOrNull { it.value.role == PartRole.BASE }?.key

    /** Peças de eixo na ordem do número (1, 2, 3…). */
    val axisParts: List<Pair<Int, String>>
        get() = roles.filter { it.value.role == PartRole.AXIS }.map { it.value.axis to it.key }.sortedBy { it.first }

    val placement: Transform
        get() = Transform(
            Transform.rotZ(Math.toRadians(baseRotDeg)).r,
            Vec3(baseX, baseY, baseZ),
        )

    /** O que ainda impede de montar (vazio = tudo certo para marcar os eixos). */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (parts.size < 2) out += "O arquivo tem uma peça só: o robô precisa vir dividido (base, uma peça por eixo, ferramenta)."
        val bases = roles.count { it.value.role == PartRole.BASE }
        if (bases == 0) out += "Falta dizer qual peça é a base."
        if (bases > 1) out += "Há $bases peças marcadas como base: só pode uma."
        val numbers = axisParts.map { it.first }
        if (numbers.isEmpty()) out += "Nenhuma peça marcada como eixo."
        numbers.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "Eixo $it marcado em mais de uma peça." }
        val distinct = numbers.distinct()
        if (distinct.isNotEmpty() && distinct != (1..distinct.size).toList()) {
            val missing = (1..distinct.max()).filter { it !in distinct }
            out += "Falta o eixo ${missing.joinToString(", ")}: os eixos vão de 1 em diante, sem pular."
        }
        val unassigned = parts.count { it !in roles }
        if (unassigned > 0) out += "$unassigned peça(s) sem tipo (ficam paradas com a base)."
        return out
    }

    /** Quantos eixos estão marcados em sequência a partir do 1 (o modelo usa só esses). */
    val definedAxisCount: Int
        get() {
            val parts = axisParts.map { it.first }.toSet()
            var n = 0
            while ((n + 1) in parts && (n + 1) in axes) n++
            return n
        }

    /**
     * O modelo da cinemática com os eixos marcados até agora (do 1 até o primeiro que falta).
     * null se não há base ou nenhum eixo marcado.
     */
    fun model(): RobotModel? {
        val base = basePart ?: return null
        if (problems().any { it.startsWith("Há ") || it.contains("mais de uma peça") }) return null
        val n = definedAxisCount
        if (n == 0) return null
        val byNumber = axisParts.toMap()
        val list = (1..n).map { i ->
            val a = axes.getValue(i)
            RobotModel.AssembledAxis(i, byNumber.getValue(i), a.point, a.direction, a.minDeg, a.maxDeg, a.zeroDeg)
        }
        val last = axes.getValue(n)
        val f = flange ?: last
        val flangeFrame = Transform.fromAxis(f.point, f.direction, front.vector)
        val robotFrame = nullBase()!! * (baseTrans?.toTransform() ?: Transform.IDENTITY)
        return RobotModel.assembled(
            name, base, list, flangeFrame,
            tool = tool?.toTransform() ?: Transform.IDENTITY,
            robotFrame = robotFrame, placement = placement,
        )
    }

    /**
     * O sistema do robô com BASE 0 ("null base"), nas coordenadas do arquivo: origem no eixo 1
     * (ver [origin]), Z para cima ao longo do eixo 1 e X na [front]. null sem o eixo 1.
     */
    fun nullBase(): Transform? {
        val a1 = axes[1] ?: return null
        // o Z do robô aponta para cima, qualquer que seja o sentido positivo do JT1
        val up = if (a1.direction.z >= 0) a1.direction else -a1.direction
        val o = when (origin) {
            RobotOrigin.EIXO2 -> axes[2]?.let { a2 -> a1.point + up * (a2.point - a1.point).dot(up) } ?: a1.point
            RobotOrigin.PISO -> if (kotlin.math.abs(up.z) > 1e-6) a1.point + up * ((baseFloorZ - a1.point.z) / up.z) else a1.point
            RobotOrigin.PERSONALIZADA -> originCustom
        }
        return Transform.fromAxis(o, up, front.vector)
    }

    /** Sistema do robô com BASE 0 no espaço 3D; null sem o eixo 1. */
    fun robotFrameInWorld(): Transform? = nullBase()?.let { placement * it }

    /** Base deslocada pelo BASE do controlador no espaço 3D; null sem BASE. */
    fun baseFrameInWorld(): Transform? {
        val b = baseTrans ?: return null
        return robotFrameInWorld()?.let { it * b.toTransform() }
    }

    /**
     * Onde cada peça fica (em relação à pose do arquivo) com os ângulos [deg] dos eixos marcados.
     * Peças de eixos ainda sem marca vão junto com o último eixo marcado; a ferramenta vai com a
     * última peça; peças sem tipo e "outro" ficam com a base.
     */
    fun poses(deg: DoubleArray = DoubleArray(definedAxisCount)): Map<String, Transform> {
        val place = placement
        val model = model()
        val out = LinkedHashMap<String, Transform>()
        for (p in parts) out[p] = place
        if (model == null) return out
        val partPoses = model.partTransforms(model.clamp(deg.copyOf(model.axisCount)))
        out.putAll(partPoses)
        val lastPose = partPoses.getValue(model.flangePart)
        for ((number, part) in axisParts) if (number > model.axisCount) out[part] = lastPose
        for ((part, a) in roles) if (a.role == PartRole.TOOL) out[part] = lastPose
        return out
    }

    /**
     * Onde desenhar cada peça: a pose de [poses] mais o ajuste da peça (o desenho e o toque
     * trabalham com a peça como está no arquivo).
     */
    fun displayPoses(deg: DoubleArray = DoubleArray(definedAxisCount)): Map<String, Transform> =
        poses(deg).mapValues { (part, pose) -> offsets[part]?.let { pose * it } ?: pose }

    /** Ponto da peça como está no arquivo → posição montada. */
    fun toAssembled(part: String, p: Vec3): Vec3 = offsets[part]?.apply(p) ?: p

    /** Direção da peça como está no arquivo → posição montada. */
    fun dirToAssembled(part: String, d: Vec3): Vec3 = offsets[part]?.rotate(d) ?: d

    /** Pai do eixo [number]: a base (eixo 1) ou a peça do eixo anterior. */
    fun parentOf(number: Int): String? =
        if (number == 1) basePart else axisParts.firstOrNull { it.first == number - 1 }?.second

    fun partOf(number: Int): String? = axisParts.firstOrNull { it.first == number }?.second

    fun withRole(part: String, assignment: PartAssignment?): RobotAssembly =
        copy(roles = if (assignment == null) roles - part else roles + (part to assignment))

    fun withAxis(number: Int, def: AxisDef?): RobotAssembly =
        copy(axes = if (def == null) axes - number else axes + (number to def))

    fun toJson(): String = MiniJson.write(
        linkedMapOf(
            "formato" to "myrobots-robo3d",
            "versao" to 1,
            "nome" to name,
            "arquivo" to fileName,
            "pecas" to parts,
            "tipos" to roles.mapValues { (_, a) -> linkedMapOf("tipo" to a.role.name, "eixo" to a.axis) },
            "eixos" to axes.entries.sortedBy { it.key }.map { (n, a) -> axisJson(a) + ("numero" to n) },
            "flange" to flange?.let { axisJson(it) },
            "base" to linkedMapOf("x" to baseX, "y" to baseY, "z" to baseZ, "giro" to baseRotDeg),
            "frente" to front.name,
            "cores" to colors.mapValues { (_, c) -> colorHex(c) },
            "tool" to tool?.let { listOf(it.x, it.y, it.z, it.o, it.a, it.t) },
            "ajustes" to offsets.mapValues { (_, t) -> KawasakiPose.fromTransform(t).let { listOf(it.x, it.y, it.z, it.o, it.a, it.t) } },
            "fixas" to locked.toList(),
            "origem" to origin.name,
            "origemXYZ" to vecJson(originCustom),
            "pisoZ" to baseFloorZ,
            "baseControlador" to baseTrans?.let { listOf(it.x, it.y, it.z, it.o, it.a, it.t) },
            "programa" to linkedMapOf(
                "velocidade" to program.speedDegS,
                "pausa" to program.pauseS,
                "pontos" to program.points.map { linkedMapOf("nome" to it.name, "eixos" to it.angles) },
            ),
        ),
    )

    companion object {
        /** Número no fim do nome: "…_J3", "eixo 3", "link3", "axis_3". */
        private val NUMBER_AT_END = Regex("""(?:^|[^A-Za-z0-9])(?:j|jt|eixo|axis|link|a)?[ _-]?(\d{1,2})$""", RegexOption.IGNORE_CASE)
        private val TOOL_WORDS = listOf("tool", "ferramenta", "pistola", "gun", "tcp", "garra", "gripper")

        /** Sugere o tipo de cada peça pelo nome (J0 = base, J1…J6 = eixos). */
        fun suggestRoles(parts: List<String>): Map<String, PartAssignment> {
            val out = LinkedHashMap<String, PartAssignment>()
            for (p in parts) {
                val lower = p.lowercase()
                if (TOOL_WORDS.any { it in lower }) { out[p] = PartAssignment(PartRole.TOOL); continue }
                if ("base" in lower) { out[p] = PartAssignment(PartRole.BASE); continue }
                val n = NUMBER_AT_END.find(p)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                out[p] = if (n == 0) PartAssignment(PartRole.BASE) else PartAssignment(PartRole.AXIS, n)
            }
            // duas bases sugeridas: fica só a primeira
            val bases = out.filter { it.value.role == PartRole.BASE }.keys.drop(1)
            bases.forEach { out.remove(it) }
            return out
        }

        /** 0xRRGGBB → "#RRGGBB". */
        fun colorHex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        /** "#RRGGBB" ou "RRGGBB" → 0xRRGGBB; null se não for uma cor. */
        fun parseColor(text: String): Int? {
            val t = text.trim().removePrefix("#")
            if (t.length != 6) return null
            return t.toIntOrNull(16)
        }

        private fun vecJson(v: Vec3) = listOf(v.x, v.y, v.z)
        private fun axisJson(a: AxisDef) = linkedMapOf<String, Any?>(
            "ponto" to vecJson(a.point), "direcao" to vecJson(a.direction),
            "min" to a.minDeg, "max" to a.maxDeg, "marcado" to a.kind.name, "raio" to a.radiusMm,
            "zero" to a.zeroDeg,
        )

        private fun vec(v: Any?): Vec3? {
            val l = v.arr()
            if (l.size != 3) return null
            return Vec3(l[0].num() ?: return null, l[1].num() ?: return null, l[2].num() ?: return null)
        }

        private fun axis(v: Any?): AxisDef? {
            val o = v.obj()
            val dir = vec(o["direcao"]) ?: return null
            if (dir.length() < 1e-9) return null
            return AxisDef(
                point = vec(o["ponto"]) ?: return null,
                direction = dir.normalized(),
                minDeg = o["min"].num() ?: -180.0,
                maxDeg = o["max"].num() ?: 180.0,
                kind = runCatching { AxisGuess.Kind.valueOf(o["marcado"].str() ?: "") }.getOrDefault(AxisGuess.Kind.REDONDA),
                radiusMm = o["raio"].num() ?: 0.0,
                zeroDeg = o["zero"].num() ?: 0.0,
            )
        }

        fun fromJson(text: String): RobotAssembly {
            val o = MiniJson.parse(text).obj()
            require(o["formato"].str() == "myrobots-robo3d") { "Não é um robô montado do MyRobots." }
            val roles = o["tipos"].obj().mapNotNull { (part, v) ->
                val a = v.obj()
                val role = runCatching { PartRole.valueOf(a["tipo"].str() ?: "") }.getOrNull() ?: return@mapNotNull null
                part to PartAssignment(role, a["eixo"].int() ?: 0)
            }.toMap()
            val axes = o["eixos"].arr().mapNotNull { v ->
                val n = v.obj()["numero"].int() ?: return@mapNotNull null
                n to (axis(v) ?: return@mapNotNull null)
            }.toMap()
            val base = o["base"].obj()
            return RobotAssembly(
                name = o["nome"].str() ?: "Robô",
                fileName = o["arquivo"].str() ?: "",
                parts = o["pecas"].arr().mapNotNull { it.str() },
                roles = roles,
                axes = axes,
                flange = o["flange"]?.let { axis(it) },
                baseX = base["x"].num() ?: 0.0,
                baseY = base["y"].num() ?: 0.0,
                baseZ = base["z"].num() ?: 0.0,
                baseRotDeg = base["giro"].num() ?: 0.0,
                front = runCatching { RobotFront.valueOf(o["frente"].str() ?: "") }.getOrDefault(RobotFront.PX),
                tool = o["tool"].arr().mapNotNull { it.num() }.takeIf { it.size == 6 }
                    ?.let { KawasakiPose(it[0], it[1], it[2], it[3], it[4], it[5]) },
                offsets = o["ajustes"].obj().mapNotNull { (part, v) ->
                    val n = v.arr().mapNotNull { it.num() }
                    if (n.size != 6) null else part to KawasakiPose(n[0], n[1], n[2], n[3], n[4], n[5]).toTransform()
                }.toMap(),
                locked = o["fixas"].arr().mapNotNull { it.str() }.toSet(),
                origin = runCatching { RobotOrigin.valueOf(o["origem"].str() ?: "") }.getOrDefault(RobotOrigin.EIXO2),
                originCustom = vec(o["origemXYZ"]) ?: Vec3.ZERO,
                baseFloorZ = o["pisoZ"].num() ?: 0.0,
                baseTrans = o["baseControlador"].arr().mapNotNull { it.num() }.takeIf { it.size == 6 }
                    ?.let { KawasakiPose(it[0], it[1], it[2], it[3], it[4], it[5]) },
                colors = o["cores"].obj().mapNotNull { (part, v) -> parseColor(v.str() ?: "")?.let { part to it } }.toMap(),
                program = o["programa"].obj().let { prog ->
                    TestProgram(
                        points = prog["pontos"].arr().mapNotNull { v ->
                            val pt = v.obj()
                            val angles = pt["eixos"].arr().map { it.num() ?: return@mapNotNull null }
                            TestPoint(pt["nome"].str() ?: "P", angles)
                        },
                        speedDegS = prog["velocidade"].num() ?: 60.0,
                        pauseS = prog["pausa"].num() ?: 0.5,
                    )
                },
            )
        }
    }
}
