package my.robots

import android.app.Application
import android.database.CursorWindow
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.robots.core.database.ALL_MIGRATIONS
import my.robots.core.database.AppDatabase
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.data.RobotRepository
import my.robots.core.data.security.KeystoreSecretCipher
import my.robots.core.data.storage.RobotFilesStorage
import my.robots.core.data.ControllerChecks
import my.robots.core.data.ManufacturerSettings
import my.robots.core.data.MasterSlaveOptions
import my.robots.core.data.RobotCommands
import my.robots.core.data.ProjectOperations

/**
 * Classe que o Android cria UMA vez quando o app abre (antes de qualquer tela).
 *
 * Aqui montamos as peças que vivem enquanto o app estiver aberto:
 * o banco de dados, o repositório e o gerenciador do terminal.
 * As telas pegam essas peças pela MainActivity.
 */
class MyRobotsApp : Application() {

    /**
     * Repositório único de dados (banco + rede + arquivos).
     */
    lateinit var robotRepository: RobotRepository
    /**
     * Gerenciador único das conexões de terminal com os robôs.
     */
    lateinit var terminalManager: KawasakiTerminalManager
    /**
     * Checagens depois do login em qualquer robô: série (ID), relógio e memória (FREE).
     */
    lateinit var controllerChecks: ControllerChecks
    /**
     * Ações em grupo da tela de Projeto (backup de todos, comando para todos, mestre -> escravo).
     */
    lateinit var projectOperations: ProjectOperations
    /** Termos da pesquisa rápida e comandos padrão de cada fabricante (tela "Fabricantes"). */
    lateinit var manufacturerSettings: ManufacturerSettings
    /** Opções da transferência mestre -> escravo de cada projeto escravo. */
    lateinit var masterSlaveOptions: MasterSlaveOptions
    /**
     * Área para tarefas de longa duração ligadas ao app (não a uma tela).
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Pasta dos arquivos dos robôs (Documentos/MyRobots ou a pasta escolhida pelo usuário).
     */
    lateinit var filesStorage: RobotFilesStorage

    /**
     * Chamado quando o app inicia. Faz, na ordem:
     * 1. Abre o banco de dados.
     * 2. Cria a pasta dos arquivos, o repositório e o gerenciador de terminal.
     * 3. Na primeira abertura da v1.2, regrava os backups do banco na pasta nova.
     */
    override fun onCreate() {
        super.onCreate()

        // Backups do tipo Full (SAVE/FULL) juntam programas, variáveis, Data Bank e os
        // logs do controlador num texto só, que pode passar dos 2 MB (limite padrão do
        // CursorWindow do SQLite para uma linha). Sem isso, abrir um backup Full falha
        // ao ler a coluna "content" do banco. Precisa rodar antes de qualquer consulta.
        increaseCursorWindowSize()

        val database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "robot_database"
        )
        // Ao mudar a versão do banco, os dados são levados pelas migrações escritas à mão
        // (DatabaseMigrations.kt). Nunca apaga o banco: sem migração, o app falha ao abrir.
        .addMigrations(*ALL_MIGRATIONS)
        .build()

        robotRepository = RobotRepository(
            database.robotDao(),
            database.quickCommandDao(),
            database.backupDao(),
            database.projectDao(),
            database.hierarchyDao(),
            RobotFilesStorage(this).also { filesStorage = it },
            KeystoreSecretCipher()
        )
        
        terminalManager = KawasakiTerminalManager(filesStorage)
        controllerChecks = ControllerChecks(this, terminalManager, RobotCommands(terminalManager), robotRepository, applicationScope)
        controllerChecks.start()
        projectOperations = ProjectOperations(robotRepository, terminalManager, controllerChecks)
        manufacturerSettings = ManufacturerSettings(this)
        masterSlaveOptions = MasterSlaveOptions(this)
        robotRepository.defaultCommandsFor = { m -> manufacturerSettings.defaultCommands(m).value }

        migrateFilesToNewFolderOnce()

        // senhas de login gravadas em texto puro até a v1.1 passam a ser guardadas cifradas;
        // e todo projeto vira estação de uma linha (Cliente → Linha → Estação, banco v8)
        applicationScope.launch(Dispatchers.IO) {
            try {
                robotRepository.ensureStations()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                robotRepository.encryptLegacyPasswords()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Eleva o limite do CursorWindow (padrão ~2 MB) para 100 MB, via reflection no campo
     * estático interno do Android. Sem isso, ler uma linha do banco com um texto maior
     * que o limite (caso dos backups Full) derruba a consulta. Se a reflection falhar
     * (versão do Android sem esse campo), o app segue normalmente com o limite padrão.
     */
    private fun increaseCursorWindowSize() {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Migração de armazenamento da v1.2: até a v1.1 os arquivos ficavam em /MyRobots, com
     * "acesso a todos os arquivos". Sem essa permissão a pasta antiga deixa de ser acessível,
     * então, uma vez só, cada backup do banco (que guarda o texto completo) é regravado na
     * pasta nova. Se falhar (ex.: sem espaço), tenta de novo na próxima abertura.
     * Arquivos que estavam só na pasta antiga e nunca entraram no banco continuam no disco:
     * o usuário pode escolher a pasta /MyRobots antiga em "Pasta dos arquivos" para importá-los.
     */
    private fun migrateFilesToNewFolderOnce() {
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FILES_MIGRATED_V12, false)) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                robotRepository.restoreMissingFiles()
                prefs.edit().putBoolean(KEY_FILES_MIGRATED_V12, true).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private companion object {
        const val KEY_FILES_MIGRATED_V12 = "files_migrated_v12"
    }
}
