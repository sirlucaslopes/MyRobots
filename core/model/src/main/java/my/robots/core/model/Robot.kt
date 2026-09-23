package my.robots.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Marcas de robô que o app conhece.
 *
 * Hoje só a KAWASAKI (linguagem AS) tem suporte completo (terminal, backups
 * e comandos rápidos). As outras marcas já podem ser cadastradas, mas ainda
 * não têm funções próprias.
 * "displayName" é o nome bonito que aparece na tela.
 */
enum class Manufacturer(val displayName: String) {
    KAWASAKI("Kawasaki (AS)"),
    FANUC("Fanuc (KAREL)"),
    ABB("ABB (RAPID)"),
    UNIVERSAL_ROBOTS("Universal Robots (URScript)")
}

/**
 * Um robô cadastrado no app (é uma linha da tabela "robots" do banco).
 *
 * - id: número único criado automaticamente.
 * - name: nome do robô (também vira o nome da pasta dele em /MyRobots).
 * - ip / port: endereço de rede do controlador. A porta padrão do telnet é 23.
 * - project: grupo/célula a que o robô pertence (ex.: "Célula 01").
 * - manufacturer: marca do robô.
 * - autoLogin / loginUser / loginPassword: se ligado, o app digita usuário e
 *   senha sozinho quando o terminal pede.
 */
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
