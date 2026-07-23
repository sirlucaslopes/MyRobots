package my.robots.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.data.model.Robot

@Dao
interface RobotDao {
    @Query("SELECT * FROM robots")
    fun getAllRobots(): Flow<List<Robot>>

    @Query("SELECT * FROM robots WHERE id = :id")
    suspend fun getRobotById(id: Int): Robot?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRobot(robot: Robot): Long

    @Update
    suspend fun updateRobot(robot: Robot)

    @Delete
    suspend fun deleteRobot(robot: Robot)
}
