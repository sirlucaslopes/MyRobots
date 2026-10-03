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

            // 4 -> 5: robô antigo fica fora do layout, e o projeto ainda não tem layout
            assertEquals(null, robot.layoutRow)
            assertEquals(null, robot.layoutCol)
            assertEquals(null, db.projectDao().getLayout("CAT Primer").first())
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
}
