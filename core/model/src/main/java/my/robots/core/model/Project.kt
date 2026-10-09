package my.robots.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Uma ESTAÇÃO (o "projeto" de até a v1.2): a cabine de um projeto, linha da tabela
 * "project_layouts". Desde o banco v8 ela pertence a uma linha de produção ([lineId]), na ordem
 * do processo ([sortOrder]), com um tipo de trabalho e pode ficar oculta (nada é apagado).
 *
 * - projectName: o nome do projeto, igual ao `Robot.project`.
 * - rowCount / colCount: linhas e colunas da grade ("rows" não é usado porque ROWS é
 *   palavra-chave do SQLite). Um projeto sem linha nesta tabela usa o padrão 2×2, e a linha
 *   só é criada na primeira edição.
 * - masterProject: projeto mestre deste (este é o escravo; ex.: Top Coat é escravo do Primer).
 *   null = projeto sem mestre.
 * - baseOffset: variável somada à base dos programas transferidos do mestre: "BASE fr_[100]"
 *   no mestre vira "BASE fr_[100]+top_offset" aqui. Só vale com masterProject. A ligação
 *   mestre/escravo é a "ligação de reaproveitamento" entre estações (pode ser de outra linha).
 * - lineId: a linha de produção ([ProductionLine]). null = ainda sem linha: o app põe na linha
 *   usada por último (RobotRepository.ensureStations).
 * - workType: tipo de trabalho ([WorkType.name]); null = sem tipo.
 * - sortOrder: posição na ordem do processo da linha (0 = primeira).
 * - hidden: oculta na tela inicial (os dados continuam).
 */
@Entity(tableName = "project_layouts")
data class ProjectLayout(
    @PrimaryKey val projectName: String,
    val rowCount: Int = DEFAULT_ROWS,
    val colCount: Int = DEFAULT_COLS,
    val masterProject: String? = null,
    val baseOffset: String = DEFAULT_BASE_OFFSET,
    val lineId: Long? = null,
    val workType: String? = null,
    val sortOrder: Int = 0,
    val hidden: Boolean = false
) {
    companion object {
        const val DEFAULT_ROWS = 2
        const val DEFAULT_COLS = 2
        const val DEFAULT_BASE_OFFSET = "top_offset"
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

/**
 * Um cliente (dono das linhas de produção), linha da tabela "clients".
 * - hidden: oculto na tela inicial (nada é apagado);
 * - lastUsedAt: quando foi aberto por último (a tela inicial mostra o último usado primeiro).
 */
@Entity(tableName = "clients")
data class Client(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hidden: Boolean = false,
    val lastUsedAt: Long = 0
)

/**
 * Uma linha de produção (uma cabine com várias estações em sequência), tabela "lines".
 * O processo vive aqui: a ordem das estações ([ProjectLayout.sortOrder]) vale dentro da linha.
 */
@Entity(tableName = "lines", indices = [Index("clientId")])
data class ProductionLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val name: String,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    val lastUsedAt: Long = 0
)

/** Tipo de trabalho de uma estação (lista aberta), tabela "work_types". */
@Entity(tableName = "work_types")
data class WorkType(
    @PrimaryKey val name: String,
    val sortOrder: Int = 0
) {
    companion object {
        /** Os tipos com que o app começa (banco v8). */
        val DEFAULTS = listOf("Pintura", "Solda", "Manipulação", "Selagem")
        const val DEFAULT_CLIENT = "Meu cliente"
        const val DEFAULT_LINE = "Linha 1"
    }
}
