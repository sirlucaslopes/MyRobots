package my.robots.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.Backup
import my.robots.core.model.BackupSummary

/**
 * Consultas da tabela de backups.
 */
@Dao
interface BackupDao {
    /**
     * Lista os backups de um robô, do mais novo para o mais antigo,
     * SEM o texto do arquivo (só o resumo), para a lista ficar leve.
     */
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsSummaryForRobot(robotId: Int): Flow<List<BackupSummary>>

    /**
     * Procura backups de um robô pelo texto digitado, olhando o nome do
     * arquivo e o nome do backup. Devolve só o resumo (sem o conteúdo).
     */
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId AND (fileName LIKE '%' || :query || '%' OR backupName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchBackupsSummary(robotId: Int, query: String): Flow<List<BackupSummary>>

    /**
     * Lista os backups de um robô COM o texto completo. Pesado: use só quando precisar.
     */
    @Query("SELECT * FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsForRobot(robotId: Int): Flow<List<Backup>>

    /**
     * Salva um backup (se o id já existir, ele é substituído). Devolve o id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: Backup): Long

    /**
     * Apaga um backup do banco.
     */
    @Delete
    suspend fun deleteBackup(backup: Backup)

    /**
     * Busca um backup completo (com o texto) pelo id. Devolve null se não existir.
     */
    @Query("SELECT * FROM backups WHERE id = :id")
    suspend fun getBackupById(id: Int): Backup?
    
    /**
     * Apaga um backup usando só o id dele.
     */
    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteBackupById(id: Int)
}
