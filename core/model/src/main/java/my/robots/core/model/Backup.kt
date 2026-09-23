package my.robots.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Um backup do programa de um robô (é uma linha da tabela "backups").
 *
 * - content: o texto inteiro do arquivo .as (pode ser bem grande).
 * - programsCount / variablesCount / framesCount: contagens calculadas ao salvar.
 * - memoryUsage: tamanho do texto, em caracteres.
 * - robotId: de qual robô é o backup (-1 = arquivo aberto de fora do app).
 * - timestamp: data/hora da última alteração, em milissegundos.
 */
@Entity(tableName = "backups")
data class Backup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val robotId: Int,
    val backupName: String,
    val fileName: String,
    val content: String,
    val programsCount: Int = 0,
    val variablesCount: Int = 0,
    val framesCount: Int = 0,
    val memoryUsage: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Versão "leve" do Backup, sem o texto (content).
 *
 * Serve para montar listas sem carregar arquivos enormes na memória,
 * o que evita erro de falta de memória (OutOfMemory).
 */
data class BackupSummary(
    val id: Int,
    val robotId: Int,
    val backupName: String,
    val fileName: String,
    val programsCount: Int,
    val variablesCount: Int,
    val framesCount: Int,
    val memoryUsage: Long,
    val timestamp: Long
)
