package my.robots.core.common.ascode

/**
 * Transferência de programas de um robô mestre para o escravo (ex.: Primer -> Top Coat).
 *
 * Os robôs dos dois projetos fazem a mesma trajetória, só em outra altura; a diferença já foi
 * calculada numa variável do escravo (ex.: top_offset). Por isso, no programa que vai para o
 * escravo, cada "BASE <frame>" vira "BASE <frame>+<offset>". "BASE NULL", bases escritas com
 * números e linhas que já somam o offset ficam como estão. Recuo e comentário são mantidos.
 *
 * O frame da base (ex.: fr_[100], na .TRANS) pode ir junto, sem mudança: o offset é aplicado
 * pela soma na linha BASE, não no frame.
 */
object AsMasterTransfer {

    /** "  BASE fr_[100]+x ; comentário" -> recuo, expressão, resto (comentário). */
    private val BASE_LINE = Regex("""^(\s*BASE\s+)([^;]*?)(\s*(;.*)?)$""", RegexOption.IGNORE_CASE)

    /** Nome de variável de pose: letra, depois letras/números/_/. e índice opcional "[n]". */
    private val FRAME_NAME = Regex("""^[A-Za-z][\w.]*(\[\d+(,\d+)*])?$""")

    /**
     * Soma [offset] em cada linha BASE do texto (um ou mais blocos .PROGRAM). Devolve o texto
     * novo e quantas linhas mudaram.
     */
    fun applyBaseOffset(code: String, offset: String): Pair<String, Int> {
        var changed = 0
        val out = code.lines().joinToString("\n") { line ->
            val m = BASE_LINE.matchEntire(line) ?: return@joinToString line
            val (head, expr, tail) = m.destructured
            val first = expr.trim().substringBefore('+').trim()
            val skip = expr.isBlank() ||
                first.equals("NULL", ignoreCase = true) ||
                !FRAME_NAME.matches(first) ||
                expr.replace(" ", "").contains("+$offset", ignoreCase = true)
            if (skip) line else { changed++; "$head${expr.trim()}+$offset$tail" }
        }
        return out to changed
    }

    /** Frames usados nas linhas BASE do texto (o primeiro termo da expressão), sem NULL. */
    fun framesUsed(code: String): List<String> = code.lines().mapNotNull { line ->
        val m = BASE_LINE.matchEntire(line) ?: return@mapNotNull null
        val first = m.groupValues[2].trim().substringBefore('+').trim()
        first.takeIf { !it.equals("NULL", ignoreCase = true) && FRAME_NAME.matches(it) }
    }.distinct()

    /**
     * Linhas da .TRANS do backup com as poses pedidas (pelo nome exato), na ordem do backup.
     * Devolve as linhas achadas e os nomes que não existem no backup.
     */
    fun transLines(backup: String, names: Collection<String>): Pair<List<String>, List<String>> {
        val wanted = names.toSet()
        val found = mutableListOf<String>()
        val foundNames = mutableSetOf<String>()
        var inTrans = false
        for (line in backup.lines()) {
            val t = line.trim()
            when {
                t.equals(".TRANS", ignoreCase = true) -> inTrans = true
                t.equals(".END", ignoreCase = true) -> inTrans = false
                inTrans && t.isNotEmpty() && !t.startsWith(";") -> {
                    val name = t.split(Regex("\\s+")).first()
                    if (name in wanted && foundNames.add(name)) found.add(line)
                }
            }
        }
        return found to wanted.filter { it !in foundNames }
    }

    /** true se o backup define a pose [name] na .TRANS (ex.: o offset no robô escravo). */
    fun definesPose(backup: String, name: String): Boolean = transLines(backup, listOf(name)).second.isEmpty()

    /**
     * Arquivo para o LOAD no escravo: os programas (já com o offset) e, se houver, as linhas
     * da .TRANS com os frames.
     */
    fun buildFile(programs: String, frameLines: List<String>): String = buildString {
        append(programs.trimEnd()).append('\n')
        if (frameLines.isNotEmpty()) {
            append(".TRANS\n")
            frameLines.forEach { append(it).append('\n') }
            append(".END\n")
        }
    }
}
