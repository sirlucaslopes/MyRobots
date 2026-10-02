package my.robots.core.common.ascode

/**
 * Memória de programas do controlador, lida com o comando FREE do terminal.
 * - totalKb / freeKb: total e disponível, em KB.
 * - readAt: quando foi lida (epoch ms).
 */
data class ControllerMemory(
    val totalKb: Long,
    val freeKb: Long,
    val readAt: Long
) {
    val freePercent: Int get() = if (totalKb <= 0) 0 else ((freeKb * 100) / totalKb).toInt()
}

/**
 * Lê a resposta do comando FREE. O controlador responde, por exemplo:
 * ```
 * Total memory, 8192 KBbytes.
 * Available memory size 8175 KBbytes.( 99 %)
 * ```
 * Aceita o valor em KB ("KB", "KBbytes") ou em bytes, e convertido para KB.
 */
object AsFreeMemory {
    private val TOTAL = Regex("""Total memory\s*,?\s*(\d+)\s*(KB|bytes)""", RegexOption.IGNORE_CASE)
    private val AVAILABLE = Regex("""Available memory(?: size)?\s*,?\s*(\d+)\s*(KB|bytes)""", RegexOption.IGNORE_CASE)

    /** Quantas respostas do FREE aparecem no texto (para saber se chegou uma nova). */
    fun countAnswers(text: String): Int = AVAILABLE.findAll(text).count()

    /** A última resposta do FREE no texto, ou null se não houver uma completa. */
    fun parseLast(text: String, readAt: Long = System.currentTimeMillis()): ControllerMemory? {
        val total = TOTAL.findAll(text).lastOrNull() ?: return null
        val available = AVAILABLE.findAll(text).lastOrNull() ?: return null
        if (available.range.first < total.range.first) return null
        return ControllerMemory(toKb(total), toKb(available), readAt)
    }

    private fun toKb(m: MatchResult): Long {
        val value = m.groupValues[1].toLong()
        return if (m.groupValues[2].equals("bytes", ignoreCase = true)) value / 1024 else value
    }
}
