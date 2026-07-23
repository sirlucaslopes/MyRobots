package my.robots.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import my.robots.data.model.Backup
import my.robots.data.model.QuickCommand
import my.robots.data.model.Robot

@Database(entities = [Robot::class, QuickCommand::class, Backup::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun robotDao(): RobotDao
    abstract fun quickCommandDao(): QuickCommandDao
    abstract fun backupDao(): BackupDao
}
