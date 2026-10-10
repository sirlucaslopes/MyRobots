package my.robots.core.common.ascode

/** Grupo de um item do arquivo a carregar. [system] = muda a configuração do controlador. */
enum class LoadKind(val label: String, val system: Boolean = false) {
    PROGRAM("Programas"),
    POSE("Posições (.TRANS)"),
    JOINT("Posições em juntas (.JOINTS)"),
    REAL("Reais (.REALS)"),
    STRING("Textos (.STRINGS)"),
    INTEGER("Inteiros (.INTEGER)"),
    DATABANK("Data Bank (.SPRDB)"),
    SYSTEM("Dados do sistema", system = true)
}

/**
 * Um item que pode ir no LOAD: um programa, uma variável, um registro do Data Bank ou uma seção
 * inteira do sistema (.SYSDATA, .AUXDATA, .NETCONF…).
 * - name: "pg100", "fr_[100]", "DB12", ".SYSDATA";
 * - detail: o que mostrar na lista (linhas e comentário, valor…);
 * - section: o cabeçalho da seção no arquivo (".TRANS", ".sprdb"…), para remontar;
 * - lines: as linhas como estão no arquivo (o bloco inteiro, ou a linha da variável).
 */
data class LoadItem(
    val kind: LoadKind,
    val name: String,
    val detail: String,
    val section: String,
    val lines: List<String>
) {
    /** Chave única na lista (tipo + nome). */
    val key: String get() = kind.name + ":" + name.replace(" ", "").lowercase()
}

/**
 * O que acontece num robô de destino com os itens escolhidos, pelo último backup dele
 * (null em [LoadCheck.backupKnown] = sem backup: não dá para saber o que já existe).
 */
data class LoadCheck(
    val backupKnown: Boolean,
    val newPrograms: List<String> = emptyList(),
    val replacedPrograms: List<String> = emptyList(),
    val newVariables: Int = 0,
    val changedVariables: List<String> = emptyList(),
    val sameVariables: Int = 0,
    val newDataBank: Int = 0,
    val replacedDataBank: List<String> = emptyList(),
    val systemSections: List<String> = emptyList()
) {
    /** Algo será substituído, mudado ou é desconhecido: a confirmação fica amarela. */
    val warn: Boolean
        get() = !backupKnown || replacedPrograms.isNotEmpty() || changedVariables.isNotEmpty() ||
            replacedDataBank.isNotEmpty() || systemSections.isNotEmpty()
}

/**
 * "Carregar" das ações em grupo: separa um arquivo AS (um backup ou um .as do aparelho) em itens,
 * monta o arquivo do LOAD só com os escolhidos e confere contra o backup de cada destino.
 */
object AsLoadFile {

    private val SECTION = Regex("""^\.([A-Za-z_][A-Za-z0-9_]*)\s*$""")
    private val LOG_SECTION = Regex("""LOG""", RegexOption.IGNORE_CASE)
    /** Seção de uma linha só, com os dados no próprio cabeçalho: ".NETCONF     192.168.0.2,…". */
    private val ONE_LINE = Regex("""^\.([A-Z][A-Z0-9_]*)\s+\S.*$""")

    /** Os itens do arquivo, na ordem em que aparecem. Logs do controlador não entram. */
    fun items(content: String): List<LoadItem> {
        val out = mutableListOf<LoadItem>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            val program = AsProgramBlocks.programName(raw)
            if (program != null) {
                val block = mutableListOf(raw.trimEnd())
                i++
                while (i < lines.size) {
                    block += lines[i].trimEnd()
                    if (AsProgramBlocks.isEnd(lines[i])) break
                    i++
                }
                val header = AsProgramBlocks.parseHeader(line)
                val body = (block.size - 2).coerceAtLeast(0)
                val comment = header?.comment.orEmpty()
                out += LoadItem(LoadKind.PROGRAM, program, "$body linhas" + if (comment.isNotBlank()) " · $comment" else "", ".PROGRAM", block)
                i++
                continue
            }
            val one = ONE_LINE.matchEntire(line)
            if (one != null) {
                val name = "." + one.groupValues[1]
                if (!LOG_SECTION.containsMatchIn(name)) out += LoadItem(LoadKind.SYSTEM, name, line.removePrefix(name).trim(), name, listOf(raw.trimEnd()))
                i++
                continue
            }
            val m = SECTION.matchEntire(line)
            if (m == null || line.equals(".END", ignoreCase = true)) { i++; continue }
            val varKind = AsVarKind.ofSection(line)
            val isDb = line.equals(".SPRDB", ignoreCase = true)
            // lê a seção até o .END (ou até a próxima seção, nas que não têm .END)
            val body = mutableListOf<String>()
            var hasEnd = false
            i++
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.equals(".END", ignoreCase = true)) { hasEnd = true; i++; break }
                if (SECTION.matches(t) || ONE_LINE.matches(t) || AsProgramBlocks.programName(lines[i]) != null) break
                body += lines[i].trimEnd()
                i++
            }
            when {
                varKind != null -> body.filter { it.isNotBlank() && !it.trim().startsWith(";") }.forEach { l ->
                    val t = l.trim()
                    val name = t.split(Regex("""[\s=]"""), limit = 2)[0]
                    out += LoadItem(kindOf(varKind), name, t.removePrefix(name).trim().removePrefix("=").trim(), line, listOf(l))
                }
                isDb -> body.filter { it.isNotBlank() && !it.trim().startsWith(";") }.forEach { l ->
                    val t = l.trim()
                    val name = t.split(Regex("""\s+"""), limit = 2)[0]
                    out += LoadItem(LoadKind.DATABANK, name, t.removePrefix(name).trim(), line, listOf(l))
                }
                LOG_SECTION.containsMatchIn(m.groupValues[1]) -> {}
                else -> out += LoadItem(
                    LoadKind.SYSTEM, line.uppercase(), "${body.size} linhas", line,
                    listOf(line) + body + if (hasEnd) listOf(".END") else emptyList()
                )
            }
        }
        return out
    }

    private fun kindOf(k: AsVarKind) = when (k) {
        AsVarKind.POSE -> LoadKind.POSE
        AsVarKind.JOINT -> LoadKind.JOINT
        AsVarKind.REAL -> LoadKind.REAL
        AsVarKind.STRING -> LoadKind.STRING
        AsVarKind.INTEGER -> LoadKind.INTEGER
    }

    /**
     * O arquivo do LOAD com os [chosen]: seções do sistema e programas como estão no original;
     * as variáveis dentro da seção delas (cabeçalho, as linhas escolhidas, .END) e o Data Bank
     * na .sprdb. Ordem: sistema, programas, variáveis, Data Bank.
     */
    fun build(chosen: List<LoadItem>): String = buildString {
        chosen.filter { it.kind == LoadKind.SYSTEM }.forEach { s -> s.lines.forEach { appendLine(it) } }
        chosen.filter { it.kind == LoadKind.PROGRAM }.forEach { p -> p.lines.forEach { appendLine(it) } }
        chosen.filter { it.kind != LoadKind.SYSTEM && it.kind != LoadKind.PROGRAM }
            .groupBy { it.section.trim().uppercase() }
            .forEach { (_, items) ->
                appendLine(items.first().section.trim())
                items.forEach { appendLine(it.lines.first()) }
                appendLine(".END")
            }
    }

    /** Confere os [chosen] contra o backup do destino ([target] = null: robô sem backup). */
    fun check(chosen: List<LoadItem>, target: String?): LoadCheck {
        val programs = chosen.filter { it.kind == LoadKind.PROGRAM }.map { it.name }
        val vars = chosen.filter { it.kind !in setOf(LoadKind.PROGRAM, LoadKind.DATABANK, LoadKind.SYSTEM) }
        val db = chosen.filter { it.kind == LoadKind.DATABANK }
        val system = chosen.filter { it.kind == LoadKind.SYSTEM }.map { it.name }
        if (target == null) {
            return LoadCheck(backupKnown = false, newPrograms = programs, newVariables = vars.size, newDataBank = db.size, systemSections = system)
        }
        val existing = items(target)
        val byKey = existing.associateBy { it.key }
        val (replaced, created) = programs.partition { n -> byKey.containsKey(LoadKind.PROGRAM.name + ":" + n.lowercase()) }
        var newVars = 0
        var same = 0
        val changed = mutableListOf<String>()
        vars.forEach { v ->
            val old = byKey[v.key]
            when {
                old == null -> newVars++
                AsBackupDiff.sameValue(old.detail, v.detail) -> same++
                else -> changed += v.name
            }
        }
        val (dbReplaced, dbNew) = db.partition { byKey.containsKey(it.key) }
        return LoadCheck(
            backupKnown = true,
            newPrograms = created,
            replacedPrograms = replaced,
            newVariables = newVars,
            changedVariables = changed,
            sameVariables = same,
            newDataBank = dbNew.size,
            replacedDataBank = dbReplaced.map { it.name },
            systemSections = system
        )
    }
}
