package my.robots.ui.backup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class BackupType(
    val label: String,
    val commandPrefix: String,
    val description: String,
    val icon: ImageVector
) {
    FULL("Full", "SAVE/FULL", "Tudo", Icons.Rounded.Storage),
    PROGRAMS("Progs", "SAVE/P", "Programas", Icons.Rounded.Code),
    POSE_VARS("Poses", "SAVE/L", "Variáveis L", Icons.Rounded.LocationOn),
    REAL_VARS("Reals", "SAVE/R", "Variáveis R", Icons.Rounded.Tune),
    STRINGS("Strings", "SAVE/S", "Texto", Icons.Rounded.TextFormat),
    AUX("Aux", "SAVE/A", "Auxiliar", Icons.Rounded.SettingsInputComponent),
    SYSTEM("System", "SAVE/SYS", "Sistema", Icons.Rounded.Settings),
    ROBOT("Robot", "SAVE/ROB", "Robô", Icons.Rounded.SmartToy),
    ERROR_LOG("Errors", "SAVE/EAG", "Erros", Icons.Rounded.ErrorOutline),
    OP_LOG("Op Log", "SAVE/OPLOG", "Operação", Icons.AutoMirrored.Rounded.Assignment),
    ALL_LOG("All Logs", "SAVE/ALLLOG", "Logs", Icons.Rounded.History)
}
