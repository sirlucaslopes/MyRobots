package my.robots.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import my.robots.core.model.Backup
import my.robots.core.model.Client
import my.robots.core.model.ProductionLine
import my.robots.core.model.WorkType
import my.robots.core.model.ProjectEquipment
import my.robots.core.model.ProjectLayout
import my.robots.core.model.QuickCommand
import my.robots.core.model.Robot

/**
 * O banco de dados do app (Room / SQLite), guardado no próprio celular.
 *
 * Tabelas: robôs, comandos rápidos, backups e, desde a versão 5, o layout da cabine de cada
 * projeto (project_layouts) e os equipamentos dela (project_equipment). A versão 6 guarda o
 * número de série de cada robô; a 7, os pares mestre/escravo (robô e projeto); a 8, Cliente →
 * Linha → Estação (clients, lines, work_types e a linha de cada estação em project_layouts).
 * O schema de cada versão fica gravado em core/database/schemas/ (exportSchema = true).
 * Ao subir a versão, escreva a migração em DatabaseMigrations.kt: o banco nunca é apagado.
 */
@Database(
    entities = [
        Robot::class, QuickCommand::class, Backup::class, ProjectLayout::class, ProjectEquipment::class,
        Client::class, ProductionLine::class, WorkType::class
    ],
    version = 8,
    exportSchema = true
)
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
    /**
     * Acesso ao layout da cabine e aos equipamentos dos projetos.
     */
    abstract fun projectDao(): ProjectDao
    /**
     * Clientes, linhas, tipos de trabalho e onde fica cada estação.
     */
    abstract fun hierarchyDao(): HierarchyDao
}
