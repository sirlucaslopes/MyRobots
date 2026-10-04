package my.robots.core.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.robots.core.database.BackupDao
import my.robots.core.database.ProjectDao
import my.robots.core.database.QuickCommandDao
import my.robots.core.database.RobotDao
import my.robots.core.model.*
import my.robots.core.common.FileUtil
import my.robots.core.common.ascode.AsBackupStats
import my.robots.core.data.security.SecretCipher
import my.robots.core.data.security.StoredSecret
import my.robots.core.data.storage.RobotFilesStorage
import my.robots.core.data.storage.StorageLocation
import kotlinx.coroutines.flow.StateFlow

/**
 * Porta de entrada única para os dados do app.
 *
 * As telas (ViewModels) nunca falam direto com o banco ou com as pastas do celular:
 * elas pedem tudo a esta classe. Ela junta:
 * - o banco de dados (robôs, comandos rápidos e backups) — a fonte da verdade: o texto
 *   completo de cada backup fica no banco;
 * - a pasta dos arquivos (RobotFilesStorage): Documentos/MyRobots ou a pasta escolhida.
 *
 * A senha de login do controlador (Robot.loginPassword) é guardada CIFRADA no banco
 * (SecretCipher, chave do Android Keystore). Tudo o que sai daqui já vem decifrado; tudo o que
 * entra é cifrado antes de gravar. Se não der para decifrar (banco restaurado em outro
 * celular), a senha volta vazia e o usuário digita de novo ao editar o robô.
 */
class RobotRepository(
    private val robotDao: RobotDao,
    private val quickCommandDao: QuickCommandDao,
    private val backupDao: BackupDao,
    private val projectDao: ProjectDao,
    private val files: RobotFilesStorage,
    private val secrets: SecretCipher
) {
    /**
     * Lista de todos os robôs cadastrados. Se um robô mudar, a lista se atualiza sozinha.
     */
    /**
     * Comandos rápidos que um robô novo recebe, por fabricante. O app troca pelos da tela
     * "Fabricantes" (ManufacturerSettings); o padrão é o RobotCommandLibrary.
     */
    var defaultCommandsFor: (Manufacturer) -> List<RobotCommand> = RobotCommandLibrary::getCommandsForManufacturer

    val allRobots: Flow<List<Robot>> = robotDao.getAllRobots()
        .map { robots -> robots.map { fromDb(it) } }
        .flowOn(Dispatchers.Default)

    /** Robô como vem do banco -> senha decifrada (vazia se não der para decifrar). */
    private fun fromDb(robot: Robot): Robot =
        robot.copy(loginPassword = secrets.decrypt(robot.loginPassword) ?: "")

    /** Robô para gravar no banco -> senha cifrada. */
    private fun toDb(robot: Robot): Robot =
        robot.copy(loginPassword = secrets.encrypt(robot.loginPassword))

    /**
     * Cifra as senhas que ainda estão em texto puro (gravadas até a v1.1). Não faz nada nas
     * que já estão cifradas; pode ser chamada a cada abertura do app.
     */
    suspend fun encryptLegacyPasswords() = withContext(Dispatchers.Default) {
        robotDao.getAllRobots().first()
            .filter { it.loginPassword.isNotEmpty() && !StoredSecret.isEncrypted(it.loginPassword) }
            .forEach { robotDao.updateRobot(toDb(it)) }
    }

    /**
     * Cadastra um robô novo.
     *
     * 1. Salva o robô no banco.
     * 2. Cria os comandos rápidos padrão da marca dele.
     * A pasta do robô aparece sozinha quando o primeiro arquivo dele é gravado.
     */
    suspend fun insertRobot(robot: Robot) {
        val id = robotDao.insertRobot(toDb(robot)).toInt()
        seedQuickCommandsForRobot(id, robot.manufacturer)
    }

    /**
     * Cria os comandos rápidos padrão (SAVE, LOAD, DIR...) para um robô recém-cadastrado,
     * usando a biblioteca de comandos da marca.
     */
    private suspend fun seedQuickCommandsForRobot(robotId: Int, manufacturer: Manufacturer) {
        val commands = defaultCommandsFor(manufacturer)
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
     * Atualiza os dados de um robô. Se o nome mudar, os próximos arquivos vão para a pasta com
     * o nome novo (os antigos continuam na pasta antiga e os backups continuam no banco).
     */
    suspend fun updateRobot(robot: Robot) {
        val current = robotDao.getRobotById(robot.id)
        // A posição na cabine e a série são do banco: quem edita o robô (RobotDialog) não as
        // conhece. Mudou de projeto: o robô cai em "fora do layout" no projeto novo.
        val positioned = when {
            current == null -> robot
            // mudou de projeto: sai do layout e perde o mestre (o par era do projeto antigo)
            current.project != robot.project -> robot.copy(layoutRow = null, layoutCol = null, serialNumber = current.serialNumber)
            else -> robot.copy(
                layoutRow = current.layoutRow, layoutCol = current.layoutCol,
                serialNumber = current.serialNumber, masterRobotId = current.masterRobotId
            )
        }
        robotDao.updateRobot(toDb(positioned))
        if (current != null && current.project != robot.project) projectDao.deleteLayoutIfEmpty(current.project)
    }

    /**
     * Apaga o robô do banco. Os arquivos dele na pasta não são apagados. Se o projeto dele
     * ficou sem robôs, o layout da cabine e os equipamentos do projeto também são apagados.
     */
    suspend fun deleteRobot(robot: Robot) {
        robotDao.deleteRobot(robot)
        projectDao.deleteLayoutIfEmpty(robot.project)
    }
    /**
     * Grava o número de série do controlador do robô ("CPF" do robô).
     */
    suspend fun setRobotSerialNumber(robotId: Int, serial: String?) = robotDao.setSerialNumber(robotId, serial)

    /**
     * Busca um robô pelo id. Devolve null se não existir.
     */
    suspend fun getRobotById(id: Int): Robot? = robotDao.getRobotById(id)?.let { fromDb(it) }

    // ---------- Projetos (cabine) ----------
    /**
     * Tamanho da grade da cabine do projeto. Sem layout gravado, devolve o padrão 2×2.
     */
    fun getProjectLayout(projectName: String): Flow<ProjectLayout> =
        projectDao.getLayout(projectName).map { it ?: ProjectLayout(projectName) }

    /**
     * Equipamentos da cabine do projeto, por faixa e ordem.
     */
    fun getProjectEquipment(projectName: String): Flow<List<ProjectEquipment>> =
        projectDao.getEquipment(projectName)

    /**
     * Grava a edição do layout de uma vez (numa transação): tamanho da grade, posição de cada
     * robô (null = fora do layout) e a lista completa de equipamentos.
     */
    suspend fun saveProjectLayout(
        layout: ProjectLayout,
        robotPositions: Map<Int, Pair<Int?, Int?>>,
        equipment: List<ProjectEquipment>
    ) = projectDao.saveLayout(layout, robotPositions, equipment)

    /**
     * Grava a configuração mestre/escravo do projeto [slaveProject]: o projeto mestre (null =
     * desfaz), a variável de offset da base e o mestre de cada robô (robô escravo -> robô
     * mestre; null = sem par). Tudo numa transação.
     */
    suspend fun saveMasterConfig(
        slaveProject: String,
        masterProject: String?,
        baseOffset: String,
        pairs: Map<Int, Int?>
    ) = projectDao.saveMasterConfig(slaveProject, masterProject, baseOffset, pairs)

    /**
     * Renomeia o projeto nos robôs, no layout e nos equipamentos (numa transação).
     */
    suspend fun renameProject(oldName: String, newName: String) = projectDao.renameProject(oldName, newName)

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
     * Trechos ".OPE_INFO1" de todos os backups do robô (mais antigo primeiro), para o
     * gráfico de uso. Leve: o banco recorta o trecho, o texto completo não é carregado.
     */
    suspend fun getUsageSnippets(robotId: Int) = backupDao.getUsageSnippets(robotId)
    
    /**
     * Salva um backup no banco e, se saveToFile for true, também como arquivo na pasta do robô.
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
     * Devolve false se não conseguiu gravar (o backup continua salvo no banco).
     */
    suspend fun saveBackupToFile(backup: Backup): Boolean =
        saveFileToRobotFolder(backup.robotId, backup.fileName, backup.content)

    /**
     * Grava um arquivo de texto na pasta do robô (<pasta dos arquivos>/<robô>/<arquivo>).
     * Devolve false se o robô não existe ou se a gravação falhou (o motivo vai para o log).
     */
    suspend fun saveFileToRobotFolder(robotId: Int, fileName: String, content: String): Boolean {
        val robot = getRobotById(robotId) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                files.openOutput(robot.name, FileUtil.sanitizeFileName(fileName)).use { out ->
                    out.write(encodeAsText(content))
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Apaga o backup do banco e o arquivo dele da pasta do robô.
     */
    suspend fun deleteBackupAndFile(backupId: Int, fileName: String) {
        val robotId = backupDao.getBackupsSummaryById(backupId)?.robotId
        backupDao.deleteBackupById(backupId)
        val robot = robotId?.let { getRobotById(it) } ?: return
        withContext(Dispatchers.IO) { files.delete(robot.name, fileName) }
    }

    /**
     * Traz para o banco os arquivos .as da pasta do robô que ainda não estão nele (ex.: um SAVE
     * feito pelo terminal, ou um arquivo copiado pelo PC para a pasta escolhida). Devolve
     * quantos backups novos entraram. Os arquivos que o app grava só para enviar ao robô
     * (`FileUtil.isTransferFile`: transfer_*, var_*, db_*) ficam de fora: não são backups.
     *
     * NUNCA apaga backup do banco por causa de arquivo ausente: o banco guarda o texto
     * completo e é a fonte da verdade. (Até a v1.1 apagava, o que com a pasta nova — que
     * pode não enxergar arquivos antigos depois de reinstalar, ou perder a permissão —
     * poderia apagar todos os backups.)
     */
    suspend fun syncRobotFolder(robot: Robot): Int = withContext(Dispatchers.IO) {
        val known = backupDao.getBackupsSummaryForRobot(robot.id).first()
            .map { it.fileName.lowercase() }.toSet()
        var imported = 0
        files.list(robot.name).forEach { info ->
            if (info.name.lowercase() in known || FileUtil.isTransferFile(info.name)) return@forEach
            val bytes = files.read(robot.name, info.name) ?: return@forEach
            insertBackup(
                Backup(
                    robotId = robot.id,
                    backupName = "Sinc: ${info.name}",
                    fileName = info.name,
                    content = decodeAsText(bytes),
                    timestamp = if (info.lastModified > 0) info.lastModified else System.currentTimeMillis()
                ),
                saveToFile = false
            )
            imported++
        }
        imported
    }

    /**
     * Regrava na pasta dos arquivos cada backup do banco cujo arquivo não está lá. Usado na
     * migração da v1.2 (a pasta /MyRobots antiga deixou de ser acessível) e depois de trocar
     * de pasta. Devolve quantos arquivos foram gravados.
     */
    suspend fun restoreMissingFiles(): Int = withContext(Dispatchers.IO) {
        var written = 0
        allRobots.first().forEach { robot ->
            val existing = files.list(robot.name).map { it.name.lowercase() }.toSet()
            backupDao.getBackupsSummaryForRobot(robot.id).first().forEach { summary ->
                val name = FileUtil.sanitizeFileName(summary.fileName)
                if (name.lowercase() in existing) return@forEach
                // um por vez, para não carregar todos os textos na memória
                val backup = getBackupById(summary.id) ?: return@forEach
                if (saveFileToRobotFolder(robot.id, name, backup.content)) written++
            }
        }
        written
    }

    // ---------- Pasta dos arquivos ----------
    /** Onde os arquivos estão sendo gravados (pasta padrão ou pasta escolhida). */
    val storageLocation: StateFlow<StorageLocation> get() = files.location

    /** Reconfere se a pasta escolhida continua disponível. */
    fun refreshStorageLocation() = files.refresh()

    /**
     * Passa a usar a pasta escolhida no seletor, grava nela os backups que faltam e importa
     * os .as que já estavam lá. Devolve (gravados, importados).
     */
    suspend fun useStorageFolder(treeUri: Uri): Pair<Int, Int> {
        withContext(Dispatchers.IO) { files.useFolder(treeUri) }
        return restoreMissingFiles() to syncAllRobotFolders()
    }

    /** Volta para a pasta padrão Documentos/MyRobots e grava nela os backups que faltam. */
    suspend fun useDefaultStorage(): Int {
        files.useDefault()
        return restoreMissingFiles()
    }

    /** Uri para abrir a pasta dos arquivos no gerenciador de arquivos do Android. */
    fun filesFolderUri(): Uri = files.folderViewUri()

    /** syncRobotFolder de todos os robôs. Devolve o total de backups importados. */
    suspend fun syncAllRobotFolders(): Int {
        var total = 0
        allRobots.first().forEach { robot ->
            val imported = syncRobotFolder(robot)
            if (imported > 0) refreshBackupsMetadata(robot.id)
            total += imported
        }
        return total
    }

    /**
     * Texto <-> bytes dos arquivos AS. Hoje UTF-8, como a v1.1 sempre leu e gravou; é o único
     * lugar a mudar quando a codificação do controlador for confirmada (Fase 0-B.D do plano).
     */
    private fun encodeAsText(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)
    private fun decodeAsText(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

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
