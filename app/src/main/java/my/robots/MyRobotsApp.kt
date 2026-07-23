package my.robots

import android.app.Application
import android.os.Environment
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import my.robots.data.local.AppDatabase
import my.robots.data.remote.KawasakiTerminalManager
import my.robots.data.remote.RobotApiService
import my.robots.data.repository.RobotRepository
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

class MyRobotsApp : Application() {

    lateinit var robotRepository: RobotRepository
    lateinit var terminalManager: KawasakiTerminalManager
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Cria a pasta MyRobots na raiz do armazenamento interno ao iniciar
        createRootFolder()

        val database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "robot_database"
        )
        .fallbackToDestructiveMigration()
        .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost/") // Placeholder
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

        // Verifica e cria as pastas para todos os robôs cadastrados
        verifyRobotFolders()
    }

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

    private fun verifyRobotFolders() {
        applicationScope.launch(Dispatchers.IO) {
            val robots = robotRepository.allRobots.first()
            robots.forEach { robot ->
                robotRepository.createRobotFolder(robot)
            }
        }
    }
}
