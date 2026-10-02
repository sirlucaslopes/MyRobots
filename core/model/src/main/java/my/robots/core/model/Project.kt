package my.robots.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tamanho da grade da cabine de um projeto (linha da tabela "project_layouts").
 *
 * - projectName: o nome do projeto, igual ao `Robot.project`.
 * - rowCount / colCount: linhas e colunas da grade ("rows" não é usado porque ROWS é
 *   palavra-chave do SQLite). Um projeto sem linha nesta tabela usa o padrão 2×2, e a linha
 *   só é criada na primeira edição.
 */
@Entity(tableName = "project_layouts")
data class ProjectLayout(
    @PrimaryKey val projectName: String,
    val rowCount: Int = DEFAULT_ROWS,
    val colCount: Int = DEFAULT_COLS
) {
    companion object {
        const val DEFAULT_ROWS = 2
        const val DEFAULT_COLS = 2
    }
}

/** Tipo de equipamento desenhado na cabine. "Nenhum" é não ter linha na tabela. */
enum class EquipmentType(val displayName: String) {
    CONVEYOR("Transportador"),
    OTHER("Outro")
}

/**
 * Um equipamento da cabine (linha da tabela "project_equipment"), desenhado como uma faixa
 * entre as linhas de robôs.
 *
 * - position: em qual faixa fica. 0 = acima da linha 1, 1 = entre a linha 1 e a 2 ... e
 *   rowCount = abaixo da última linha.
 * - name: obrigatório quando o tipo é OTHER.
 * - flowDirection: sentido do fluxo. 1 = para a direita, -1 = para a esquerda, 0 = sem sentido.
 * - sortOrder: ordem entre vários equipamentos na mesma faixa (ficam empilhados).
 */
@Entity(tableName = "project_equipment", indices = [Index("projectName")])
data class ProjectEquipment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val projectName: String,
    val type: EquipmentType,
    val name: String = "",
    val position: Int,
    val flowDirection: Int = 0,
    val sortOrder: Int = 0
)
