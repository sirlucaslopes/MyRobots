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
 * O schema de cada versão fica gravado em core/database/schemas/ (exportSchema = true).
 * Ao subir a versão, escreva a migração em DatabaseMigrations.kt: o banco nunca é apagado.
 */
@Database(entities = [Robot::class, QuickCommand::class, Backup::class], version = 4, exportSchema = true)
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
