package my.robots.core.common.ascode

/** Onde um item está na comparação "offline × robô". */
enum class DiffStatus { ONLY_OFFLINE, ONLY_ROBOT, DIFFERENT, EQUAL }

/**
 * Um programa na comparação. [changedLines] = linhas que entram ou saem (0 quando iguais);
 * [dateOnly] = mesmo código, só a data/contador do cabeçalho mudou.
 */
data class ProgramDiff(
    val name: String,
    val status: DiffStatus,
    val offline: AsProgram?,
    val robot: AsProgram?,
    val changedLines: Int = 0,
    val dateOnly: Boolean = false
)

/** Uma variável na comparação, com o valor de cada lado (null = não existe ali). */
data class VarDiff(
    val kind: AsVarKind,
    val name: String,
    val status: DiffStatus,
    val offline: AsVar?,
    val robot: AsVar?
) {
    /** A variável que está no robô (para apagar) ou, se não houver, a do offline. */
    val any: AsVar get() = robot ?: offline!!
}

data class BackupComparison(val programs: List<ProgramDiff>, val variables: List<VarDiff>) {
    fun programs(status: DiffStatus) = programs.filter { it.status == status }
    fun variables(status: DiffStatus) = variables.filter { it.status == status }
}

/** Uma linha da diferença: igual, só no offline (−) ou só no robô (+), com o número em cada lado. */
data class DiffLine(val kind: Kind, val text: String, val offlineLine: Int?, val robotLine: Int?) {
    enum class Kind { SAME, OFFLINE, ROBOT }
}

/**
 * Compara dois backups como o "Comparar" do KIDE: o offline (o arquivo do app) e o do robô.
 * Programas pelo nome (sem diferença de maiúsculas), pelo código e pelo cabeçalho sem a data;
 * variáveis pelo tipo e nome, e o valor com os números comparados como número.
 */
object AsBackupDiff {

    fun compare(offline: AsInventory, robot: AsInventory): BackupComparison {
        val off = offline.programs.associateBy { it.name.lowercase() }
        val rob = robot.programs.associateBy { it.name.lowercase() }
        val programs = (off.keys + rob.keys).map { key ->
            val a = off[key]
            val b = rob[key]
            when {
                a == null -> ProgramDiff(b!!.name, DiffStatus.ONLY_ROBOT, null, b)
                b == null -> ProgramDiff(a.name, DiffStatus.ONLY_OFFLINE, a, null)
                else -> {
                    val sameBody = normalize(a.body) == normalize(b.body)
                    val sameHeader = a.headerKey == b.headerKey
                    if (sameBody && sameHeader) {
                        ProgramDiff(a.name, DiffStatus.EQUAL, a, b, dateOnly = a.header.trim() != b.header.trim())
                    } else {
                        val changed = if (sameBody) 1 else lines(a.body, b.body).count { it.kind != DiffLine.Kind.SAME }
                        ProgramDiff(a.name, DiffStatus.DIFFERENT, a, b, changedLines = changed)
                    }
                }
            }
        }.sortedWith(compareBy({ it.status.ordinal }, { it.name.lowercase() }))

        val offVars = offline.variables.associateBy { it.key }
        val robVars = robot.variables.associateBy { it.key }
        val variables = (offVars.keys + robVars.keys).map { key ->
            val a = offVars[key]
            val b = robVars[key]
            val v = a ?: b!!
            val status = when {
                a == null -> DiffStatus.ONLY_ROBOT
                b == null -> DiffStatus.ONLY_OFFLINE
                sameValue(a.value, b.value) -> DiffStatus.EQUAL
                else -> DiffStatus.DIFFERENT
            }
            VarDiff(v.kind, v.name, status, a, b)
        }.sortedWith(compareBy({ it.status.ordinal }, { it.kind.ordinal }, { it.name.lowercase() }))

        return BackupComparison(programs, variables)
    }

    /** Valores iguais: mesmas palavras, com números comparados como número (0.000000 = 0). */
    fun sameValue(a: String, b: String): Boolean {
        val ta = a.trim().split(Regex("""\s+"""))
        val tb = b.trim().split(Regex("""\s+"""))
        if (ta.size != tb.size) return false
        return ta.zip(tb).all { (x, y) ->
            val nx = x.toDoubleOrNull()
            val ny = y.toDoubleOrNull()
            if (nx != null && ny != null) kotlin.math.abs(nx - ny) < 1e-4 else x == y
        }
    }

    private fun normalize(body: List<String>) = body.map { it.trimEnd() }

    /**
     * Diferença linha a linha (maior trecho comum). Linhas iguais no começo e no fim saem
     * direto; o miolo usa a tabela do maior trecho comum até [MAX_CELLS] células. Acima disso
     * (programas enormes e muito diferentes), o miolo aparece inteiro como saiu e entrou.
     */
    fun lines(offline: List<String>, robot: List<String>): List<DiffLine> {
        val a = normalize(offline)
        val b = normalize(robot)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val out = mutableListOf<DiffLine>()
        for (i in 0 until start) out += DiffLine(DiffLine.Kind.SAME, a[i], i + 1, i + 1)

        val n = endA - start
        val m = endB - start
        if (n.toLong() * m > MAX_CELLS) {
            for (i in start until endA) out += DiffLine(DiffLine.Kind.OFFLINE, a[i], i + 1, null)
            for (j in start until endB) out += DiffLine(DiffLine.Kind.ROBOT, b[j], null, j + 1)
        } else {
            // lcs[i][j] = maior trecho comum de a[start+i..] e b[start+j..]
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && a[start + i] == b[start + j] -> {
                        out += DiffLine(DiffLine.Kind.SAME, a[start + i], start + i + 1, start + j + 1); i++; j++
                    }
                    // o que sai (offline) vem antes do que entra (robô)
                    i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                        out += DiffLine(DiffLine.Kind.OFFLINE, a[start + i], start + i + 1, null); i++
                    }
                    else -> {
                        out += DiffLine(DiffLine.Kind.ROBOT, b[start + j], null, start + j + 1); j++
                    }
                }
            }
        }
        for (k in 0 until a.size - endA) out += DiffLine(DiffLine.Kind.SAME, a[endA + k], endA + k + 1, endB + k + 1)
        return out
    }

    private const val MAX_CELLS = 4_000_000L

    /**
     * Comando que apaga só este item no controlador, sem o "/D" (apagar forçado, que também
     * leva sub-rotinas e variáveis de outros programas e que o software ASE_K80000W48 recusa):
     * "DELETE/P pg100" (só o programa), "DELETE/L fr_[100]", "DELETE/R speed", "DELETE/S \$msg".
     */
    fun deleteProgramCommand(name: String) = "DELETE/P $name"

    fun deleteVariableCommand(v: AsVar) = "DELETE${v.kind.deleteSwitch} ${v.name.replace(" ", "")}"
}
