package my.robots.core.data

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.robots.core.database.BackupDao
import my.robots.core.database.QuickCommandDao
import my.robots.core.database.RobotDao
import my.robots.core.model.*
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsBackupStats
import java.io.File

/**
 * Porta de entrada única para os dados do app.
 *
 * As telas (ViewModels) nunca falam direto com o banco, com a rede ou com as
 * pastas do celular: elas pedem tudo a esta classe. Ela junta:
 * - o banco de dados (robôs, comandos rápidos e backups);
 * - os arquivos na pasta /MyRobots do celular.
 */
class RobotRepository(
    private val robotDao: RobotDao,
    private val quickCommandDao: QuickCommandDao,
    private val backupDao: BackupDao
) {
    /**
     * Lista de todos os robôs cadastrados. Se um robô mudar, a lista se atualiza sozinha.
     */
    val allRobots: Flow<List<Robot>> = robotDao.getAllRobots()

    /**
     * Cadastra um robô novo.
     *
     * 1. Salva o robô no banco.
     * 2. Cria a pasta dele em /MyRobots.
     * 3. Cria os comandos rápidos padrão da marca dele.
     */
    suspend fun insertRobot(robot: Robot) {
        val id = robotDao.insertRobot(robot).toInt()
        createRobotFolder(robot.copy(id = id))
        seedQuickCommandsForRobot(id, robot.manufacturer)
    }

    /**
     * Cria os comandos rápidos padrão (SAVE, LOAD, DIR...) para um robô recém-cadastrado,
     * usando a biblioteca de comandos da marca.
     */
    private suspend fun seedQuickCommandsForRobot(robotId: Int, manufacturer: Manufacturer) {
        val commands = RobotCommandLibrary.getCommandsForManufacturer(manufacturer)
        commands.forEach { cmd ->
            quickCommandDao.insertQuickCommand(
                QuickCommand(
                    robotId = robotId,
                    manufacturer = manufacturer,
                    label = cmd.label,
                    command = cmd.command
                )
            )
        }
    }

    /**
     * Atualiza os dados de um robô e garante que a pasta dele existe (o nome pode ter mudado).
     */
    suspend fun updateRobot(robot: Robot) {
        robotDao.updateRobot(robot)
        createRobotFolder(robot)
    }

    /**
     * Cria a pasta do robô dentro de /MyRobots, se ainda não existir.
     * O nome da pasta é o nome do robô em minúsculo, trocando caracteres especiais por "_".
     */
    fun createRobotFolder(robot: Robot) {
        try {
            val root = Environment.getExternalStorageDirectory()
            val myRobotsDir = File(root, "MyRobots")
            val robotDirName = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val robotDir = File(myRobotsDir, robotDirName)
            if (!robotDir.exists()) {
                robotDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Apaga o robô do banco. Os arquivos dele na pasta /MyRobots não são apagados.
     */
    suspend fun deleteRobot(robot: Robot) = robotDao.deleteRobot(robot)
    /**
     * Busca um robô pelo id. Devolve null se não existir.
     */
    suspend fun getRobotById(id: Int): Robot? = robotDao.getRobotById(id)

    // ---------- Comandos rápidos ----------
    /**
     * Lista os comandos rápidos de um robô. A lista se atualiza sozinha.
     */
    fun getQuickCommands(robotId: Int) = quickCommandDao.getQuickCommandsForRobot(robotId)
    
    /**
     * Lista os comandos rápidos de uma marca (usado pelo terminal geral e pela tela de comandos).
     * Pega todos e filtra pela marca.
     */
    fun getQuickCommandsByManufacturer(manufacturer: Manufacturer): Flow<List<QuickCommand>> {
        return quickCommandDao.getAllQuickCommands().map { all ->
            all.filter { it.manufacturer == manufacturer }
        }
    }

    /**
     * Salva um comando rápido novo.
     */
    suspend fun insertQuickCommand(command: QuickCommand) = quickCommandDao.insertQuickCommand(command)
    /**
     * Atualiza um comando rápido (o banco troca o que tem o mesmo id).
     */
    suspend fun updateQuickCommand(command: QuickCommand) = quickCommandDao.insertQuickCommand(command)
    /**
     * Apaga um comando rápido.
     */
    suspend fun deleteQuickCommand(command: QuickCommand) = quickCommandDao.deleteQuickCommand(command)

    // ---------- Backups ----------
    /**
     * Lista os backups de um robô só com o resumo (leve, sem o texto do arquivo).
     */
    fun getBackupsSummary(robotId: Int) = backupDao.getBackupsSummaryForRobot(robotId)
    /**
     * Procura backups de um robô pelo texto digitado (nome do arquivo ou do backup).
     */
    fun searchBackupsSummary(robotId: Int, query: String) = backupDao.searchBackupsSummary(robotId, query)
    /**
     * Lista os backups de um robô COM o texto completo. Pesado: use só quando precisar.
     */
    fun getBackupsForRobotFull(robotId: Int) = backupDao.getBackupsForRobot(robotId)
    
    /**
     * Salva um backup no banco e, se saveToFile for true, também como arquivo em /MyRobots/<robô>/.
     *
     * O texto do backup é gravado exatamente como veio (mesmo que o controlador tenha
     * anexado seções estranhas ao idioma AS, como despejos de diagnóstico do sistema) —
     * nada é apagado do arquivo. Só a contagem de programas/variáveis (calculateAndApplyMetadata)
     * ignora essas seções, sem precisar mexer no texto guardado.
     *
     * 1. Limpa o nome do arquivo (só letras, números e _).
     * 2. Conta programas e variáveis do texto.
     * 3. Grava no banco.
     * 4. Grava o arquivo na pasta do robô.
     * Devolve o id do backup salvo.
     */
    suspend fun insertBackup(backup: Backup, saveToFile: Boolean = true): Int {
        val updatedBackup = withContext(Dispatchers.Default) {
            val sanitizedBackup = backup.copy(fileName = FileUtil.sanitizeFileName(backup.fileName))
            calculateAndApplyMetadata(sanitizedBackup)
        }
        val id = backupDao.insertBackup(updatedBackup).toInt()
        if (saveToFile) {
            saveBackupToFile(updatedBackup.copy(id = id))
        }
        return id
    }
    
    /**
     * Apaga um backup do banco (o arquivo na pasta não é apagado aqui).
     */
    suspend fun deleteBackup(backup: Backup) = backupDao.deleteBackup(backup)
    /**
     * Apaga um backup do banco usando só o id dele.
     */
    suspend fun deleteBackupById(id: Int) = backupDao.deleteBackupById(id)
    /**
     * Busca um backup completo (com o texto) pelo id. Devolve null se não existir OU se o
     * texto do backup (caso de um backup Full, bem maior que os outros tipos) não couber
     * na leitura do banco — sem isso, abrir um backup grande derrubava o app inteiro.
     */
    suspend fun getBackupById(id: Int): Backup? = try {
        backupDao.getBackupById(id)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /**
     * Grava o texto do backup como arquivo dentro da pasta do robô dono dele.
     */
    suspend fun saveBackupToFile(backup: Backup) {
        saveFileToRobotFolder(backup.robotId, backup.fileName, backup.content)
    }

    /**
     * Grava um arquivo de texto na pasta do robô (/MyRobots/<robô>/<arquivo>).
     * Cria as pastas se não existirem. Se der erro ao gravar, o erro é ignorado.
     */
    suspend fun saveFileToRobotFolder(robotId: Int, fileName: String, content: String) {
        val robot = getRobotById(robotId) ?: return
        val robotDirName = robot.name.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
        val root = Environment.getExternalStorageDirectory()
        val myRobotsRootDir = File(root, "MyRobots")
        val robotDir = File(myRobotsRootDir, robotDirName)
        
        if (!myRobotsRootDir.exists()) myRobotsRootDir.mkdirs()
        if (!robotDir.exists()) robotDir.mkdirs()
        
        withContext(Dispatchers.IO) {
            try {
                val sanitizedFileName = FileUtil.sanitizeFileName(fileName)
                val robotFile = File(robotDir, sanitizedFileName)
                robotFile.writeText(content)
            } catch (e: Exception) { }
        }
    }

    /**
     * Recalcula as contagens (programas e variáveis) dos backups de um robô.
     * Só mexe nos backups que estão com as duas contagens zeradas, para não perder tempo.
     */
    suspend fun refreshBackupsMetadata(robotId: Int) {
        val backupSummaries = backupDao.getBackupsSummaryForRobot(robotId).first()
        backupSummaries.forEach { summary ->
            if (summary.programsCount == 0 && summary.variablesCount == 0) {
                // carrega um por vez, só quando precisa, para não estourar a memória
                val backup = backupDao.getBackupById(summary.id)
                if (backup != null) {
                    val updated = calculateAndApplyMetadata(backup)
                    if (updated.programsCount > 0 || updated.variablesCount > 0) {
                        backupDao.insertBackup(updated)
                    }
                }
            }
        }
    }

    /**
     * Conta programas e variáveis do texto (AsBackupStats) e guarda o tamanho do texto
     * ORIGINAL em memoryUsage. O texto em si NUNCA é alterado.
     */
    private fun calculateAndApplyMetadata(backup: Backup): Backup {
        val content = backup.content
        if (content.isBlank()) return backup
        val stats = AsBackupStats.count(content)
        return backup.copy(
            programsCount = stats.programs,
            variablesCount = stats.variables,
            memoryUsage = content.length.toLong()
        )
    }
}
