package my.robots.core.common.ascode

/**
 * Lê e troca blocos `.PROGRAM nome(...)` ... `.END` dentro do texto de um backup AS.
 *
 * Todas as funções comparam o NOME EXATO do programa (sem diferenciar maiúsculas).
 * Antes, cada tela fazia `startsWith(".PROGRAM $nome")`: com "pg1" e "pg10" no mesmo
 * backup, abrir "pg1" podia mostrar o "pg10", e salvar apagava os dois blocos.
 *
 * O texto devolvido usa "\n" como quebra de linha (o mesmo que o app já gravava).
 */
object AsProgramBlocks {

    /**
     * Cabeçalho de programa: ".PROGRAM", espaço, o nome (até "(" ou espaço) e o resto
     * (parâmetros, "@data hora#N" e ";comentário"). Grupos: 1 = prefixo, 2 = nome, 3 = resto.
     */
    private val HEADER_REGEX = Regex("""^(\s*\.PROGRAM\s+)([^(\s]+)(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * Data/hora e comentário lidos de ".PROGRAM nome(params)@dd/mm/aa hh:mm#N;comentário".
     * Cada parte depois do nome é opcional (backups mais antigos podem não ter data ou
     * comentário). Grupos: 1 = data (dd/mm/aa), 2 = hora (hh:mm), 3 = comentário.
     * (Era a PROGRAM_HEADER_REGEX do RobotDashboardViewModel, sem mudança.)
     */
    private val HEADER_DETAILS_REGEX = Regex(
        """\.PROGRAM\s+\S+?\([^)]*\)(?:@([^\s#;]+)\s+([^\s#;]+))?(?:#[^;]*)?(?:;(.*))?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Partes opcionais do cabeçalho de um programa.
     * - modifiedAt: "dd/mm/aa hh:mm", ou vazio se faltar a data ou a hora.
     * - comment: o texto depois do ";", ou vazio.
     */
    data class Header(val modifiedAt: String, val comment: String)

    /**
     * Lê data/hora e comentário do cabeçalho. Devolve null se a linha não casar com o formato
     * (por exemplo, um cabeçalho sem os parênteses dos parâmetros).
     */
    fun parseHeader(line: String): Header? {
        val match = HEADER_DETAILS_REGEX.find(line) ?: return null
        val date = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val time = match.groupValues.getOrNull(2)?.trim().orEmpty()
        return Header(
            modifiedAt = if (date.isNotEmpty() && time.isNotEmpty()) "$date $time" else "",
            comment = match.groupValues.getOrNull(3)?.trim().orEmpty()
        )
    }

    /**
     * Nome do programa se a linha abre um programa (".PROGRAM nome(...)"); senão, null.
     */
    fun programName(line: String): String? = HEADER_REGEX.find(line)?.groupValues?.get(2)

    /**
     * true se a linha fecha um bloco (".END").
     */
    fun isEnd(line: String): Boolean = line.trim().equals(".END", ignoreCase = true)

    /**
     * Nomes de todos os programas do texto, na ordem em que aparecem.
     */
    fun list(content: String): List<String> = content.lineSequence().mapNotNull { programName(it) }.toList()

    /**
     * Devolve o bloco do programa (do ".PROGRAM" ao ".END", cada linha terminada em "\n"),
     * ou null se não existir. Se o arquivo acabar sem ".END", vai até o fim.
     */
    fun extract(content: String, name: String): String? {
        val lines = content.lines()
        val range = findBlock(lines, name) ?: return null
        return buildString { for (i in range) append(lines[i]).append('\n') }
    }

    /**
     * Junta num texto só os blocos de todos os programas pedidos, na ordem do backup.
     * Nomes que não existem são ignorados.
     */
    fun extractMany(content: String, names: Collection<String>): String {
        val wanted = names.map { it.lowercase() }.toSet()
        val result = StringBuilder()
        var reading = false
        for (line in content.lines()) {
            programName(line)?.let { reading = it.lowercase() in wanted }
            if (reading) {
                result.append(line).append('\n')
                if (isEnd(line)) reading = false
            }
        }
        return result.toString()
    }

    /**
     * Troca o bloco do programa `name` por `newBlock`, sem tocar em mais nada.
     * Se houver dois blocos com o mesmo nome, só o primeiro é trocado. Se o programa não
     * existir, `newBlock` vai para o fim do texto (para não perder o que foi editado).
     */
    fun replace(content: String, name: String, newBlock: String): String {
        val lines = content.lines()
        val newLines = newBlock.trimEnd('\n', '\r').lines()
        val range = findBlock(lines, name)
        val result = if (range == null) {
            lines.dropLastWhile { it.isBlank() } + newLines
        } else {
            lines.subList(0, range.first) + newLines + lines.subList(range.last + 1, lines.size)
        }
        return result.joinToString("\n")
    }

    /**
     * Remove os blocos de todos os programas da lista (texto do resto fica igual).
     */
    fun remove(content: String, names: Collection<String>): String {
        val unwanted = names.map { it.lowercase() }.toSet()
        val result = StringBuilder()
        var skipping = false
        for (line in content.lines()) {
            val name = programName(line)
            if (name != null && name.lowercase() in unwanted) {
                skipping = true
                continue
            }
            if (skipping) {
                if (isEnd(line)) skipping = false
                continue
            }
            result.append(line).append('\n')
        }
        return result.toString()
    }

    /**
     * Troca só o nome no cabeçalho do bloco (a primeira linha), mantendo parâmetros,
     * data e comentário. Se a primeira linha não for um cabeçalho, devolve o bloco igual.
     */
    /**
     * Troca o comentário do cabeçalho do bloco (o texto depois do ";" da linha .PROGRAM). Sem
     * comentário, acrescenta ";comentário". Comentário vazio tira o ";…". O resto do bloco não muda.
     */
    fun setHeaderComment(block: String, comment: String): String {
        val firstBreak = block.indexOf('\n')
        val header = if (firstBreak == -1) block else block.substring(0, firstBreak)
        val rest = if (firstBreak == -1) "" else block.substring(firstBreak)
        val cr = header.endsWith("\r")
        val line = header.removeSuffix("\r")
        // o ";" do comentário vem depois dos parênteses dos parâmetros
        val close = line.indexOf(')')
        val semi = if (close >= 0) line.indexOf(';', close) else line.indexOf(';')
        val base = (if (semi >= 0) line.substring(0, semi) else line).trimEnd()
        val clean = comment.trim().replace("\n", " ")
        val newHeader = if (clean.isEmpty()) base else "$base;$clean"
        return newHeader + (if (cr) "\r" else "") + rest
    }

    fun renameHeader(block: String, newName: String): String {
        val firstBreak = block.indexOf('\n')
        val header = if (firstBreak == -1) block else block.substring(0, firstBreak)
        val rest = if (firstBreak == -1) "" else block.substring(firstBreak)
        val match = HEADER_REGEX.find(header) ?: return block
        val (prefix, _, tail) = match.destructured
        return prefix + newName + tail + rest
    }

    /**
     * Índices (início..fim, inclusive) do primeiro bloco com esse nome exato, ou null.
     */
    private fun findBlock(lines: List<String>, name: String): IntRange? {
        val start = lines.indexOfFirst { programName(it)?.equals(name, ignoreCase = true) == true }
        if (start == -1) return null
        var end = lines.lastIndex
        for (i in start + 1..lines.lastIndex) {
            if (isEnd(lines[i])) {
                end = i
                break
            }
        }
        return start..end
    }
}
