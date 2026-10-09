package my.robots.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.ProjectEquipment
import my.robots.core.model.ProjectLayout

/**
 * Consultas do layout da cabine de cada projeto: o tamanho da grade (project_layouts), os
 * equipamentos (project_equipment) e a posição de cada robô (robots.layoutRow/layoutCol).
 *
 * O projeto é identificado só pelo nome, igual ao `Robot.project`.
 */
@Dao
abstract class ProjectDao {

    /** Tamanho da grade do projeto, ou null se ele ainda usa o padrão. */
    @Query("SELECT * FROM project_layouts WHERE projectName = :projectName")
    abstract fun getLayout(projectName: String): Flow<ProjectLayout?>

    /** Equipamentos do projeto, por faixa e ordem dentro da faixa. */
    @Query("SELECT * FROM project_equipment WHERE projectName = :projectName ORDER BY position, sortOrder")
    abstract fun getEquipment(projectName: String): Flow<List<ProjectEquipment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertLayout(layout: ProjectLayout)

    @Insert
    protected abstract suspend fun insertEquipment(equipment: List<ProjectEquipment>)

    @Query("DELETE FROM project_equipment WHERE projectName = :projectName")
    protected abstract suspend fun deleteEquipment(projectName: String)

    @Query("DELETE FROM project_layouts WHERE projectName = :projectName")
    protected abstract suspend fun deleteLayout(projectName: String)

    @Query("UPDATE robots SET layoutRow = :row, layoutCol = :col WHERE id = :robotId")
    protected abstract suspend fun setRobotPosition(robotId: Int, row: Int?, col: Int?)

    @Query("SELECT COUNT(*) FROM robots WHERE project = :projectName")
    protected abstract suspend fun countRobots(projectName: String): Int

    @Query("SELECT COUNT(*) FROM project_layouts WHERE projectName = :projectName")
    protected abstract suspend fun countLayouts(projectName: String): Int

    @Query("UPDATE robots SET project = :newName WHERE project = :oldName")
    protected abstract suspend fun renameRobotsProject(oldName: String, newName: String)

    @Query("UPDATE project_layouts SET projectName = :newName WHERE projectName = :oldName")
    protected abstract suspend fun renameLayout(oldName: String, newName: String)

    @Query("UPDATE project_equipment SET projectName = :newName WHERE projectName = :oldName")
    protected abstract suspend fun renameEquipment(oldName: String, newName: String)

    /** Quem tinha o projeto antigo como mestre passa a apontar para o nome novo. */
    @Query("UPDATE project_layouts SET masterProject = :newName WHERE masterProject = :oldName")
    protected abstract suspend fun renameMasterReferences(oldName: String, newName: String)

    /**
     * Grava a edição do layout de uma vez: tamanho da grade, posição de cada robô
     * (robotId -> linha/coluna, null = fora do layout) e a lista completa de equipamentos,
     * que substitui a anterior.
     */
    @Transaction
    open suspend fun saveLayout(
        layout: ProjectLayout,
        robotPositions: Map<Int, Pair<Int?, Int?>>,
        equipment: List<ProjectEquipment>
    ) {
        // só a grade muda aqui: o projeto mestre e o offset continuam os gravados
        val current = layoutNow(layout.projectName)
        upsertLayout(current?.copy(rowCount = layout.rowCount, colCount = layout.colCount) ?: layout)
        robotPositions.forEach { (id, pos) -> setRobotPosition(id, pos.first, pos.second) }
        deleteEquipment(layout.projectName)
        insertEquipment(equipment.map { it.copy(id = 0, projectName = layout.projectName) })
    }

    /**
     * Renomeia o projeto nos robôs, no layout e nos equipamentos. Se já existir um projeto com
     * o nome novo, os robôs passam para ele, que mantém o próprio layout; os equipamentos do
     * antigo são somados aos dele.
     */
    @Transaction
    open suspend fun renameProject(oldName: String, newName: String) {
        if (oldName == newName) return
        renameRobotsProject(oldName, newName)
        if (countLayouts(newName) > 0) deleteLayout(oldName) else renameLayout(oldName, newName)
        renameEquipment(oldName, newName)
        // a ligação mestre/escravo é pelo nome: sem isto, renomear o mestre desfazia a ligação
        renameMasterReferences(oldName, newName)
    }

    @Query("UPDATE robots SET masterRobotId = :masterId WHERE id = :robotId")
    protected abstract suspend fun setMaster(robotId: Int, masterId: Int?)

    @Query("SELECT * FROM project_layouts WHERE projectName = :projectName")
    protected abstract suspend fun layoutNow(projectName: String): ProjectLayout?

    /**
     * Configuração mestre/escravo do projeto escravo: o projeto mestre (null = sem mestre), a
     * variável de offset e o mestre de cada robô. Cria a linha do layout se não existir.
     */
    @Transaction
    open suspend fun saveMasterConfig(
        slaveProject: String,
        masterProject: String?,
        baseOffset: String,
        pairs: Map<Int, Int?>
    ) {
        val layout = layoutNow(slaveProject) ?: ProjectLayout(slaveProject)
        upsertLayout(layout.copy(masterProject = masterProject, baseOffset = baseOffset))
        pairs.forEach { (robotId, masterId) -> setMaster(robotId, if (masterProject == null) null else masterId) }
    }

    /** Apaga o layout e os equipamentos de um projeto que ficou sem robôs. */
    @Transaction
    open suspend fun deleteLayoutIfEmpty(projectName: String) {
        if (countRobots(projectName) == 0) {
            deleteLayout(projectName)
            deleteEquipment(projectName)
        }
    }
}
