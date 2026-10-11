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

/**
 * 5 -> 6 (v1.2): número de série do controlador em cada robô. Começa nulo e é preenchido pelo
 * backup SAVE/FULL ou pelo comando ID ao conectar.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE robots ADD COLUMN serialNumber TEXT DEFAULT NULL")
    }
}

/**
 * 6 -> 7 (v1.2): projetos mestre/escravo. Cada robô pode ter um robô mestre, e cada projeto um
 * projeto mestre e a variável de offset somada à base dos programas transferidos.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE robots ADD COLUMN masterRobotId INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN masterProject TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN baseOffset TEXT NOT NULL DEFAULT 'top_offset'")
    }
}

/**
 * 7 -> 8 (v1.3): Cliente → Linha → Estação.
 * - tabelas novas: clients, lines e work_types (Pintura, Solda, Manipulação, Selagem);
 * - a estação é a linha de project_layouts, que ganha lineId, workType, sortOrder e hidden;
 * - cria "Meu cliente" › "Linha 1" e põe nela TODOS os projetos: os que já tinham layout e os
 *   que só existiam como texto em robots.project (ganham o layout padrão 2×2);
 * - a ordem do processo começa pela ordem alfabética (sem diferença de maiúsculas);
 * - os pares mestre/escravo (masterProject, masterRobotId) não mudam: viram as ligações de
 *   reaproveitamento entre estações.
 * Sem nenhum projeto (banco vazio), o cliente e a linha não são criados: o app cria quando
 * aparecer o primeiro robô (HierarchyDao.ensureStations).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `clients` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `hidden` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`clientId` INTEGER NOT NULL, `name` TEXT NOT NULL, `hidden` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lines_clientId` ON `lines` (`clientId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `work_types` (`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`))"
        )
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN lineId INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN workType TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE project_layouts ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            "INSERT INTO work_types (name, sortOrder) VALUES " +
                "('Pintura', 0), ('Solda', 1), ('Manipulação', 2), ('Selagem', 3)"
        )
        // projetos que só existiam nos robôs ganham o layout padrão
        db.execSQL(
            "INSERT OR IGNORE INTO project_layouts (projectName, rowCount, colCount, masterProject, baseOffset) " +
                "SELECT DISTINCT project, 2, 2, NULL, 'top_offset' FROM robots"
        )
        val hasStations = db.query("SELECT COUNT(*) FROM project_layouts").use { it.moveToFirst(); it.getInt(0) > 0 }
        if (hasStations) {
            db.execSQL("INSERT INTO clients (id, name, hidden, lastUsedAt) VALUES (1, 'Meu cliente', 0, 0)")
            db.execSQL("INSERT INTO lines (id, clientId, name, hidden, sortOrder, lastUsedAt) VALUES (1, 1, 'Linha 1', 0, 0, 0)")
            db.execSQL("UPDATE project_layouts SET lineId = 1")
            // ordem alfabética: quantas estações vêm antes desta (o SQLite do Android 9 não tem ROW_NUMBER)
            db.execSQL(
                "UPDATE project_layouts SET sortOrder = (SELECT COUNT(*) FROM project_layouts p2 " +
                    "WHERE p2.projectName COLLATE NOCASE < project_layouts.projectName COLLATE NOCASE)"
            )
        }
    }
}

/**
 * 8 -> 9 (cabine 3D): cada robô pode ter um modelo 3D (o id de um robô montado no Montador,
 * `files/robos3d/<id>`) e o BASE do controlador dele ("X Y Z O A T"). Os dois começam nulos:
 * sem modelo, a cabine 3D desenha um robô genérico; sem BASE, vale o BASE 0.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE robots ADD COLUMN model3dId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE robots ADD COLUMN robotBase TEXT DEFAULT NULL")
    }
}

/** Todas as migrações, na ordem. Fica no fim porque usa as declaradas acima. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
