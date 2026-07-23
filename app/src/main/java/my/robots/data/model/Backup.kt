package my.robots.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

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
 * Versão leve do Backup para exibição em listas, evitando carregar o conteúdo pesado (String content)
 * o que previne erros de memória (OutOfMemory).
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
