package my.robots.core.common.ascode

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Leitura dos contadores do controlador num backup: horímetro (controle ligado), horas em
 * operação (servo ligado, SERV_TIM) e vezes que o motor foi ligado.
 */
data class UsagePoint(
    val timestamp: Long,
    val poweredHours: Double?,
    val operatingHours: Double?,
    val motorOnCount: Int?
)

/**
 * Uso do robô num dia.
 * - operatingHours: horas em operação (servo ligado).
 * - poweredHours: horas com o controlador ligado.
 * - motorOnCount: vezes que o motor foi ligado.
 * - estimated: true quando o dia está entre dois backups distantes (mais de 2 dias), e o
 *   valor é a média do intervalo, não o uso real daquele dia.
 */
data class DailyUsage(
    val date: LocalDate,
    val operatingHours: Double,
    val poweredHours: Double,
    val motorOnCount: Double,
    val estimated: Boolean
)

/**
 * Monta o uso por dia a partir da sequência de backups de um robô.
 *
 * O controlador só guarda os contadores acumulados (horímetro, horas de servo, motor
 * ligado). A diferença entre dois backups é o uso naquele intervalo, e ela é dividida
 * entre os dias do intervalo de forma proporcional ao tempo de cada dia. Com backups
 * diários, o resultado é o uso real de cada dia; com backups espaçados, é uma média.
 */
object RobotUsageHistory {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val ESTIMATED_AFTER_MS = 2 * DAY_MS

    /** Data e hora no nome que o app dá ao backup: "R10_20260919_0810.as". */
    private val FILE_NAME_DATE = Regex("""_(\d{8})_(\d{4})(?:\D|$)""")
    private val FILE_NAME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    /**
     * Lê um trecho ".OPE_INFO1" do backup. A hora do ponto vem do nome do arquivo quando
     * ele segue o padrão do app (é a hora do SAVE); senão, da data gravada no banco, que
     * num backup importado depois pode ser dias mais tarde.
     */
    fun pointFrom(timestamp: Long, fileName: String, snippet: String, zone: ZoneId = ZoneId.systemDefault()): UsagePoint {
        val info = AsRobotInfo.parse(snippet)
        val fromName = FILE_NAME_DATE.find(fileName)?.let { m ->
            try {
                java.time.LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], FILE_NAME_FORMAT)
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
        }
        return UsagePoint(fromName ?: timestamp, info.hourMeterHours, info.servoOnHours, info.motorOnCount)
    }

    /**
     * Uso por dia, do dia do primeiro backup com contadores até o do último. Pares de
     * backups em que um contador diminuiu (troca de placa, contador zerado) são ignorados
     * para aquele contador.
     */
    fun daily(points: List<UsagePoint>, zone: ZoneId = ZoneId.systemDefault()): List<DailyUsage> {
        val valid = points.filter { it.operatingHours != null || it.poweredHours != null }
            .sortedBy { it.timestamp }
            .distinctBy { it.timestamp }
        if (valid.size < 2) return emptyList()

        class Acc(var op: Double = 0.0, var pw: Double = 0.0, var mt: Double = 0.0, var est: Boolean = false)
        val days = sortedMapOf<LocalDate, Acc>()

        valid.zipWithNext { a, b ->
            val span = (b.timestamp - a.timestamp).toDouble()
            val dOp = delta(a.operatingHours, b.operatingHours)
            val dPw = delta(a.poweredHours, b.poweredHours)
            val dMt = delta(a.motorOnCount?.toDouble(), b.motorOnCount?.toDouble())
            val estimated = b.timestamp - a.timestamp > ESTIMATED_AFTER_MS

            var start = a.timestamp
            while (start < b.timestamp) {
                val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(nextDay, b.timestamp)
                val share = (end - start) / span
                val acc = days.getOrPut(date) { Acc() }
                acc.op += dOp * share
                acc.pw += dPw * share
                acc.mt += dMt * share
                acc.est = acc.est || estimated
                start = end
            }
        }

        return days.map { (date, acc) -> DailyUsage(date, acc.op, acc.pw, acc.mt, acc.est) }
    }

    private fun delta(from: Double?, to: Double?): Double =
        if (from == null || to == null || to < from) 0.0 else to - from
}
