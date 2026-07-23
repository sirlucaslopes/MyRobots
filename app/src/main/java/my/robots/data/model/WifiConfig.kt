package my.robots.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wifi_configs")
data class WifiConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ssid: String,
    val password: String,
    val isStaticIp: Boolean = false,
    val ipAddress: String? = null,
    val gateway: String? = null,
    val mask: String? = null,
    val dns1: String? = null,
    val dns2: String? = null
)
