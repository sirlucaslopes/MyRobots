package my.robots.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_commands")
data class QuickCommand(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val robotId: Int? = null,
    val manufacturer: Manufacturer? = null,
    val label: String,
    val command: String
)
