package my.robots.core.common.layout

/** Uma vaga da grade da cabine (linha e coluna começam em 0). */
data class Cell(val row: Int, val col: Int)

/**
 * Estado da cabine durante a edição.
 *
 * - rows / cols: tamanho da grade.
 * - placed: robôs posicionados (id do robô -> vaga). Quem não está aqui fica "fora do layout".
 * - bandPositions: a faixa de cada equipamento, na mesma ordem da lista de equipamentos da
 *   tela. 0 = acima da linha 1, rows = abaixo da última.
 */
data class CabinState(
    val rows: Int,
    val cols: Int,
    val placed: Map<Int, Cell> = emptyMap(),
    val bandPositions: List<Int> = emptyList()
) {
    fun robotAt(cell: Cell): Int? = placed.entries.firstOrNull { it.value == cell }?.key
    fun isRowEmpty(row: Int) = placed.values.none { it.row == row }
    fun isColEmpty(col: Int) = placed.values.none { it.col == col }
}

/**
 * Regras da grade da cabine, sem Android (testadas na JVM). Toda função devolve um estado
 * novo; o original não muda. A tela edita uma cópia e só grava em "Salvar".
 *
 * Limites: 1 a [MAX_ROWS] linhas e 1 a [MAX_COLS] colunas. Só se remove linha ou coluna vazia,
 * e sempre sobra pelo menos uma.
 */
object LayoutOps {
    const val MAX_ROWS = 5
    const val MAX_COLS = 6

    fun canAddRow(s: CabinState) = s.rows < MAX_ROWS
    fun canAddCol(s: CabinState) = s.cols < MAX_COLS

    /** Linha nova no fim. Equipamentos abaixo da última linha continuam abaixo dela. */
    fun addRow(s: CabinState): CabinState {
        if (!canAddRow(s)) return s
        return s.copy(rows = s.rows + 1, bandPositions = s.bandPositions.map { if (it == s.rows) it + 1 else it })
    }

    fun addCol(s: CabinState): CabinState = if (canAddCol(s)) s.copy(cols = s.cols + 1) else s

    fun canRemoveRow(s: CabinState, row: Int) = s.rows > 1 && row in 0 until s.rows && s.isRowEmpty(row)
    fun canRemoveCol(s: CabinState, col: Int) = s.cols > 1 && col in 0 until s.cols && s.isColEmpty(col)

    /**
     * Remove a linha vazia [row]: os robôs de linhas abaixo sobem uma, e as faixas abaixo dela
     * também (as duas faixas em volta da linha removida passam a ser a mesma).
     */
    fun removeRow(s: CabinState, row: Int): CabinState {
        if (!canRemoveRow(s, row)) return s
        return s.copy(
            rows = s.rows - 1,
            placed = s.placed.mapValues { (_, c) -> if (c.row > row) c.copy(row = c.row - 1) else c },
            bandPositions = s.bandPositions.map { if (it > row) it - 1 else it }
        )
    }

    /** Remove a coluna vazia [col]: os robôs das colunas à direita vão uma para a esquerda. */
    fun removeCol(s: CabinState, col: Int): CabinState {
        if (!canRemoveCol(s, col)) return s
        return s.copy(
            cols = s.cols - 1,
            placed = s.placed.mapValues { (_, c) -> if (c.col > col) c.copy(col = c.col - 1) else c }
        )
    }

    /**
     * Põe o robô na vaga. Se ela estiver ocupada, os dois trocam de lugar: o outro vai para a
     * vaga antiga do robô, ou para fora do layout se o robô estava fora.
     */
    fun moveTo(s: CabinState, robotId: Int, cell: Cell): CabinState {
        if (cell.row !in 0 until s.rows || cell.col !in 0 until s.cols) return s
        val from = s.placed[robotId]
        if (from == cell) return s
        val other = s.robotAt(cell)
        val placed = s.placed.toMutableMap()
        placed[robotId] = cell
        if (other != null) {
            if (from != null) placed[other] = from else placed.remove(other)
        }
        return s.copy(placed = placed)
    }

    /** Tira o robô da grade (vai para "fora do layout"). */
    fun removeFromLayout(s: CabinState, robotId: Int): CabinState = s.copy(placed = s.placed - robotId)

    /**
     * "Posicionar todos": coloca os robôs de [robotIds] que estão fora do layout nas vagas
     * livres, por linha, da esquerda para a direita. Sem vaga, crescem as linhas (até o limite);
     * quem não couber continua fora.
     */
    fun placeAll(s: CabinState, robotIds: List<Int>): CabinState {
        var state = s
        val pending = robotIds.filter { it !in s.placed }.toMutableList()
        while (pending.isNotEmpty()) {
            val free = (0 until state.rows).flatMap { r -> (0 until state.cols).map { c -> Cell(r, c) } }
                .filter { state.robotAt(it) == null }
            if (free.isEmpty()) {
                if (!canAddRow(state)) break
                state = addRow(state)
                continue
            }
            val placed = state.placed.toMutableMap()
            free.zip(pending.toList()).forEach { (cell, id) ->
                placed[id] = cell
                pending.remove(id)
            }
            state = state.copy(placed = placed)
        }
        return state
    }

    /**
     * Saneamento do que veio do banco: limita o tamanho da grade, manda para fora do layout o
     * robô fora dos limites ou numa vaga já ocupada (fica o de menor id), e traz as faixas de
     * equipamento para dentro de 0..rows.
     */
    fun sanitize(rows: Int, cols: Int, positions: Map<Int, Cell?>, bandPositions: List<Int>): CabinState {
        val r = rows.coerceIn(1, MAX_ROWS)
        val c = cols.coerceIn(1, MAX_COLS)
        val placed = mutableMapOf<Int, Cell>()
        positions.entries.sortedBy { it.key }.forEach { (id, cell) ->
            if (cell != null && cell.row in 0 until r && cell.col in 0 until c && cell !in placed.values) {
                placed[id] = cell
            }
        }
        return CabinState(r, c, placed, bandPositions.map { it.coerceIn(0, r) })
    }
}
