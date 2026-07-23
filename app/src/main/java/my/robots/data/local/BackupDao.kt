package my.robots.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.data.model.Backup
import my.robots.data.model.BackupSummary

@Dao
interface BackupDao {
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsSummaryForRobot(robotId: Int): Flow<List<BackupSummary>>

    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId AND (fileName LIKE '%' || :query || '%' OR backupName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchBackupsSummary(robotId: Int, query: String): Flow<List<BackupSummary>>

    @Query("SELECT * FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsForRobot(robotId: Int): Flow<List<Backup>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: Backup)

    @Delete
    suspend fun deleteBackup(backup: Backup)

    @Query("SELECT * FROM backups WHERE id = :id")
    suspend fun getBackupById(id: Int): Backup?
    
    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteBackupById(id: Int)
}
