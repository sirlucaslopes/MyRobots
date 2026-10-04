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

    /** Palavra do padrão de frame trocada pelo número do programa ("fr_[pgnum]" → "fr_[100]"). */
    const val PGNUM = "pgnum"

    /** Número do programa: os dígitos do fim do nome ("pg100" → "100"), ou null. */
    fun programNumber(programName: String): String? = Regex("""(\d+)$""").find(programName.trim())?.groupValues?.get(1)

    /** Frame do programa pelo padrão ("fr_[pgnum]" e "pg100" → "fr_[100]"); null sem número. */
    fun frameFor(pattern: String, programName: String): String? {
        if (PGNUM !in pattern) return pattern
        val n = programNumber(programName) ?: return null
        return pattern.replace(PGNUM, n)
    }

    /** O padrão vira uma regra: "fr_[pgnum]" acha "fr_[100]", "fr_[7]"... (e só esses). */
    fun patternRegex(pattern: String): Regex =
        Regex(pattern.split(PGNUM).joinToString("""\d+""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    /**
     * Soma [offset] em cada linha BASE do texto (um ou mais blocos .PROGRAM). Com
     * [framePattern], só nas bases cujo frame segue o padrão (ex.: "fr_[pgnum]"). Devolve o
     * texto novo e quantas linhas mudaram.
     */
    fun applyBaseOffset(code: String, offset: String, framePattern: String? = null): Pair<String, Int> {
        val only = framePattern?.takeIf { it.isNotBlank() }?.let { patternRegex(it) }
        var changed = 0
        val out = code.lines().joinToString("\n") { line ->
            val m = BASE_LINE.matchEntire(line) ?: return@joinToString line
            val (head, expr, tail) = m.destructured
            val first = expr.trim().substringBefore('+').trim()
            val skip = expr.isBlank() ||
                first.equals("NULL", ignoreCase = true) ||
                !FRAME_NAME.matches(first) ||
                expr.replace(" ", "").contains("+$offset", ignoreCase = true) ||
                (only != null && !only.matches(first))
            if (skip) line else { changed++; "$head${expr.trim()}+$offset$tail" }
        }
        return out to changed
    }

    /**
     * Frames usados nas linhas BASE do texto (o primeiro termo da expressão), sem NULL. Com
     * [framePattern], só os que seguem o padrão.
     */
    fun framesUsed(code: String, framePattern: String? = null): List<String> {
        val only = framePattern?.takeIf { it.isNotBlank() }?.let { patternRegex(it) }
        return code.lines().mapNotNull { line ->
            val m = BASE_LINE.matchEntire(line) ?: return@mapNotNull null
            val first = m.groupValues[2].trim().substringBefore('+').trim()
            first.takeIf { !it.equals("NULL", ignoreCase = true) && FRAME_NAME.matches(it) && (only == null || only.matches(it)) }
        }.distinct()
    }

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
