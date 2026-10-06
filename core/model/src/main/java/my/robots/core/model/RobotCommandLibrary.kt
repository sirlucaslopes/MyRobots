package my.robots.core.model

/**
 * Um comando pronto da biblioteca: nome do botão, texto do comando,
 * explicação e categoria.
 */
data class RobotCommand(
    val label: String,
    val command: String,
    val description: String,
    val category: CommandCategory
)

/**
 * Grupo do comando: SAVE (salvar), LOAD (carregar), SYSTEM (consulta ao sistema),
 * CONTROL (controle do robô e dos programas) ou UTILITY (utilidades).
 */
enum class CommandCategory {
    SAVE, LOAD, SYSTEM, CONTROL, UTILITY
}

/**
 * Biblioteca com os comandos prontos de cada marca de robô.
 * É daqui que saem os comandos rápidos criados junto com um robô novo.
 */
object RobotCommandLibrary {
    /**
     * Devolve a lista de comandos prontos da marca informada.
     * Por enquanto só a Kawasaki tem comandos; as outras marcas devolvem lista vazia.
     */
    fun getCommandsForManufacturer(manufacturer: Manufacturer): List<RobotCommand> {
        return when (manufacturer) {
            Manufacturer.KAWASAKI -> getKawasakiCommands()
            else -> emptyList()
        }
    }

    /**
     * Lista dos comandos da Kawasaki (linguagem AS).
     *
     * Nos textos, [ROBOT] é trocado pelo nome do robô e [FILE] pelo arquivo escolhido.
     */
    private fun getKawasakiCommands(): List<RobotCommand> {
        return listOf(
            // Comandos para SALVAR dados do robô em arquivo (SAVE)
            RobotCommand("Save Full Backup", "SAVE/FULL [ROBOT]_full", "Salva backup completo", CommandCategory.SAVE),
            RobotCommand("Save Programs", "SAVE/P [ROBOT]_progs", "Salva todos os programas", CommandCategory.SAVE),
            RobotCommand("Save Pose Vars", "SAVE/L [ROBOT]_poses", "Salva variáveis de posição (L)", CommandCategory.SAVE),
            RobotCommand("Save Real Vars", "SAVE/R [ROBOT]_reals", "Salva variáveis reais (R)", CommandCategory.SAVE),
            RobotCommand("Save Strings", "SAVE/S [ROBOT]_strings", "Salva variáveis de texto (S)", CommandCategory.SAVE),
            RobotCommand("Save System", "SAVE/SYS [ROBOT]_sys", "Salva dados do sistema", CommandCategory.SAVE),
            RobotCommand("Save Robot Data", "SAVE/ROB [ROBOT]_rob", "Salva dados do robô", CommandCategory.SAVE),
            RobotCommand("Save All Logs", "SAVE/ALLLOG [ROBOT]_logs", "Salva todos os logs", CommandCategory.SAVE),
            
            // Comando para CARREGAR um arquivo no robô (LOAD)
            RobotCommand("Load File", "LOAD [FILE].as", "Carrega um arquivo para a memória", CommandCategory.LOAD),
            
            // Comandos de consulta
            RobotCommand("Status System", "ID", "Mostra o ID e versão do sistema", CommandCategory.SYSTEM),
            RobotCommand("Free Memory", "FREE", "Verifica memória livre", CommandCategory.SYSTEM),
            RobotCommand(
                "Robot Task", "TYPE TASK (1)",
                "Estado do programa do robô: 0 parado, 1 rodando, 2 em pausa (HOLD)", CommandCategory.SYSTEM
            ),
            RobotCommand(
                "PC Task 1", "TYPE TASK (1001)",
                "Estado do programa PC 1 (1002 a 1005 para os outros): 0 parado, 1 rodando, 2 em pausa", CommandCategory.SYSTEM
            ),

            // Controle do robô e dos programas: os mesmos comandos que o KIDE manda em cada função
            // (gravados com tools/kroset_captura.py no K-ROSET; ver docs/KIDE_COMANDOS.md)
            RobotCommand("Error Reset", "ERESET", "Limpa o erro do controlador", CommandCategory.CONTROL),
            RobotCommand("Hold", "HOLD", "Pausa o programa do robô (CONTINUE retoma)", CommandCategory.CONTROL),
            RobotCommand(
                "Continue", "CONTINUE",
                "Retoma o programa pausado; com o motor desligado o robô recusa (P1000)", CommandCategory.CONTROL
            ),
            RobotCommand("Motor ON", "ZPOW ON", "Liga o motor (como o botão do KIDE)", CommandCategory.CONTROL),
            RobotCommand(
                "Motor OFF", "ZPOW OFF",
                "Desliga o motor; o KIDE manda HOLD antes, para pausar o programa", CommandCategory.CONTROL
            ),
            RobotCommand("Speed 50%", "SPEED 50", "Velocidade de monitor em % (vale para todos os programas)", CommandCategory.CONTROL),
            RobotCommand("Abort", "ABORT", "Para o programa do robô no fim do passo atual", CommandCategory.CONTROL),
            RobotCommand(
                "Kill", "KILL",
                "Limpa a pilha do programa do robô; o robô pergunta \"Are you sure? (Yes:1, No:0)\": responda 1",
                CommandCategory.CONTROL
            ),
            RobotCommand("PC Abort 1", "PCABORT 1:", "Para o programa PC 1 (troque o número: 1 a 5)", CommandCategory.CONTROL),
            RobotCommand(
                "PC Kill 1", "PCKILL 1:",
                "Limpa a pilha do programa PC 1; o robô pergunta \"Are you sure? (Yes:1, No:0)\": responda 1",
                CommandCategory.CONTROL
            ),

            // Utilidades
            RobotCommand("List Files", "DIR", "Lista arquivos na memória", CommandCategory.UTILITY)
        )
    }
}
