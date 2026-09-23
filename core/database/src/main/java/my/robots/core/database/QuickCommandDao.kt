package my.robots.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.QuickCommand

/**
 * Consultas da tabela de comandos rápidos.
 */
@Dao
interface QuickCommandDao {
    /**
     * Lista os comandos rápidos de um robô específico.
     */
    @Query("SELECT * FROM quick_commands WHERE robotId = :robotId")
    fun getQuickCommandsForRobot(robotId: Int): Flow<List<QuickCommand>>

    /**
     * Lista todos os comandos rápidos, de todos os robôs.
     */
    @Query("SELECT * FROM quick_commands")
    fun getAllQuickCommands(): Flow<List<QuickCommand>>

    /**
     * Salva um comando rápido (se o id já existir, ele é substituído).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuickCommand(command: QuickCommand)

    /**
     * Atualiza um comando rápido que já existe.
     */
    @Update
    suspend fun updateQuickCommand(command: QuickCommand)

    /**
     * Apaga um comando rápido.
     */
    @Delete
    suspend fun deleteQuickCommand(command: QuickCommand)
}
