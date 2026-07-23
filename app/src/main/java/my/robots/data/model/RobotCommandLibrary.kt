package my.robots.data.model

data class RobotCommand(
    val label: String,
    val command: String,
    val description: String,
    val category: CommandCategory
)

enum class CommandCategory {
    SAVE, LOAD, SYSTEM, UTILITY
}

object RobotCommandLibrary {
    fun getCommandsForManufacturer(manufacturer: Manufacturer): List<RobotCommand> {
        return when (manufacturer) {
            Manufacturer.KAWASAKI -> getKawasakiCommands()
            else -> emptyList()
        }
    }

    private fun getKawasakiCommands(): List<RobotCommand> {
        return listOf(
            // Program and Data Storage Commands (SAVE)
            RobotCommand("Save Full Backup", "SAVE/FULL [ROBOT]_full", "Salva backup completo", CommandCategory.SAVE),
            RobotCommand("Save Programs", "SAVE/P [ROBOT]_progs", "Salva todos os programas", CommandCategory.SAVE),
            RobotCommand("Save Pose Vars", "SAVE/L [ROBOT]_poses", "Salva variáveis de posição (L)", CommandCategory.SAVE),
            RobotCommand("Save Real Vars", "SAVE/R [ROBOT]_reals", "Salva variáveis reais (R)", CommandCategory.SAVE),
            RobotCommand("Save Strings", "SAVE/S [ROBOT]_strings", "Salva variáveis de texto (S)", CommandCategory.SAVE),
            RobotCommand("Save System", "SAVE/SYS [ROBOT]_sys", "Salva dados do sistema", CommandCategory.SAVE),
            RobotCommand("Save Robot Data", "SAVE/ROB [ROBOT]_rob", "Salva dados do robô", CommandCategory.SAVE),
            RobotCommand("Save All Logs", "SAVE/ALLLOG [ROBOT]_logs", "Salva todos os logs", CommandCategory.SAVE),
            
            // Loading Commands (LOAD)
            RobotCommand("Load File", "LOAD [FILE].as", "Carrega um arquivo para a memória", CommandCategory.LOAD),
            
            // Utility / Monitoring
            RobotCommand("Status System", "ID", "Mostra o ID e versão do sistema", CommandCategory.SYSTEM),
            RobotCommand("Free Memory", "FREE", "Verifica memória livre", CommandCategory.SYSTEM),
            RobotCommand("List Files", "DIR", "Lista arquivos na memória", CommandCategory.UTILITY),
            RobotCommand("Reset", "DO RESET", "Executa comando de Reset", CommandCategory.UTILITY)
        )
    }
}
