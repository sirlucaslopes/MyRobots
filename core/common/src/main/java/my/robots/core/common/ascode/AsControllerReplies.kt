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
     * Relógio (AS Language Reference Manual, E Series, 5-57: "TIME year-month-day
     * hour:minute:second"). Só "TIME" mostra a data e a hora e pergunta se quer mudar:
     * ```
     * TIME      26-10-03(Sat) 08:03:28
     * Change? (If not, Press RETURN only.)
     * ```
     * "TIME aa-mm-dd hh:mm:ss" ([setClockCommand]) grava a hora, mostra o valor gravado e faz a
     * mesma pergunta. Enter em branco sai da pergunta sem mudar nada. (Também funciona responder
     * a pergunta com "aa/mm/dd hh:mm:ss".) O K-ROSET mostra sempre 12 h a mais que o gravado.
     */
    const val CLOCK_COMMAND = "TIME"
    val CLOCK_REPLY = Regex("""TIME\s+(\d{2})-(\d{2})-(\d{2})\(\w+\)\s+(\d{2}):(\d{2}):(\d{2})""")
    val CHANGE_PROMPT = Regex("""Change\?""", RegexOption.IGNORE_CASE)

    private val SET_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")

    /** Série da última resposta do ID no texto, ou null. */
    fun parseSerial(text: String): String? = SERIAL.findAll(text).lastOrNull()?.groupValues?.get(1)

    /** Data e hora da última resposta do TIME no texto (ano com 2 dígitos = 20aa), ou null. */
    fun parseClock(text: String): LocalDateTime? {
        val m = CLOCK_REPLY.findAll(text).lastOrNull() ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try {
            LocalDateTime.of(2000 + y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /** Comando que grava o relógio, como no manual: "TIME aa-mm-dd hh:mm:ss". */
    fun setClockCommand(time: LocalDateTime): String = "TIME " + time.format(SET_FORMAT)
}
