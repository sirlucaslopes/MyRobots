package my.robots.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrações do banco, escritas à mão, uma para cada subida de versão.
 *
 * Como usar ao mudar uma entidade:
 * 1. Suba a versão em AppDatabase.
 * 2. Compile: o Room grava o schema novo em core/database/schemas/<versão>.json.
 * 3. Escreva aqui um `Migration(antiga, nova)` com o SQL que leva do schema antigo ao
 *    novo (compare os dois .json) e inclua na lista ALL_MIGRATIONS.
 * 4. Acrescente o caso no MigrationTest (androidTest deste módulo).
 *
 * Sem a migração, o app falha ao abrir o banco em vez de apagar os dados do usuário.
 */
/**
 * 4 -> 5 (v1.2, tela de Projeto): posição de cada robô na cabine e as tabelas do layout.
 * Os robôs antigos ficam com layoutRow/layoutCol nulos, ou seja, "fora do layout".
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE robots ADD COLUMN layoutRow INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE robots ADD COLUMN layoutCol INTEGER DEFAULT NULL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `project_layouts` (`projectName` TEXT NOT NULL, " +
                "`rowCount` INTEGER NOT NULL, `colCount` INTEGER NOT NULL, PRIMARY KEY(`projectName`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `project_equipment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectName` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`flowDirection` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_equipment_projectName` ON `project_equipment` (`projectName`)")
    }
}

/** Todas as migrações, na ordem. Fica no fim porque usa as declaradas acima. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5)
