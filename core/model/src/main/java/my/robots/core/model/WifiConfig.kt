package my.robots.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Uma configuração de rede Wifi (SSID, senha e, se quiser, IP fixo).
 *
 * Usada pela tela de configurações de Wifi. Ainda não é salva no banco.
 */
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
