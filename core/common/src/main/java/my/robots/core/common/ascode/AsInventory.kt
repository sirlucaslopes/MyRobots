package my.robots.core.common.ascode

/**
 * Tipo de variável do AS (AS Language Reference Manual, 3.4): seção do backup onde fica, prefixo
 * do nome e a opção do DELETE que apaga só ela no controlador.
 */
enum class AsVarKind(val section: String, val prefix: String, val deleteSwitch: String, val label: String) {
    POSE(".TRANS", "", "/L", "Posição"),
    JOINT(".JOINTS", "#", "/L", "Posição em juntas"),
    REAL(".REALS", "", "/R", "Real"),
    STRING(".STRINGS", "$", "/S", "Texto"),
    INTEGER(".INTEGER", "", "/INT", "Inteiro");

    companion object {
        fun ofSection(header: String): AsVarKind? {
            val h = header.trim().uppercase()
            return entries.firstOrNull { it.section == h } ?: when (h) {
                ".JOINT" -> JOINT
                ".POS", ".POINT" -> POSE
                else -> null
            }
        }
    }
}

/**
 * Uma variável do backup.
 * - name: como está no arquivo, com prefixo e índice ("fr_[100]", "#home", "\$msg");
 * - value: o resto da linha (valores da posição, o número ou o texto);
 * - line: a linha inteira, sem o recuo.
 */
data class AsVar(val kind: AsVarKind, val name: String, val value: String, val line: String) {
    /** Nome sem prefixo e sem índice, minúsculo ("fr_[100]" -> "fr_"). */
    val base: String get() = name.removePrefix(kind.prefix).substringBefore('[').trim().lowercase()

    /** Índice sem espaços ("fr_[ 100 ]" -> "100"), ou null se não for elemento de array. */
    val index: String? get() = name.substringAfter('[', "").substringBefore(']').takeIf { '[' in name }?.replace(" ", "")

    /** Chave única: tipo + nome minúsculo sem espaços. */
    val key: String get() = kind.name + ":" + name.replace(" ", "").lowercase()

    /** Variável do sistema ("!gun2"): nunca é oferecida para apagar. */
    val isSystem: Boolean get() = name.startsWith("!")
}

/** Um programa do backup: nome, linha do cabeçalho e as linhas entre o cabeçalho e o .END. */
data class AsProgram(val name: String, val header: String, val body: List<String>) {
    /** Cabeçalho sem a data e o contador ("@26/10/06 22:06#3"): parâmetros e comentário. */
    val headerKey: String get() = header.trim().replace(Regex("""@[^;]*"""), "").replace(Regex("""\s+"""), " ").lowercase()
}

/**
 * O que há num backup: programas, variáveis e o texto das outras seções (.SYSDATA,
 * .AUXDATA, painel de interface…), que também podem citar variáveis pelo nome.
 */
data class AsInventory(
    val programs: List<AsProgram>,
    val variables: List<AsVar>,
    val otherSections: Map<String, List<String>>
) {
    fun program(name: String): AsProgram? = programs.firstOrNull { it.name.equals(name, ignoreCase = true) }

    companion object {
        /** Seções de log: citam nomes de programas e variáveis de uso antigo, não contam como uso. */
        private val LOG_SECTION = Regex("""^\.[A-Z_0-9]*LOG[A-Z_0-9]*$""")

        /** Começo de seção fora de programa: ".SYSDATA", ".TRANS", ".OPE_INFO1"... */
        private val SECTION = Regex("""^\.([A-Za-z_][A-Za-z0-9_]*)\s*$""")

        /** Nome no começo de uma linha de variável: prefixo opcional, nome e índice opcional. */
        private val VAR_NAME = Regex("""^([#$]?!?[A-Za-z_!][A-Za-z0-9_.]*\s*(\[[^\]]*\])?)""")

        fun parse(content: String): AsInventory {
            val programs = mutableListOf<AsProgram>()
            val variables = mutableListOf<AsVar>()
            val others = linkedMapOf<String, MutableList<String>>()

            var program: String? = null
            var header = ""
            var body = mutableListOf<String>()
            var kind: AsVarKind? = null
            var other: MutableList<String>? = null

            for (raw in content.lines()) {
                val line = raw.trim()
                if (program != null) {
                    // dentro do programa só o .END fecha (".par = 1" é variável local, não seção)
                    if (AsProgramBlocks.isEnd(line)) {
                        programs += AsProgram(program, header, body)
                        program = null
                    } else {
                        body.add(raw.trimEnd())
                    }
                    continue
                }
                val name = AsProgramBlocks.programName(raw)
                if (name != null) {
                    program = name
                    header = line
                    body = mutableListOf()
                    kind = null
                    other = null
                    continue
                }
                if (line.equals(".END", ignoreCase = true)) {
                    kind = null
                    other = null
                    continue
                }
                if (SECTION.matches(line)) {
                    kind = AsVarKind.ofSection(line)
                    other = if (kind == null && !LOG_SECTION.matches(line.uppercase())) {
                        others.getOrPut(line.uppercase()) { mutableListOf() }
                    } else null
                    continue
                }
                val k = kind
                when {
                    k != null -> parseVariable(k, line)?.let { variables += it }
                    other != null -> other.add(line)
                }
            }
            return AsInventory(programs, variables, others)
        }

        private fun parseVariable(kind: AsVarKind, line: String): AsVar? {
            if (line.isEmpty() || line.startsWith(";")) return null
            val m = VAR_NAME.find(line) ?: return null
            var name = m.groupValues[1].trim()
            // na .JOINTS o nome pode vir sem o "#"
            if (kind.prefix.isNotEmpty() && !name.startsWith(kind.prefix)) name = kind.prefix + name
            val value = line.substring(m.range.last + 1).trim().removePrefix("=").trim()
            return AsVar(kind, name, value, line)
        }
    }
}

/**
 * Onde cada variável é usada: nos programas (pelo nome) e nas outras seções do backup (o
 * sistema de pintura cita variáveis pelo nome no .SYSDATA, por exemplo "flowrate").
 *
 * Regras (para nunca chamar de "sem uso" uma variável usada):
 * - comentário (depois do ";") e texto entre aspas dos programas não contam;
 * - variável local (".par") não é a global "par";
 * - o prefixo separa os tipos: "#home" é a posição em juntas, "\$msg" o texto;
 * - elemento de array ("fr_[100]"): conta o uso com o mesmo índice, com índice calculado
 *   ("fr_[pgnum]", "fr_[n+1]": pode ser qualquer um) e o array sem índice.
 */
object AsVariableUsage {

    /** Referência no código: prefixo, nome minúsculo e índice (null = sem colchetes). */
    private data class Ref(val prefix: String, val name: String, val index: String?)

    private val TOKEN = Regex("""(?<![A-Za-z0-9_.#$!])([#$]?)([A-Za-z_!][A-Za-z0-9_.]*)\s*(\[([^\]]*)\])?""")
    private val LITERAL_INDEX = Regex("""^[0-9,]+$""")

    /** Para cada variável (pela [AsVar.key]), os lugares onde aparece: nomes de programa ou seções. */
    fun analyze(inventory: AsInventory): Map<String, List<String>> {
        val refsByPlace = linkedMapOf<String, List<Ref>>()
        inventory.programs.forEach { p -> refsByPlace[p.name] = p.body.flatMap { refs(stripCode(it)) } }
        inventory.otherSections.forEach { (section, lines) ->
            val r = lines.flatMap { refs(it) }
            if (r.isNotEmpty()) refsByPlace[section] = r
        }
        // índice por nome para não varrer tudo a cada variável
        val byName = HashMap<String, MutableList<Pair<String, Ref>>>()
        refsByPlace.forEach { (place, refs) -> refs.forEach { byName.getOrPut(it.name) { mutableListOf() }.add(place to it) } }

        return inventory.variables.associate { v ->
            val places = byName[v.base].orEmpty()
                .filter { (_, r) -> r.prefix == v.kind.prefix && matchesIndex(v.index, r.index) }
                .map { it.first }
                .distinct()
            v.key to places
        }
    }

    /** Variáveis que não aparecem em nenhum programa nem seção (as do sistema "!" ficam de fora). */
    fun unused(inventory: AsInventory, usage: Map<String, List<String>> = analyze(inventory)): List<AsVar> =
        inventory.variables.filter { !it.isSystem && usage[it.key].isNullOrEmpty() }

    private fun matchesIndex(varIndex: String?, refIndex: String?): Boolean {
        if (varIndex == null || refIndex == null) return true
        val r = refIndex.replace(" ", "")
        if (!LITERAL_INDEX.matches(r)) return true // índice calculado: pode ser este
        return r.split(',').map { it.trimStart('0').ifEmpty { "0" } } == varIndex.split(',').map { it.trimStart('0').ifEmpty { "0" } }
    }

    private fun refs(text: String): List<Ref> = TOKEN.findAll(text).map {
        Ref(it.groupValues[1], it.groupValues[2].lowercase(), it.groups[4]?.value)
    }.toList()

    /** Tira o comentário (";" fora de aspas) e o texto entre aspas de uma linha de programa. */
    fun stripCode(line: String): String {
        val out = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                quoted -> {}
                c == ';' -> break
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

/**
 * Nome de variável do AS: prefixo do tipo opcional ("#" juntas, "$" texto), uma letra, depois
 * letras, números, "_" e ".", e índice de array opcional entre colchetes com números
 * ("fr_[100]", "p[1,2]"). É o que o controlador aceita e o que os backups já usam.
 */
object AsVariableNames {
    private val NAME = Regex("""^[#$]?[A-Za-z][A-Za-z0-9_.]*(\[\d+(,\d+)*])?$""")

    fun isValid(name: String): Boolean = NAME.matches(name.trim())

    /** O que está errado no nome, para a tela; null se estiver certo. */
    fun error(name: String): String? {
        val n = name.trim().removePrefix("#").removePrefix("$")
        return when {
            n.isEmpty() -> "O nome não pode ser vazio"
            n.contains(' ') -> "O nome não pode conter espaços"
            !n.first().isLetter() -> "O nome começa com uma letra"
            !isValid(name) -> "Use letras, números, _ e . e, num array, o índice entre colchetes: fr_[100]"
            else -> null
        }
    }

    /**
     * Sugestão de nome para a cópia que ainda não existe em [taken]: num elemento de array, o
     * próximo índice livre ("fr_[100]" -> "fr_[101]"); senão "<nome>_2", "<nome>_3"…
     */
    fun nextFree(name: String, taken: Collection<String>): String {
        val used = taken.map { it.replace(" ", "").lowercase() }.toSet()
        val m = Regex("""^(.*)\[\s*(\d+)\s*]$""").find(name.trim())
        if (m != null) {
            var i = m.groupValues[2].toInt() + 1
            while ("${m.groupValues[1]}[$i]".replace(" ", "").lowercase() in used) i++
            return "${m.groupValues[1]}[$i]"
        }
        var i = 2
        while ("${name}_$i".lowercase() in used) i++
        return "${name}_$i"
    }
}
