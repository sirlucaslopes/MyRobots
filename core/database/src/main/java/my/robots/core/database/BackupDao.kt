package my.robots.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import my.robots.core.model.Backup
import my.robots.core.model.BackupSummary
import my.robots.core.model.BackupUsageSnippet

/**
 * Consultas da tabela de backups.
 */
@Dao
interface BackupDao {
    /**
     * Lista os backups de um robô, do mais novo para o mais antigo,
     * SEM o texto do arquivo (só o resumo), para a lista ficar leve.
     */
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsSummaryForRobot(robotId: Int): Flow<List<BackupSummary>>

    /**
     * Procura backups de um robô pelo texto digitado, olhando o nome do
     * arquivo e o nome do backup. Devolve só o resumo (sem o conteúdo).
     */
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE robotId = :robotId AND (fileName LIKE '%' || :query || '%' OR backupName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchBackupsSummary(robotId: Int, query: String): Flow<List<BackupSummary>>

    /**
     * Para cada backup do robô, só o trecho da seção ".OPE_INFO1" (1500 caracteres a partir
     * dela), do mais antigo para o mais novo. O recorte é feito no SQLite, então o texto
     * completo dos backups não vem para a memória do app.
     */
    @Query("""
        SELECT id, timestamp, fileName,
               CASE WHEN instr(content, '.OPE_INFO1') > 0
                    THEN substr(content, instr(content, '.OPE_INFO1'), 1500)
                    ELSE '' END AS snippet
        FROM backups WHERE robotId = :robotId ORDER BY timestamp ASC
    """)
    suspend fun getUsageSnippets(robotId: Int): List<BackupUsageSnippet>

    /**
     * Lista os backups de um robô COM o texto completo. Pesado: use só quando precisar.
     */
    @Query("SELECT * FROM backups WHERE robotId = :robotId ORDER BY timestamp DESC")
    fun getBackupsForRobot(robotId: Int): Flow<List<Backup>>

    /**
     * Salva um backup (se o id já existir, ele é substituído). Devolve o id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: Backup): Long

    /**
     * Apaga um backup do banco.
     */
    @Delete
    suspend fun deleteBackup(backup: Backup)

    /**
     * Resumo (sem o texto) de um backup pelo id. Devolve null se não existir.
     */
    @Query("SELECT id, robotId, backupName, fileName, programsCount, variablesCount, framesCount, memoryUsage, timestamp FROM backups WHERE id = :id")
    suspend fun getBackupsSummaryById(id: Int): BackupSummary?

    /**
     * Busca um backup completo (com o texto) pelo id, numa linha só. Falha com backup grande
     * (a linha não cabe no CursorWindow): use [getBackupChunked].
     */
    @Query("SELECT * FROM backups WHERE id = :id")
    suspend fun getBackupById(id: Int): Backup?

    /** Tamanho do texto do backup, em caracteres (null se não existir). */
    @Query("SELECT length(content) FROM backups WHERE id = :id")
    suspend fun getContentLength(id: Int): Int?

    /** Um pedaço do texto do backup: [len] caracteres a partir de [start] (começa em 1). */
    @Query("SELECT substr(content, :start, :len) FROM backups WHERE id = :id")
    suspend fun getContentPart(id: Int, start: Int, len: Int): String?

    /**
     * Backup completo lido em pedaços de [CHUNK] caracteres: cada consulta traz uma linha
     * pequena, então qualquer tamanho de backup cabe no CursorWindow, mesmo com vários sendo
     * lidos ao mesmo tempo (o SAVE/FULL de um robô de pintura passa de 4 MB).
     */
    @Transaction
    suspend fun getBackupChunked(id: Int): Backup? {
        val summary = getBackupsSummaryById(id) ?: return null
        val length = getContentLength(id) ?: 0
        val text = StringBuilder(length)
        var start = 1
        while (start <= length) {
            text.append(getContentPart(id, start, CHUNK).orEmpty())
            start += CHUNK
        }
        return Backup(
            id = summary.id, robotId = summary.robotId, backupName = summary.backupName,
            fileName = summary.fileName, content = text.toString(),
            programsCount = summary.programsCount, variablesCount = summary.variablesCount,
            framesCount = summary.framesCount, memoryUsage = summary.memoryUsage, timestamp = summary.timestamp
        )
    }

    companion object {
        /** Tamanho de cada pedaço do texto (caracteres): bem abaixo dos 2 MB do CursorWindow. */
        const val CHUNK = 512 * 1024
    }
    
    /**
     * Apaga um backup usando só o id dele.
     */
    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteBackupById(id: Int)
}
