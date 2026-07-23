package my.robots.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.data.model.QuickCommand

@Dao
interface QuickCommandDao {
    @Query("SELECT * FROM quick_commands WHERE robotId = :robotId")
    fun getQuickCommandsForRobot(robotId: Int): Flow<List<QuickCommand>>

    @Query("SELECT * FROM quick_commands")
    fun getAllQuickCommands(): Flow<List<QuickCommand>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuickCommand(command: QuickCommand)

    @Update
    suspend fun updateQuickCommand(command: QuickCommand)

    @Delete
    suspend fun deleteQuickCommand(command: QuickCommand)
}
