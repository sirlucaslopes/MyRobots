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
    val controllerIp: String? = null
) {
    /** true quando o backup não trouxe nenhum dado do robô (não é SAVE/FULL). */
    val isEmpty: Boolean get() = this == RobotInfo()
}

/**
 * Lê os dados do robô de um backup. O texto não é alterado.
 */
object AsRobotInfo {

    private val ROBOT_TYPE = Regex("""^ZROBOT\.TYPE\s+\d+\s+\d+\s+(\d+)\s+(\d+)\s+-?\d+\s+(\S+)""")
    private val VERSION = Regex("""^\.\*===\s*(AS|SERVO) GROUP\s*===\s*:\s*(\S+)""")

    fun parse(content: String): RobotInfo {
        if (content.isBlank()) return RobotInfo()

        var info = RobotInfo()
        var contTime: Double? = null

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

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
                ".NETCONF2" -> info = info.copy(
                    controllerIp = info.controllerIp ?: value?.substringBefore(',')?.takeIf { it.isNotBlank() }
                )
            }
        }

        return if (info.hourMeterHours == null) info.copy(hourMeterHours = contTime) else info
    }
}

/**
 * Situação geral do robô, montada a partir do backup.
 *
 * - level: OK, ATTENTION (houve erro nos 7 dias antes do backup, ou o backup tem mais de
 *   30 dias) ou NO_DATA (backup sem dados do controlador e sem log de erros).
 * - errorsLast7Days: erros do .ERRLOG nos 7 dias antes da data do backup.
 * - lastError: o erro mais recente do .ERRLOG, se houver.
 * - mostFrequentError: o código que mais aparece nos 7 dias (com quantas vezes e a mensagem).
 * - backupAgeDays: idade do backup em dias, contada de "agora".
 */
data class RobotHealth(
    val level: Level,
    val errorsLast7Days: Int,
    val lastError: RobotErrorLogEntry?,
    val backupAgeDays: Long,
    val mostFrequentError: FrequentError? = null
) {
    /** Um código de erro e quantas vezes ele apareceu. */
    data class FrequentError(val code: String, val message: String, val count: Int)

    enum class Level { OK, ATTENTION, NO_DATA }

    companion object {
        const val STALE_BACKUP_DAYS = 30L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val ERRLOG_DATE = java.time.format.DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")

        /**
         * Avalia a situação. As datas do .ERRLOG ("26/09/29 15:03:58", ano com 2 dígitos)
         * são lidas no fuso [zone]; uma data que não segue o formato é ignorada na contagem.
         */
        fun evaluate(
            info: RobotInfo,
            errors: List<RobotErrorLogEntry>,
            backupTimestamp: Long,
            now: Long = System.currentTimeMillis(),
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): RobotHealth {
            val ageDays = ((now - backupTimestamp) / DAY_MS).coerceAtLeast(0)
            val windowStart = backupTimestamp - 7 * DAY_MS
            val recentErrors = errors.filter { e ->
                val t = errorTimeMillis(e.timestamp, zone) ?: return@filter false
                t in windowStart..backupTimestamp
            }
            val recent = recentErrors.size
            val frequent = recentErrors.groupBy { it.errorCode }.maxByOrNull { it.value.size }?.let { (code, list) ->
                FrequentError(code, list.first().errorMessage, list.size)
            }
            val level = when {
                info.isEmpty && errors.isEmpty() -> Level.NO_DATA
                recent > 0 || ageDays > STALE_BACKUP_DAYS -> Level.ATTENTION
                else -> Level.OK
            }
            return RobotHealth(level, recent, errors.firstOrNull(), ageDays, frequent)
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
