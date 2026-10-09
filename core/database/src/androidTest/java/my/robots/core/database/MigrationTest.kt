package my.robots.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.robots.core.model.Manufacturer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Confere que o banco de uma versão antiga abre na versão atual SEM perder dados.
 *
 * Usa os schemas gravados em core/database/schemas/. Roda no celular ou emulador:
 * `.\gradlew.bat :core:database:connectedDebugAndroidTest`.
 * A cada migração nova (ALL_MIGRATIONS), acrescente um teste aqui.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /**
     * Cria um banco na versão 4 (a primeira com schema exportado), grava um robô, um backup
     * e um comando rápido, e abre com o AppDatabase atual + ALL_MIGRATIONS: os três
     * registros precisam continuar lá, iguais.
     */
    @Test
    fun bancoV4AbreNaVersaoAtualComOsDados() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword) " +
                    "VALUES (1, 'R10', '192.168.0.10', 23, 'CAT Primer', 'KAWASAKI', 1, 'as', 'senha')"
            )
            execSQL(
                "INSERT INTO backups (id, robotId, backupName, fileName, content, programsCount, variablesCount, framesCount, memoryUsage, timestamp) " +
                    "VALUES (1, 1, 'Backup R10', 'r10_20260925_1000.as', '.PROGRAM main()\n.END', 1, 0, 0, 20, 1000)"
            )
            execSQL(
                "INSERT INTO quick_commands (id, robotId, manufacturer, label, command) " +
                    "VALUES (1, 1, 'KAWASAKI', 'Save Full', 'SAVE/FULL [ROBOT][DATA]')"
            )
            close()
        }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*ALL_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val robot = db.robotDao().getRobotById(1)!!
            assertEquals("R10", robot.name)
            assertEquals("CAT Primer", robot.project)
            assertEquals(Manufacturer.KAWASAKI, robot.manufacturer)
            assertEquals(true, robot.autoLogin)

            val backup = db.backupDao().getBackupById(1)!!
            assertEquals(1, backup.robotId)
            assertEquals(".PROGRAM main()\n.END", backup.content)

            val commands = db.quickCommandDao().getQuickCommandsForRobot(1).first()
            assertEquals(listOf("SAVE/FULL [ROBOT][DATA]"), commands.map { it.command })

            // 4 -> 5: robô antigo fica fora do layout
            assertEquals(null, robot.layoutRow)
            assertEquals(null, robot.layoutCol)
            // 7 -> 8: o projeto vira estação de "Meu cliente" › "Linha 1", com o layout padrão
            val station = db.projectDao().getLayout("CAT Primer").first()!!
            assertEquals(1L, station.lineId)
            assertEquals(2, station.rowCount)
            assertEquals(listOf("Meu cliente"), db.hierarchyDao().clients().first().map { it.name })
            // 5 -> 6: série ainda desconhecida
            assertEquals(null, robot.serialNumber)
        }
    }

    /**
     * 4 -> 5: o schema gerado pela MIGRATION_4_5 tem que ser igual ao 5.json que o Room
     * exportou (colunas, tipos, índice). runMigrationsAndValidate falha se não for.
     */
    @Test
    fun migracao4Para5ValidaContraOSchema() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword) " +
                    "VALUES (1, 'R10', '192.168.0.10', 23, 'CAT Primer', 'KAWASAKI', 0, 'as', '')"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).apply {
            query("SELECT layoutRow, layoutCol FROM robots WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
                assertEquals(true, c.isNull(1))
            }
            close()
        }
    }

    /**
     * 5 -> 6: a coluna serialNumber entra nula, e o schema bate com o 6.json.
     */
    @Test
    fun migracao5Para6ValidaContraOSchema() {
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword, layoutRow, layoutCol) " +
                    "VALUES (1, 'R10', '192.168.0.10', 23, 'CAT Primer', 'KAWASAKI', 0, 'as', '', 0, 1)"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).apply {
            query("SELECT serialNumber, layoutCol FROM robots WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
                assertEquals(1, c.getInt(1))
            }
            close()
        }
    }

    /**
     * 6 -> 7: robô sem mestre, projeto sem mestre e offset padrão; schema igual ao 7.json.
     */
    @Test
    fun migracao6Para7ValidaContraOSchema() {
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword, layoutRow, layoutCol, serialNumber) " +
                    "VALUES (1, 'R14', '172.20.32.41', 23, 'Top Coat CAT', 'KAWASAKI', 0, 'as', '', 1, 0, '3771')"
            )
            execSQL("INSERT INTO project_layouts (projectName, rowCount, colCount) VALUES ('Top Coat CAT', 2, 2)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).apply {
            query("SELECT masterRobotId, serialNumber FROM robots WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
                assertEquals("3771", c.getString(1))
            }
            query("SELECT masterProject, baseOffset FROM project_layouts").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
                assertEquals("top_offset", c.getString(1))
            }
            close()
        }
    }

    /**
     * 7 -> 8: Cliente → Linha → Estação. Dois projetos com layout (um escravo do outro) e um
     * que só existe nos robôs: os três viram estações de "Meu cliente" › "Linha 1", na ordem
     * alfabética, e os pares mestre/escravo continuam. Schema igual ao 8.json.
     */
    @Test
    fun migracao7Para8ValidaContraOSchema() {
        helper.createDatabase(dbName, 7).apply {
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword, layoutRow, layoutCol, serialNumber, masterRobotId) " +
                    "VALUES (1, 'R10', '172.20.32.45', 23, 'Primer CAT', 'KAWASAKI', 0, 'as', '', 1, 0, '3772', NULL)"
            )
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword, layoutRow, layoutCol, serialNumber, masterRobotId) " +
                    "VALUES (2, 'R14', '172.20.32.41', 23, 'Top Coat CAT', 'KAWASAKI', 0, 'as', '', 1, 0, '3771', 1)"
            )
            execSQL(
                "INSERT INTO robots (id, name, ip, port, project, manufacturer, autoLogin, loginUser, loginPassword, layoutRow, layoutCol, serialNumber, masterRobotId) " +
                    "VALUES (3, 'C01', '192.168.1.15', 2301, 'k-roset', 'KAWASAKI', 0, 'as', '', NULL, NULL, NULL, NULL)"
            )
            execSQL("INSERT INTO project_layouts (projectName, rowCount, colCount, masterProject, baseOffset) VALUES ('Primer CAT', 3, 2, NULL, 'top_offset')")
            execSQL("INSERT INTO project_layouts (projectName, rowCount, colCount, masterProject, baseOffset) VALUES ('Top Coat CAT', 2, 2, 'Primer CAT', 'top_offset')")
            execSQL("INSERT INTO project_equipment (id, projectName, type, name, position, flowDirection, sortOrder) VALUES (1, 'Primer CAT', 'CONVEYOR', '', 1, 1, 0)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).apply {
            query("SELECT id, name, hidden FROM clients").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("Meu cliente", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            query("SELECT id, clientId, name FROM lines").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(1, c.getInt(1))
                assertEquals("Linha 1", c.getString(2))
            }
            // todas as estações na linha 1, em ordem alfabética (sem diferença de maiúsculas)
            query("SELECT projectName, lineId, sortOrder, rowCount, masterProject, hidden FROM project_layouts ORDER BY sortOrder").use { c ->
                val rows = mutableListOf<String>()
                while (c.moveToNext()) {
                    assertEquals(1, c.getInt(1))
                    assertEquals(0, c.getInt(5))
                    rows += "${c.getString(0)}:${c.getInt(2)}:${c.getInt(3)}:${c.getString(4)}"
                }
                assertEquals(listOf("k-roset:0:2:null", "Primer CAT:1:3:null", "Top Coat CAT:2:2:Primer CAT"), rows)
            }
            // pares de robôs, posições e equipamentos não mudam
            query("SELECT masterRobotId, layoutRow FROM robots WHERE id = 2").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
                assertEquals(1, c.getInt(1))
            }
            query("SELECT COUNT(*) FROM project_equipment WHERE projectName = 'Primer CAT'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            query("SELECT name FROM work_types ORDER BY sortOrder").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertEquals(listOf("Pintura", "Solda", "Manipulação", "Selagem"), names)
            }
            close()
        }
    }

    /** 7 -> 8 com o banco vazio: nenhum cliente é criado (o app cria com o primeiro robô). */
    @Test
    fun migracao7Para8ComBancoVazio() {
        helper.createDatabase(dbName, 7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).apply {
            query("SELECT COUNT(*) FROM clients").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            query("SELECT COUNT(*) FROM work_types").use { c -> c.moveToFirst(); assertEquals(4, c.getInt(0)) }
            close()
        }
    }
}
