package my.robots.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.Client
import my.robots.core.model.ProductionLine
import my.robots.core.model.ProjectLayout
import my.robots.core.model.WorkType

/**
 * Cliente → Linha → Estação (banco v8). A estação é a linha de "project_layouts" (o projeto de
 * até a v1.2); aqui ficam os clientes, as linhas, os tipos de trabalho e onde cada estação está.
 */
@Dao
abstract class HierarchyDao {

    @Query("SELECT * FROM clients ORDER BY id")
    abstract fun clients(): Flow<List<Client>>

    @Query("SELECT * FROM lines ORDER BY clientId, sortOrder, id")
    abstract fun lines(): Flow<List<ProductionLine>>

    /** Todas as estações (o layout de cada projeto). */
    @Query("SELECT * FROM project_layouts")
    abstract fun stations(): Flow<List<ProjectLayout>>

    @Query("SELECT * FROM work_types ORDER BY sortOrder, name")
    abstract fun workTypes(): Flow<List<WorkType>>

    @Insert
    abstract suspend fun insertClient(client: Client): Long

    @Insert
    abstract suspend fun insertLine(line: ProductionLine): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWorkType(type: WorkType)

    @Query("UPDATE clients SET name = :name WHERE id = :id")
    abstract suspend fun renameClient(id: Long, name: String)

    @Query("UPDATE lines SET name = :name WHERE id = :id")
    abstract suspend fun renameLine(id: Long, name: String)

    @Query("UPDATE clients SET hidden = :hidden WHERE id = :id")
    abstract suspend fun setClientHidden(id: Long, hidden: Boolean)

    @Query("UPDATE lines SET hidden = :hidden WHERE id = :id")
    abstract suspend fun setLineHidden(id: Long, hidden: Boolean)

    @Query("UPDATE project_layouts SET hidden = :hidden WHERE projectName = :name")
    abstract suspend fun setStationHidden(name: String, hidden: Boolean)

    @Query("UPDATE project_layouts SET workType = :workType WHERE projectName = :name")
    abstract suspend fun setStationWorkType(name: String, workType: String?)

    @Query("UPDATE clients SET lastUsedAt = :at WHERE id = :id")
    abstract suspend fun touchClient(id: Long, at: Long)

    @Query("UPDATE lines SET lastUsedAt = :at WHERE id = :id")
    abstract suspend fun touchLine(id: Long, at: Long)

    @Query("UPDATE project_layouts SET sortOrder = :order WHERE projectName = :name")
    protected abstract suspend fun setStationOrder(name: String, order: Int)

    @Query("UPDATE project_layouts SET lineId = :lineId, sortOrder = :order WHERE projectName = :name")
    protected abstract suspend fun setStationLine(name: String, lineId: Long, order: Int)

    @Query("SELECT * FROM project_layouts WHERE lineId = :lineId ORDER BY sortOrder, projectName COLLATE NOCASE")
    protected abstract suspend fun stationsOfLine(lineId: Long): List<ProjectLayout>

    @Query("SELECT * FROM project_layouts")
    protected abstract suspend fun stationsNow(): List<ProjectLayout>

    @Query("SELECT * FROM lines")
    protected abstract suspend fun linesNow(): List<ProductionLine>

    @Query("SELECT id FROM clients ORDER BY id LIMIT 1")
    protected abstract suspend fun firstClientId(): Long?

    @Query("SELECT COUNT(*) FROM work_types")
    protected abstract suspend fun countWorkTypes(): Int

    @Query("SELECT DISTINCT project FROM robots")
    protected abstract suspend fun robotProjects(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStation(layout: ProjectLayout)

    /**
     * Ordem do processo: põe as estações da linha na ordem de [names] (0, 1, 2…). Usado por
     * Subir/Descer e ao mover uma estação.
     */
    @Transaction
    open suspend fun reorderLine(names: List<String>) {
        names.forEachIndexed { i, n -> setStationOrder(n, i) }
    }

    /** Move a estação para o fim de outra linha (a ordem da linha antiga fica sem buraco). */
    @Transaction
    open suspend fun moveStation(name: String, toLineId: Long) {
        val from = stationsNow().firstOrNull { it.projectName == name } ?: return
        setStationLine(name, toLineId, stationsOfLine(toLineId).count { it.projectName != name })
        from.lineId?.takeIf { it != toLineId }?.let { old -> reorderLine(stationsOfLine(old).map { it.projectName }) }
    }

    /**
     * Garante que todo projeto tem estação e toda estação tem linha:
     * - tipos de trabalho padrão, se a tabela está vazia (instalação nova);
     * - "Meu cliente" › "Linha 1", se ainda não existe nenhum cliente e há algo para pôr nele;
     * - projeto que só existe em `robots.project` ganha a estação, no fim da linha padrão;
     * - estação sem linha (ou com uma linha que não existe mais) vai para a linha padrão.
     * A linha padrão é a usada por último (visível, se houver), ou a primeira.
     */
    @Transaction
    open suspend fun ensureStations() {
        if (countWorkTypes() == 0) WorkType.DEFAULTS.forEachIndexed { i, n -> insertWorkType(WorkType(n, i)) }
        val stations = stationsNow()
        val known = stations.map { it.projectName }.toSet()
        val missing = robotProjects().filter { it !in known }
        var lines = linesNow()
        val lineIds = lines.map { it.id }.toSet()
        val orphans = stations.filter { it.lineId == null || it.lineId !in lineIds }
        if (missing.isEmpty() && orphans.isEmpty()) return
        if (lines.isEmpty()) {
            val clientId = firstClientId() ?: insertClient(Client(name = WorkType.DEFAULT_CLIENT))
            insertLine(ProductionLine(clientId = clientId, name = WorkType.DEFAULT_LINE))
            lines = linesNow()
        }
        val target = lines.sortedWith(compareBy<ProductionLine> { it.hidden }.thenByDescending { it.lastUsedAt }.thenBy { it.id }).first()
        var next = stationsOfLine(target.id).size
        orphans.sortedBy { it.projectName.lowercase() }.forEach { setStationLine(it.projectName, target.id, next++) }
        missing.sortedBy { it.lowercase() }.forEach { insertStation(ProjectLayout(it, lineId = target.id, sortOrder = next++)) }
    }
}
