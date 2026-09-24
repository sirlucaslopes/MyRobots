package my.robots.feature.codeeditor

/**
 * Filtro de quais comandos de movimento considerar ao procurar pontos nas linhas
 * selecionadas: só LMOVE (pontos cartesianos), só JMOVE (pontos de junta) ou ambos.
 */
enum class MoveFilter(val label: String) {
    LMOVE("Só LMOVE"),
    JMOVE("Só JMOVE"),
    BOTH("LMOVE e JMOVE")
}

/**
 * Um campo (eixo) de um ponto no formato FRAME: "NOME X Y Z O A T [E7 E8 E9]".
 * fieldIndex é a posição do valor depois do nome (0 = primeiro valor).
 */
enum class PointAxis(val label: String, val fieldIndex: Int) {
    X("X", 0), Y("Y", 1), Z("Z", 2),
    O("O", 3), A("A", 4), T("T", 5),
    E7("Eixo 7", 6), E8("Eixo 8", 7), E9("Eixo 9", 8)
}

/**
 * Resultado de uma transformação de pontos (Deslocar/Espelhar).
 *
 * - error: preenchido quando a operação nem chegou a rodar (ex.: seleção fora de um único
 *   programa, ou nenhum ponto LMOVE/JMOVE encontrado). Nesse caso "lines" é igual ao original.
 * - changedPoints / skippedPoints: nomes dos pontos alterados e dos ignorados (porque são
 *   usados em outro programa, ou fora de qualquer programa).
 */
data class PointTransformResult(
    val lines: List<String>,
    val changedPoints: List<String>,
    val skippedPoints: List<String>,
    val error: String? = null
) {
    val summary: String
        get() = error ?: buildString {
            append("${changedPoints.size} ponto(s) alterado(s)")
            if (changedPoints.isNotEmpty()) append(": ${changedPoints.joinToString(", ")}")
            if (skippedPoints.isNotEmpty()) {
                append("\n${skippedPoints.size} ignorado(s) (usado(s) fora do programa): ")
                append(skippedPoints.joinToString(", "))
            }
        }
}

private val MOVE_REGEX = Regex("""^\s*(LMOVE|JMOVE)\s+#?([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
private val VAR_SECTION_HEADERS = setOf(".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", ".JOINT", ".POINT")

/** Para cada linha, o nome do .PROGRAM que a contém (null se estiver fora de qualquer um). */
private fun mapLinesToPrograms(lines: List<String>): Array<String?> {
    val owner = arrayOfNulls<String>(lines.size)
    var current: String? = null
    for (i in lines.indices) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
            current = trimmed.substringAfter(".PROGRAM").substringBefore("(").trim().ifEmpty { "?" }
        }
        owner[i] = current
        if (current != null && trimmed.equals(".END", ignoreCase = true)) {
            current = null
        }
    }
    return owner
}

/**
 * Ponto (nome em maiúsculas) -> conjunto de programas que o usam em algum LMOVE/JMOVE
 * (em todo o arquivo). "?" representa um uso fora de qualquer .PROGRAM.
 */
private fun mapPointUsageToPrograms(lines: List<String>, owner: Array<String?>): Map<String, Set<String>> {
    val usage = mutableMapOf<String, MutableSet<String>>()
    for (i in lines.indices) {
        val match = MOVE_REGEX.find(lines[i]) ?: continue
        val point = match.groupValues[2].uppercase()
        usage.getOrPut(point) { mutableSetOf() }.add(owner[i] ?: "?")
    }
    return usage
}

/** Pontos LMOVE/JMOVE (conforme o filtro) referenciados nas linhas indicadas, sem repetir. */
private fun candidatePoints(lines: List<String>, selected: List<Int>, filter: MoveFilter): List<String> {
    val result = LinkedHashSet<String>()
    for (i in selected.sorted()) {
        val line = lines.getOrNull(i) ?: continue
        val match = MOVE_REGEX.find(line) ?: continue
        val command = match.groupValues[1].uppercase()
        val matchesFilter = when (filter) {
            MoveFilter.LMOVE -> command == "LMOVE"
            MoveFilter.JMOVE -> command == "JMOVE"
            MoveFilter.BOTH -> true
        }
        if (matchesFilter) result.add(match.groupValues[2].uppercase())
    }
    return result.toList()
}

/**
 * Acha a linha de definição (formato FRAME, sem "=") de um ponto dentro de uma das seções
 * de variáveis conhecidas (.TRANS, .POS, .JOINT...). Devolve o índice da linha, ou null.
 */
private fun findPointDefinitionLine(lines: List<String>, pointName: String): Int? {
    var inVarSection = false
    for (i in lines.indices) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith(".")) {
            inVarSection = trimmed.uppercase() in VAR_SECTION_HEADERS
            continue
        }
        if (!inVarSection) continue
        if (trimmed.equals(".END", ignoreCase = true)) {
            inVarSection = false
            continue
        }
        if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.contains("=")) continue
        val firstToken = trimmed.substringBefore(' ')
        if (firstToken.equals(pointName, ignoreCase = true)) return i
    }
    return null
}

/** Formata newValue com a mesma quantidade de casas decimais que o texto original tinha. */
private fun formatNumberLike(original: String, newValue: Double): String {
    val decimals = original.substringAfter('.', "").takeWhile { it.isDigit() }.length
    val digits = if (decimals in 1..6) decimals else 3
    return "%.${digits}f".format(newValue)
}

/**
 * Motor comum das transformações de ponto:
 * 1. Confere que as linhas selecionadas pertencem a um único .PROGRAM.
 * 2. Lista os pontos LMOVE/JMOVE candidatos nelas (conforme o filtro).
 * 3. Só altera os pontos usados EXCLUSIVAMENTE dentro desse programa — os que aparecem em
 *    outro programa (ou fora de qualquer um) são ignorados, para não mexer em pontos
 *    compartilhados.
 * 4. Reescreve a linha de definição de cada ponto elegível usando [transformValues], que
 *    recebe os valores atuais (como texto, sem o nome do ponto) e devolve os novos valores.
 */
private fun transformPoints(
    lines: List<String>,
    selectedIndices: List<Int>,
    moveFilter: MoveFilter,
    transformValues: (List<String>) -> List<String>
): PointTransformResult {
    if (selectedIndices.isEmpty()) {
        return PointTransformResult(lines, emptyList(), emptyList(), error = "Nenhuma linha selecionada.")
    }

    val owner = mapLinesToPrograms(lines)
    val targetPrograms = selectedIndices.mapNotNull { owner.getOrNull(it) }.toSet()
    if (targetPrograms.size != 1) {
        return PointTransformResult(
            lines, emptyList(), emptyList(),
            error = "Selecione linhas de um único programa (.PROGRAM ... .END)."
        )
    }
    val targetProgram = targetPrograms.first()

    val candidates = candidatePoints(lines, selectedIndices, moveFilter)
    if (candidates.isEmpty()) {
        return PointTransformResult(
            lines, emptyList(), emptyList(),
            error = "Nenhuma linha LMOVE/JMOVE encontrada na seleção."
        )
    }

    val usage = mapPointUsageToPrograms(lines, owner)
    val changed = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val newLines = lines.toMutableList()

    for (point in candidates) {
        if (usage[point] != setOf(targetProgram)) {
            skipped.add(point)
            continue
        }
        val defIndex = findPointDefinitionLine(newLines, point)
        if (defIndex == null) {
            skipped.add(point)
            continue
        }
        val defLine = newLines[defIndex]
        val leadingSpace = defLine.takeWhile { it.isWhitespace() }
        val tokens = defLine.trim().split(Regex("\\s+"))
        val name = tokens.firstOrNull() ?: continue
        val values = tokens.drop(1)
        val newValues = transformValues(values)
        newLines[defIndex] = leadingSpace + (listOf(name) + newValues).joinToString(" ")
        changed.add(point)
    }

    return PointTransformResult(newLines, changed, skipped)
}

/**
 * Desloca (soma um delta) os eixos indicados dos pontos LMOVE/JMOVE elegíveis referenciados
 * na seleção. Eixos com delta 0.0 não são tocados. Um ponto com menos valores do que o eixo
 * pedido (ex.: sem eixo externo ainda) é completado com "0.000" antes de somar.
 */
fun applyPointShift(
    lines: List<String>,
    selectedIndices: List<Int>,
    moveFilter: MoveFilter,
    deltas: Map<PointAxis, Double>
): PointTransformResult = transformPoints(lines, selectedIndices, moveFilter) { values ->
    val result = values.toMutableList()
    deltas.forEach { (axis, delta) ->
        if (delta == 0.0) return@forEach
        while (result.size <= axis.fieldIndex) result.add("0.000")
        val current = result[axis.fieldIndex].toDoubleOrNull() ?: 0.0
        result[axis.fieldIndex] = formatNumberLike(result[axis.fieldIndex], current + delta)
    }
    result
}

/**
 * Espelha (multiplica por -1) o eixo escolhido (X, Y ou Z) dos pontos LMOVE/JMOVE elegíveis
 * referenciados na seleção.
 */
fun applyPointMirror(
    lines: List<String>,
    selectedIndices: List<Int>,
    moveFilter: MoveFilter,
    axis: PointAxis
): PointTransformResult = transformPoints(lines, selectedIndices, moveFilter) { values ->
    val result = values.toMutableList()
    while (result.size <= axis.fieldIndex) result.add("0.000")
    val current = result[axis.fieldIndex].toDoubleOrNull() ?: 0.0
    result[axis.fieldIndex] = formatNumberLike(result[axis.fieldIndex], -current)
    result
}
