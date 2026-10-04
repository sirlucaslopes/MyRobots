package my.robots.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.robots.core.model.CommandCategory
import my.robots.core.model.Manufacturer
import my.robots.core.model.RobotCommand
import my.robots.core.model.RobotCommandLibrary

/**
 * Configurações de cada fabricante, editadas na tela "Fabricantes" (⋮ da lista de robôs) e
 * guardadas no aparelho (SharedPreferences "manufacturer_settings"):
 * - **termos da pesquisa rápida** do editor de programas (o botão de lista no campo de
 *   pesquisa); o editor de código AS usa os da Kawasaki;
 * - **comandos rápidos padrão**: os que um robô novo daquele fabricante recebe ao ser
 *   cadastrado (os de cada robô continuam editáveis no terminal dele).
 * Sem nada gravado, valem os padrões ([DEFAULT_TERMS] e o RobotCommandLibrary).
 */
class ManufacturerSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("manufacturer_settings", Context.MODE_PRIVATE)
    private val terms = Manufacturer.entries.associateWith { MutableStateFlow(loadTerms(it)) }
    private val commands = Manufacturer.entries.associateWith { MutableStateFlow(loadCommands(it)) }

    /** Termos da pesquisa rápida do fabricante, na ordem escolhida. */
    fun searchTerms(m: Manufacturer): StateFlow<List<String>> = terms.getValue(m).asStateFlow()

    fun setSearchTerms(m: Manufacturer, list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString("terms_${m.name}", clean.joinToString("\n")).apply()
        terms.getValue(m).value = clean
    }

    fun resetSearchTerms(m: Manufacturer) {
        prefs.edit().remove("terms_${m.name}").apply()
        terms.getValue(m).value = defaultTerms(m)
    }

    /** Comandos rápidos que um robô novo do fabricante recebe. */
    fun defaultCommands(m: Manufacturer): StateFlow<List<RobotCommand>> = commands.getValue(m).asStateFlow()

    fun setDefaultCommands(m: Manufacturer, list: List<RobotCommand>) {
        val clean = list.filter { it.label.isNotBlank() && it.command.isNotBlank() }
        // uma linha por comando: nome<TAB>comando<TAB>explicação<TAB>categoria
        val text = clean.joinToString("\n") { c ->
            listOf(c.label, c.command, c.description, c.category.name).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }
        prefs.edit().putString("commands_${m.name}", text).apply()
        commands.getValue(m).value = clean
    }

    fun resetDefaultCommands(m: Manufacturer) {
        prefs.edit().remove("commands_${m.name}").apply()
        commands.getValue(m).value = RobotCommandLibrary.getCommandsForManufacturer(m)
    }

    fun defaultTerms(m: Manufacturer): List<String> = DEFAULT_TERMS[m].orEmpty()

    private fun loadTerms(m: Manufacturer): List<String> =
        prefs.getString("terms_${m.name}", null)?.split("\n")?.filter { it.isNotBlank() } ?: defaultTerms(m)

    private fun loadCommands(m: Manufacturer): List<RobotCommand> {
        val text = prefs.getString("commands_${m.name}", null) ?: return RobotCommandLibrary.getCommandsForManufacturer(m)
        return text.split("\n").filter { it.isNotBlank() }.map { line ->
            val p = line.split("\t")
            RobotCommand(
                label = p.getOrElse(0) { "" },
                command = p.getOrElse(1) { "" },
                description = p.getOrElse(2) { "" },
                category = p.getOrNull(3)?.let { c -> CommandCategory.entries.firstOrNull { it.name == c } } ?: CommandCategory.UTILITY
            )
        }
    }

    companion object {
        /** Termos mais usados de cada linguagem, para a pesquisa rápida. */
        val DEFAULT_TERMS: Map<Manufacturer, List<String>> = mapOf(
            Manufacturer.KAWASAKI to listOf(
                ".PROGRAM", ".END", "LMOVE", "JMOVE", "SPRAY", "SPRAY_SPEED", "AIRCUT_SPEED",
                "SPRAY_JSPEED", "AIRCUT_JSPEED", "PRE_SPRAY", "GUN", "CALL_DBK", "CALL_PGM", "CALL",
                "BASE", "TOOL", "HOME", "SPEED", "ACCEL", "SMOOTH_RANGE", "ACCURACY", "TWAIT",
                "TIMER_WAIT", "SWAIT", "SIGNAL", "DOUT", "IF", "GOTO", "LABEL", "UC_JUMP", "PAUSE", "RETURN"
            ),
            Manufacturer.FANUC to listOf(
                "J P[", "L P[", "CALL", "WAIT", "DO[", "DI[", "R[", "PR[", "LBL[", "JMP", "IF",
                "UFRAME_NUM", "UTOOL_NUM", "OVERRIDE"
            ),
            Manufacturer.ABB to listOf(
                "PROC", "ENDPROC", "MoveJ", "MoveL", "MoveAbsJ", "WaitTime", "SetDO", "WaitDI",
                "IF", "WHILE", "FOR", "TPWrite", "RETURN"
            ),
            Manufacturer.UNIVERSAL_ROBOTS to listOf(
                "def", "end", "movej", "movel", "movep", "sleep", "set_digital_out",
                "get_digital_in", "if", "while", "popup", "textmsg"
            )
        )
    }
}
