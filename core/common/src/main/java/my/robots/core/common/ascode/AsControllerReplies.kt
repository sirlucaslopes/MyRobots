package my.robots.core.common.ascode

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Leitura das respostas do terminal usadas nas checagens depois do login (ID e relógio).
 * As funções recebem o texto do terminal e não mexem nele.
 */
object AsControllerReplies {

    /** Resposta do comando ID: "Robot name: KJ264-A001   Num of axes 7   Serial No. 2503". */
    val SERIAL = Regex("""Serial No\.\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Comando que lê a data e a hora do controlador sem pedir nada. A resposta é uma linha
     * "2026/10/03 07:45:47" ([CLOCK]).
     */
    const val READ_CLOCK_COMMAND = "PRINT \$DATE(3),\" \",\$TIME"
    val CLOCK = Regex("""(\d{4})/(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

    private val SET_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")

    /** Série da última resposta do ID no texto, ou null. */
    fun parseSerial(text: String): String? = SERIAL.findAll(text).lastOrNull()?.groupValues?.get(1)

    /** Data e hora da última resposta do [READ_CLOCK_COMMAND] no texto, ou null. */
    fun parseClock(text: String): LocalDateTime? {
        val m = CLOCK.findAll(text).lastOrNull() ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /**
     * Comando que acerta o relógio: "TIME aa-mm-dd hh:mm:ss". O controlador pode responder
     * mostrando a hora e perguntando "Change? (If not, Press RETURN only.)"; quem manda o
     * comando responde com um Enter vazio e confere de novo com [READ_CLOCK_COMMAND].
     */
    fun setClockCommand(time: LocalDateTime): String = "TIME " + time.format(SET_FORMAT)
}
