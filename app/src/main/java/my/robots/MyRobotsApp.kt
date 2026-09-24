package my.robots

import android.app.Application
import android.database.CursorWindow
import android.os.Environment
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import my.robots.core.database.AppDatabase
import my.robots.core.network.KawasakiTerminalManager
import my.robots.core.network.RobotApiService
import my.robots.core.data.RobotRepository
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

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
     * Área para tarefas de longa duração ligadas ao app (não a uma tela).
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Chamado quando o app inicia. Faz, na ordem:
     * 1. Cria a pasta /MyRobots.
     * 2. Abre o banco de dados.
     * 3. Prepara a API HTTP (endereço de teste).
     * 4. Cria o repositório e o gerenciador de terminal.
     * 5. Confere se cada robô cadastrado tem a sua pasta.
     */
    override fun onCreate() {
        super.onCreate()

        // Backups do tipo Full (SAVE/FULL) juntam programas, variáveis, Data Bank e os
        // logs do controlador num texto só, que pode passar dos 2 MB (limite padrão do
        // CursorWindow do SQLite para uma linha). Sem isso, abrir um backup Full falha
        // ao ler a coluna "content" do banco. Precisa rodar antes de qualquer consulta.
        increaseCursorWindowSize()

        // cria a pasta /MyRobots na raiz do armazenamento interno
        createRootFolder()

        val database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "robot_database"
        )
        // Se a versão do banco mudar, ele é apagado e recriado (os dados não são migrados).
        .fallbackToDestructiveMigration()
        .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost/") // endereço de teste: ainda não existe servidor HTTP de verdade
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

        val apiService = retrofit.create(RobotApiService::class.java)

        robotRepository = RobotRepository(
            database.robotDao(),
            database.quickCommandDao(),
            database.backupDao(),
            apiService
        )
        
        terminalManager = KawasakiTerminalManager(this)

        // garante que todo robô cadastrado tenha a sua pasta
        verifyRobotFolders()
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
     * Cria a pasta /MyRobots na raiz do armazenamento, se ela ainda não existir.
     */
    private fun createRootFolder() {
        try {
            val root = Environment.getExternalStorageDirectory()
            val myRobotsDir = File(root, "MyRobots")
            if (!myRobotsDir.exists()) {
                myRobotsDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Percorre os robôs cadastrados e cria a pasta de cada um que estiver faltando (em segundo plano).
     */
    private fun verifyRobotFolders() {
        applicationScope.launch(Dispatchers.IO) {
            val robots = robotRepository.allRobots.first()
            robots.forEach { robot ->
                robotRepository.createRobotFolder(robot)
            }
        }
    }
}
