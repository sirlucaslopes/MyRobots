package my.robots.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import my.robots.core.model.Backup
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot

/**
 * O banco de dados do app (Room / SQLite), guardado no próprio celular.
 *
 * Tem 3 tabelas: robôs, comandos rápidos e backups.
 * Se a versão do banco mudar, ele é apagado e recriado (veja MyRobotsApp).
 */
@Database(entities = [Robot::class, QuickCommand::class, Backup::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Acesso à tabela de robôs.
     */
    abstract fun robotDao(): RobotDao
    /**
     * Acesso à tabela de comandos rápidos.
     */
    abstract fun quickCommandDao(): QuickCommandDao
    /**
     * Acesso à tabela de backups.
     */
    abstract fun backupDao(): BackupDao
}
