package my.robots.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.Robot

/**
 * Consultas da tabela de robôs. O Room escreve o código de cada função sozinho.
 */
@Dao
interface RobotDao {
    /**
     * Lista todos os robôs. Por ser um Flow, a tela se atualiza sozinha quando algo muda.
     */
    @Query("SELECT * FROM robots")
    fun getAllRobots(): Flow<List<Robot>>

    /**
     * Busca um robô pelo número (id). Devolve null se não existir.
     */
    @Query("SELECT * FROM robots WHERE id = :id")
    suspend fun getRobotById(id: Int): Robot?

    /**
     * Salva um robô novo (ou troca o que já existe com o mesmo id). Devolve o id criado.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRobot(robot: Robot): Long

    /**
     * Atualiza os dados de um robô que já existe.
     */
    @Update
    suspend fun updateRobot(robot: Robot)

    /**
     * Apaga um robô do banco.
     */
    @Delete
    suspend fun deleteRobot(robot: Robot)
}
