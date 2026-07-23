package my.robots.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Manufacturer(val displayName: String) {
    KAWASAKI("Kawasaki (AS)"),
    FANUC("Fanuc (KAREL)"),
    ABB("ABB (RAPID)"),
    UNIVERSAL_ROBOTS("Universal Robots (URScript)")
}

@Entity(tableName = "robots")
data class Robot(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val ip: String,
    val port: Int,
    val project: String = "Padrão",
    val manufacturer: Manufacturer = Manufacturer.KAWASAKI,
    val autoLogin: Boolean = false,
    val loginUser: String = "as",
    val loginPassword: String = ""
)
