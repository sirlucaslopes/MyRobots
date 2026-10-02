package my.robots.core.common.ascode

/**
 * Dados do robô e do controlador que vêm num backup SAVE/FULL.
 *
 * Todos os campos são opcionais: um backup parcial (só programas) não traz nada disso, e
 * cada controlador/versão do AS pode omitir uma ou outra linha.
 *
 * - model / serialNumber / axes: da linha "ZROBOT.TYPE" (seção .ROBOTDATA1), por exemplo
 *   `ZROBOT.TYPE    35   3   7 3772   -57256   KJ264-B001 ( 2026-04-08 13:53 )`
 *   -> eixos 7, série 3772, modelo KJ264-B001.
 * - hourMeterHours: "HOUR_MTR" (horímetro do controlador, em horas). Sem ela, usa "CONT_TIM".
 * - servoOnHours: "SERV_TIM", em horas.
 * - motorOnCount / emergencyStopCount / brakeCount: "MTON_CNT", "ESTP_CNT" e "BRKE_CNT"
 *   (seção .OPE_INFO1).
 * - asVersion / servoVersion: do cabeçalho de comentários ".*=== AS GROUP ===" e
 *   ".*=== SERVO GROUP ===".
 * - controllerIp: primeiro campo de ".NETCONF2" (a placa de rede usada pelo terminal).
 * - axisMoveHours: "MOVE_TJT", horas em movimento de cada eixo (JT1, JT2...).
 * - axisDistance: "DIST_DJT", deslocamento acumulado de cada eixo, na unidade do controlador.
 * - encoderTemperatures: seção ".ENCTEMPLOG", menor e maior temperatura do encoder de cada eixo.
 * As listas por eixo vêm cortadas na quantidade de eixos do robô (o controlador grava 18).
 */
data class RobotInfo(
    val model: String? = null,
    val serialNumber: String? = null,
    val axes: Int? = null,
    val hourMeterHours: Double? = null,
    val servoOnHours: Double? = null,
    val motorOnCount: Int? = null,
    val emergencyStopCount: Int? = null,
    val brakeCount: Int? = null,
    val asVersion: String? = null,
    val servoVersion: String? = null,
    val controllerIp: String? = null,
    val axisMoveHours: List<Double> = emptyList(),
    val axisDistance: List<Double> = emptyList(),
    val encoderTemperatures: List<EncoderTemperature> = emptyList()
) {
    /** true quando o backup não trouxe nenhum dado do robô (não é SAVE/FULL). */
    val isEmpty: Boolean get() = this == RobotInfo()
}

/**
 * Menor e maior temperatura registradas no encoder de um eixo (seção ".ENCTEMPLOG"), com a
 * data de cada uma no formato do controlador ("26/05/12 08:00:53"). Zero costuma indicar
 * que o eixo nunca teve leitura.
 */
data class EncoderTemperature(
    val axis: Int,
    val minCelsius: Double?,
    val minAt: String?,
    val maxCelsius: Double?,
    val maxAt: String?
)

/**
 * Lê os dados do robô de um backup. O texto não é alterado.
 */
object AsRobotInfo {

    private val ROBOT_TYPE = Regex("""^ZROBOT\.TYPE\s+\d+\s+\d+\s+(\d+)\s+(\d+)\s+-?\d+\s+(\S+)""")
    private val VERSION = Regex("""^\.\*===\s*(AS|SERVO) GROUP\s*===\s*:\s*(\S+)""")
    private val ENC_TEMP = Regex("""^JT(\d+)\s*-\s*\[([^]]*)]\s*(-?[\d.]+)""")

    fun parse(content: String): RobotInfo {
        if (content.isBlank()) return RobotInfo()

        var info = RobotInfo()
        var contTime: Double? = null
        var moveHours: List<Double>? = null
        var distance: List<Double>? = null
        // ENCTEMPLOG: null = fora da seção, "MIN" ou "MAX" = dentro do bloco
        var tempBlock: String? = null
        val tempMin = sortedMapOf<Int, Pair<Double?, String>>()
        val tempMax = sortedMapOf<Int, Pair<Double?, String>>()

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.equals(".ENCTEMPLOG", ignoreCase = true)) {
                tempBlock = ""
                continue
            }
            if (tempBlock != null) {
                when {
                    trimmed.startsWith(".") -> tempBlock = null
                    trimmed.contains("MIN", ignoreCase = true) && trimmed.startsWith("===") -> tempBlock = "MIN"
                    trimmed.contains("MAX", ignoreCase = true) && trimmed.startsWith("===") -> tempBlock = "MAX"
                    else -> ENC_TEMP.find(trimmed)?.let { m ->
                        val entry = m.groupValues[3].toDoubleOrNull() to m.groupValues[2].trim()
                        val axis = m.groupValues[1].toInt()
                        if (tempBlock == "MIN") tempMin[axis] = entry else if (tempBlock == "MAX") tempMax[axis] = entry
                    }
                }
                if (tempBlock != null) continue
            }

            ROBOT_TYPE.find(trimmed)?.let { m ->
                if (info.model == null) {
                    info = info.copy(
                        axes = m.groupValues[1].toIntOrNull(),
                        serialNumber = m.groupValues[2],
                        model = m.groupValues[3]
                    )
                }
                continue
            }

            VERSION.find(trimmed)?.let { m ->
                info = if (m.groupValues[1] == "AS") {
                    info.copy(asVersion = info.asVersion ?: m.groupValues[2])
                } else {
                    info.copy(servoVersion = info.servoVersion ?: m.groupValues[2])
                }
                continue
            }

            // linhas "CHAVE  valor" da .OPE_INFO1; as com prefixo "M_" são outra cópia e ficam de fora
            val parts = trimmed.split(Regex("\\s+"))
            val value = parts.getOrNull(1)
            when (parts[0]) {
                "HOUR_MTR" -> info = info.copy(hourMeterHours = info.hourMeterHours ?: value?.toDoubleOrNull())
                "CONT_TIM" -> contTime = contTime ?: value?.toDoubleOrNull()
                "SERV_TIM" -> info = info.copy(servoOnHours = info.servoOnHours ?: value?.toDoubleOrNull())
                "MTON_CNT" -> info = info.copy(motorOnCount = info.motorOnCount ?: value?.toIntOrNull())
                "ESTP_CNT" -> info = info.copy(emergencyStopCount = info.emergencyStopCount ?: value?.toIntOrNull())
                "BRKE_CNT" -> info = info.copy(brakeCount = info.brakeCount ?: value?.toIntOrNull())
                "MOVE_TJT" -> moveHours = moveHours ?: parts.drop(1).mapNotNull { it.toDoubleOrNull() }
                "DIST_DJT" -> distance = distance ?: parts.drop(1).mapNotNull { it.toDoubleOrNull() }
                ".NETCONF2" -> info = info.copy(
                    controllerIp = info.controllerIp ?: value?.substringBefore(',')?.takeIf { it.isNotBlank() }
                )
            }
        }

        if (info.hourMeterHours == null) info = info.copy(hourMeterHours = contTime)

        // o controlador grava 18 posições; ficam só as dos eixos que o robô tem
        val axes = info.axes ?: (tempMin.keys + tempMax.keys).maxOrNull()
            ?: moveHours?.indexOfLast { it != 0.0 }?.plus(1) ?: 0
        fun <T> cut(list: List<T>?) = list?.take(axes) ?: emptyList()
        val temps = (1..axes).mapNotNull { axis ->
            val min = tempMin[axis]
            val max = tempMax[axis]
            if (min == null && max == null) null
            else EncoderTemperature(axis, min?.first, min?.second, max?.first, max?.second)
        }
        return info.copy(axisMoveHours = cut(moveHours), axisDistance = cut(distance), encoderTemperatures = temps)
    }

    /** Número do eixo citado numa mensagem de alarme ("Jt 5 motor overloaded", "Jt7 beyond..."). */
    private val AXIS_IN_MESSAGE = Regex("""\bJ[Tt]\s*(\d+)""")

    fun axisOf(message: String): Int? = AXIS_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Gravidade de um alarme do .ERRLOG.
 * - ROUTINE: faz parte do dia a dia da cabine (porta aberta, motor desligado...). Não muda o status.
 * - PROCESS: erro de programa ou de movimento (fora de alcance, singularidade...). Aparece
 *   na lista do status, mas sozinho não liga o "Atenção".
 * - SERIOUS: falha de equipamento (encoder, servo, sobrecorrente, temperatura...). Liga o "Atenção".
 */
enum class ErrorSeverity { ROUTINE, PROCESS, SERIOUS }

/**
 * Classifica os alarmes pelo código e pela mensagem (em inglês, como vem do controlador).
 * As listas abaixo são o lugar para ajustar quando um código for mal classificado.
 */
object AsErrorSeverity {
    /** Códigos de rotina conhecidos. */
    val ROUTINE_CODES = setOf(
        "E1326", // Safety fence is open.
        "E1135", // Motor power OFF.
        "D1561", // [Power sequence board] AC primary power OFF.
        "E1060"  // Cannot execute in check back mode.
    )

    private val ROUTINE_WORDS = listOf(
        "safety fence", "motor power off", "emergency stop", "ac primary power off", "check back mode"
    )

    private val SERIOUS_WORDS = listOf(
        "encoder", "servo", "current", "collision", "overload", "overheat", "temperature",
        "brake", "battery", "communication", "deviation", "amplifier", "power module", "igbt",
        "fan ", "fuse", "ground fault", "short circuit", "regenerat", "malfunction", "failure",
        "pressure within enclos"
    )

    fun classify(code: String, message: String): ErrorSeverity {
        val msg = message.lowercase()
        return when {
            // entrada sem código: o controlador só gravou as operações (reset, emergência...)
            code.isBlank() -> ErrorSeverity.ROUTINE
            code in ROUTINE_CODES || ROUTINE_WORDS.any { it in msg } -> ErrorSeverity.ROUTINE
            SERIOUS_WORDS.any { it in msg } -> ErrorSeverity.SERIOUS
            // códigos "D" são do hardware do controlador (placas, alimentação, segurança)
            code.startsWith("D") -> ErrorSeverity.SERIOUS
            else -> ErrorSeverity.PROCESS
        }
    }
}

/**
 * Situação geral do robô, montada a partir do backup.
 *
 * - level: OK, ATTENTION ou NO_DATA (backup sem dados do controlador e sem log de erros).
 *   ATTENTION quando houve alarme SERIOUS nos 7 dias antes do backup, ou quando o backup
 *   tem mais de 30 dias. Alarmes de rotina e de processo não mudam o nível.
 * - errorGroups: os alarmes dos 7 dias antes do backup, agrupados por código (mais grave
 *   primeiro, depois o mais frequente). É o que a tela mostra ao tocar no status.
 * - backupAgeDays: idade do backup em dias, contada de "agora".
 */
data class RobotHealth(
    val level: Level,
    val errorGroups: List<ErrorGroup>,
    val backupAgeDays: Long
) {
    enum class Level { OK, ATTENTION, NO_DATA }

    /** Um código de alarme com quantas vezes apareceu e quando foi a última. */
    data class ErrorGroup(
        val code: String,
        val message: String,
        val severity: ErrorSeverity,
        val count: Int,
        val lastTimestamp: String
    )

    val seriousGroups: List<ErrorGroup> get() = errorGroups.filter { it.severity == ErrorSeverity.SERIOUS }
    val isBackupStale: Boolean get() = backupAgeDays > STALE_BACKUP_DAYS

    /** Quantos itens precisam de atenção (alarmes graves + backup antigo). */
    val attentionCount: Int get() = seriousGroups.size + if (isBackupStale) 1 else 0

    companion object {
        const val STALE_BACKUP_DAYS = 30L
        const val WINDOW_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val ERRLOG_DATE = java.time.format.DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")

        /**
         * Avalia a situação. As datas do .ERRLOG ("26/09/29 15:03:58", ano com 2 dígitos)
         * são lidas no fuso [zone]; uma data que não segue o formato fica fora da janela.
         * O .ERRLOG vem do mais novo para o mais antigo.
         */
        fun evaluate(
            info: RobotInfo,
            errors: List<RobotErrorLogEntry>,
            backupTimestamp: Long,
            now: Long = System.currentTimeMillis(),
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): RobotHealth {
            val ageDays = ((now - backupTimestamp) / DAY_MS).coerceAtLeast(0)
            val windowStart = backupTimestamp - WINDOW_DAYS * DAY_MS
            val recent = errors.filter { e ->
                val t = errorTimeMillis(e.timestamp, zone) ?: return@filter false
                t in windowStart..backupTimestamp
            }
            val groups = recent.groupBy { it.errorCode }.map { (code, list) ->
                ErrorGroup(
                    code = code,
                    message = list.first().errorMessage,
                    severity = AsErrorSeverity.classify(code, list.first().errorMessage),
                    count = list.size,
                    lastTimestamp = list.first().timestamp
                )
            }.sortedWith(compareByDescending<ErrorGroup> { it.severity.ordinal }.thenByDescending { it.count })

            val health = RobotHealth(Level.OK, groups, ageDays)
            val level = when {
                info.isEmpty && errors.isEmpty() -> Level.NO_DATA
                health.attentionCount > 0 -> Level.ATTENTION
                else -> Level.OK
            }
            return health.copy(level = level)
        }

        /**
         * Data do .ERRLOG ("26/09/29 15:03:58", ano/mês/dia) no formato do Brasil
         * ("29/09/2026 15:03"). Se não seguir o formato, devolve o texto como veio.
         */
        fun formatErrorTime(timestamp: String): String = try {
            java.time.LocalDateTime.parse(timestamp.trim(), ERRLOG_DATE)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        } catch (e: java.time.format.DateTimeParseException) {
            timestamp
        }

        private fun errorTimeMillis(timestamp: String, zone: java.time.ZoneId): Long? = try {
            java.time.LocalDateTime.parse(timestamp.trim(), ERRLOG_DATE).atZone(zone).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
