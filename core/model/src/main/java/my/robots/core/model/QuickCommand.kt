package my.robots.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Um comando rápido: um botão que envia um comando pronto ao robô.
 *
 * - label: nome que aparece no botão (ex.: "Save Full Backup").
 * - command: o texto enviado ao robô. Aceita as etiquetas [ROBOT] (nome do
 *   robô) e [DATA] (data e hora), que são trocadas na hora de enviar.
 * - robotId / manufacturer: a quem o comando pertence.
 */
@Entity(tableName = "quick_commands")
data class QuickCommand(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val robotId: Int? = null,
    val manufacturer: Manufacturer? = null,
    val label: String,
    val command: String
)
