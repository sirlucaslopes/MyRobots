package my.robots.core.common.ascode

/**
 * Tipo de um parâmetro de instrução, para a tela escolher o campo certo.
 * - NUMBER: número (com unidade opcional, ex.: mm/s, %, mm, s).
 * - ON_OFF: ON ou OFF.
 * - TEXT: texto livre (nome de sinal, parâmetros de bloco...).
 */
enum class ParamKind { NUMBER, ON_OFF, TEXT }

/** Um parâmetro de instrução: chave (para levar o valor ao trocar de instrução), rótulo e tipo. */
data class InstructionParam(val key: String, val label: String, val kind: ParamKind, val unit: String = "")

/**
 * Uma instrução que o editor sabe alterar campo a campo, como o "CHANGE" do teach pendant.
 *
 * - group: grupo do pendant (Manual de Operação, 5.3): instruções do mesmo grupo podem ser
 *   trocadas entre si mantendo os valores (ex.: SPRAY_SPEED -> AIRCUT_SPEED).
 * - pattern: reconhece a linha (sem recuo e sem comentário); cada grupo capturado é um
 *   parâmetro, na ordem de [params].
 * - format: monta a linha a partir dos valores, no mesmo formato que o controlador grava.
 */
class InstructionDef(
    val keyword: String,
    val label: String,
    val group: String,
    val params: List<InstructionParam>,
    private val pattern: Regex,
    private val format: (List<String>) -> String
) {
    fun match(code: String): List<String>? = pattern.matchEntire(code)?.groupValues?.drop(1)
    fun render(values: List<String>): String = format(values.map { it.trim() })
    /** Valores padrão para uma instrução nova. */
    fun defaults(): List<String> = params.map {
        when (it.kind) {
            ParamKind.ON_OFF -> "ON"
            ParamKind.NUMBER -> "0"
            ParamKind.TEXT -> ""
        }
    }
}

/**
 * Uma linha de programa reconhecida: a instrução, os valores e o que fica em volta (recuo e
 * comentário depois do ";"), para a linha ser regravada igual, só com os valores novos.
 */
data class ParsedInstruction(
    val def: InstructionDef,
    val values: List<String>,
    val indent: String,
    val comment: String
) {
    fun render(newDef: InstructionDef = def, newValues: List<String> = values): String =
        indent + newDef.render(newValues) + comment
}

/**
 * Catálogo das instruções de pintura do controlador E (Manual de Operação à Prova de Explosão,
 * 5.3), com o formato de texto que aparece nos backups (ex.: "SPRAY #1,ON",
 * "SPRAY_SPEED 500mm/s", "SMOOTH_RANGE 20mm"). Só entram formatos confirmados em backup real;
 * o resto do programa é editado como texto.
 */
object AsInstructions {
    private const val NUM = """(-?[\d.]+)"""
    private fun num(key: String, label: String, unit: String = "") = InstructionParam(key, label, ParamKind.NUMBER, unit)
    private val ONOFF = InstructionParam("onoff", "Estado", ParamKind.ON_OFF)
    private val XYZ_LABELS = listOf("X", "Y", "Z", "O", "A", "T", "JT7", "Ext 1", "Ext 2")

    private fun single(keyword: String, label: String, group: String, param: InstructionParam, unitInText: String = "") =
        InstructionDef(
            keyword, label, group, listOf(param),
            Regex("""${keyword}\s+$NUM\s*${Regex.escape(unitInText)}""", RegexOption.IGNORE_CASE)
        ) { v -> "$keyword ${v[0]}$unitInText" }

    /** LMOVE XYZ1 / JMOVE JOINT: bloco ensinado no pendant, "código" + coordenadas separadas por vírgula. */
    private fun move(keyword: String, kind: String, label: String, group: String, axisLabels: List<String>): InstructionDef {
        val params = listOf(InstructionParam("block", "Parâmetros do bloco", ParamKind.TEXT)) +
            axisLabels.mapIndexed { i, l -> num("c$i", l) }
        val body = (listOf("""(\S+?)""") + List(axisLabels.size) { NUM }).joinToString("""\s*,\s*""")
        return InstructionDef(
            keyword, label, group, params,
            Regex("""$keyword\s+$kind\s+$body""", RegexOption.IGNORE_CASE)
        ) { v -> "$keyword $kind " + v.joinToString(",") }
    }

    val all: List<InstructionDef> = listOf(
        // 5.3.1 Velocidade
        single("SPRAY_SPEED", "Velocidade de pulverização", "Velocidade", num("speed", "Velocidade", "mm/s"), "mm/s"),
        single("AIRCUT_SPEED", "Velocidade de corte a ar", "Velocidade", num("speed", "Velocidade", "mm/s"), "mm/s"),
        single("SPRAY_JSPEED", "Velocidade de junta pulverizando", "Velocidade", num("jspeed", "Velocidade", "%"), "%"),
        single("AIRCUT_JSPEED", "Velocidade de junta em corte a ar", "Velocidade", num("jspeed", "Velocidade", "%"), "%"),
        // 5.3.2 Pulverização
        InstructionDef(
            "SPRAY", "Pulverização", "Pulverização",
            listOf(num("spray", "Pulverizador nº"), ONOFF),
            Regex("""SPRAY\s+#(\d+)\s*,\s*(ON|OFF)""", RegexOption.IGNORE_CASE)
        ) { v -> "SPRAY #${v[0]},${v[1].uppercase()}" },
        InstructionDef(
            "PRE_SPRAY", "Adianto da pulverização (distância)", "Pulverização",
            listOf(num("spray", "Pulverizador nº"), num("dist", "Distância de adianto", "mm"), ONOFF),
            Regex("""PRE_SPRAY\s+#(\d+)\s*,\s*$NUM\s*mm\s*,\s*(ON|OFF)""", RegexOption.IGNORE_CASE)
        ) { v -> "PRE_SPRAY #${v[0]},${v[1]}mm,${v[2].uppercase()}" },
        // 5.3.3 Saídas
        InstructionDef(
            "DOUT", "Saída digital", "Saída",
            listOf(num("out", "Saída nº"), ONOFF),
            Regex("""DOUT\s+#(\d+)\s*,\s*(ON|OFF)""", RegexOption.IGNORE_CASE)
        ) { v -> "DOUT #${v[0]},${v[1].uppercase()}" },
        // 5.3.5 Movimento suave
        single("ACCEL", "Aceleração", "Movimento suave", num("accel", "Aceleração", "%"), "%"),
        single("SMOOTH_RANGE", "Faixa de movimento suave", "Movimento suave", num("range", "Faixa", "mm"), "mm"),
        // 5.3.6 Banco de dados
        single("CALL_DBK", "Chamar Data Bank", "Chamada", num("n", "Data Bank nº")),
        // 5.3.8 Chamar programa
        single("CALL_PGM", "Chamar programa", "Chamada", num("n", "Programa nº")),
        // 5.3.7 Temporizador
        // TWAIT aceita número ou variável (ex.: "TWAIT spray_time")
        InstructionDef(
            "TWAIT", "Esperar (segundos)", "Temporizador",
            listOf(InstructionParam("t", "Tempo (s) ou variável", ParamKind.TEXT)),
            Regex("""TWAIT\s+(\S+)""", RegexOption.IGNORE_CASE)
        ) { v -> "TWAIT ${v[0]}" },
        single("TIMER_WAIT", "Temporizador", "Temporizador", num("t", "Tempo", "s"), "sec"),
        // 5.3.9 Salto
        InstructionDef(
            "UC_JUMP", "Salto incondicional", "Salto",
            listOf(num("label", "Etiqueta")),
            Regex("""UC_JUMP\s+LABEL\s+(\d+)""", RegexOption.IGNORE_CASE)
        ) { v -> "UC_JUMP LABEL ${v[0]}" },
        single("LABEL", "Definir etiqueta", "Salto", num("label", "Etiqueta")),
        // 5.3.11 Pistola
        single("GUN", "Selecionar pistola", "Pistola", num("gun", "Pistola nº")),
        // 5.3.14 Movimento
        // XYZ1 e XYZ2 são os dois tipos de interpolação linear do pendant (trocam entre si)
        move("LMOVE", "XYZ1", "Linear XYZ1", "Movimento linear", XYZ_LABELS),
        move("LMOVE", "XYZ2", "Linear XYZ2", "Movimento linear", XYZ_LABELS),
        move("JMOVE", "JOINT", "Movimento de juntas (JOINT)", "Movimento de juntas",
            listOf("JT1", "JT2", "JT3", "JT4", "JT5", "JT6", "JT7", "Ext 1", "Ext 2"))
    )

    /** Grupos na ordem do pendant. */
    val groups: List<String> = all.map { it.group }.distinct()

    /** Instruções de um grupo. */
    fun inGroup(group: String) = all.filter { it.group == group }

    /**
     * Reconhece a linha. Guarda o recuo e o comentário (";..."), que voltam iguais ao regravar.
     * Devolve null se a linha não for uma instrução do catálogo (ou tiver outro formato).
     */
    fun parse(line: String): ParsedInstruction? {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val semicolon = rest.indexOf(';')
        val code = (if (semicolon >= 0) rest.substring(0, semicolon) else rest).trimEnd()
        val comment = if (semicolon >= 0) rest.substring(code.length) else ""
        if (code.isEmpty()) return null
        for (def in all) {
            val values = def.match(code) ?: continue
            return ParsedInstruction(def, values, indent, comment)
        }
        return null
    }

    /**
     * Valores para trocar de instrução dentro do grupo: leva os valores com a mesma chave e
     * usa o padrão nos que não existem na instrução de origem.
     */
    fun carryValues(from: ParsedInstruction, to: InstructionDef): List<String> {
        val byKey = from.def.params.map { it.key }.zip(from.values).toMap()
        return to.params.mapIndexed { i, p -> byKey[p.key] ?: to.defaults()[i] }
    }
}
