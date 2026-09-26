package my.robots.core.common.ascode

/*
 * Leitura dos logs do controlador que vêm no backup SAVE/FULL: .ERRLOG, .OPELOG e
 * .PGM_EDT_LOG. Código movido sem mudança do RobotDashboardViewModel (v1.1) para cá, para
 * ter testes JVM e poder ser usado por outras telas.
 */

/**
 * Ponto de entrada dos parsers de log. As funções não mexem no texto do backup.
 */
object AsControllerLogs {
    /**
     * Lê uma seção de log genérica (".OPELOG" ou ".PGM_EDT_LOG"). Ver parseLogSectionImpl.
     */
    fun parseLogSection(lines: List<String>, sectionMarker: String): List<RobotLogEntry> =
        parseLogSectionImpl(lines, sectionMarker)

    /**
     * Lê a seção ".ERRLOG" já separada em campos. Ver parseErrorLogImpl.
     */
    fun parseErrorLog(lines: List<String>): List<RobotErrorLogEntry> = parseErrorLogImpl(lines)
}

/**
 * Uma entrada de um dos logs do controlador (.ERRLOG, .OPELOG ou .PGM_EDT_LOG).
 * - index: número da entrada, como escrito no log (a numeração pode ter buracos).
 * - timestamp: o que está entre colchetes na primeira linha (data/hora e, no ERRLOG,
 *   também sinal/velocidade/modo).
 * - raw: o texto completo da entrada, como está no backup (pode ter várias linhas —
 *   é o caso do ERRLOG, que traz o estado do robô e as poses no momento do erro).
 */
data class RobotLogEntry(
    val index: String,
    val timestamp: String,
    val raw: String
)

/** Reconhece o início de uma entrada de log: "N - [qualquer coisa]". */
private val LOG_ENTRY_HEADER_REGEX = Regex("""^(\d+)\s*-\s*\[([^]]*)]""")

/**
 * Lê uma seção de log do backup (ERRLOG, OPELOG ou PGM_EDT_LOG).
 *
 * Essas seções só existem quando o backup foi feito com SAVE/FULL no robô — se a seção não
 * estiver no arquivo, devolve uma lista vazia (é o "zerado" esperado nesse caso). Cada
 * entrada começa com "N - [...]" e pode ter linhas de detalhe embaixo (como no ERRLOG). A
 * seção termina na próxima linha que começa com "." (uma nova seção do backup) ou no fim
 * do arquivo — essas seções não têm ".END" próprio.
 */
internal fun parseLogSectionImpl(lines: List<String>, sectionMarker: String): List<RobotLogEntry> {
    val startIndex = lines.indexOfFirst { it.trim().equals(sectionMarker, ignoreCase = true) }
    if (startIndex == -1) return emptyList()

    val entries = mutableListOf<RobotLogEntry>()
    var currentIndex: String? = null
    var currentTimestamp = ""
    val currentBlock = StringBuilder()

    fun flush() {
        val idx = currentIndex ?: return
        entries.add(RobotLogEntry(index = idx, timestamp = currentTimestamp, raw = currentBlock.toString().trim()))
    }

    for (i in (startIndex + 1) until lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith(".")) break

        val header = LOG_ENTRY_HEADER_REGEX.find(trimmed)
        if (header != null) {
            flush()
            currentIndex = header.groupValues[1]
            currentTimestamp = header.groupValues[2].trim()
            currentBlock.clear()
            currentBlock.append(trimmed)
        } else if (currentIndex != null && trimmed.isNotEmpty()) {
            currentBlock.append("\n").append(trimmed)
        }
    }
    flush()
    return entries
}

/**
 * Uma operação (mudança de estado) registrada dentro de uma entrada do .ERRLOG, ex.:
 * "OPERATION1:[26/07/12 08:15:26] ( EMERGENCY STOP )".
 */
data class RobotErrorLogOperation(
    val label: String,
    val timestamp: String,
    val description: String
)

/**
 * O programa/PC que estava rodando no momento do erro, ex.:
 * "ROBOT1: PROGRAM:pg9996 Step:0 Cur_Step:30 STATUS:STOP" ou
 * "PC1 PROGRAM: pc2_main Step No: 16 STATUS: STOP".
 */
data class RobotErrorLogProgram(
    val place: String,
    val program: String,
    val step: String,
    val status: String
)

/**
 * Uma entrada do .ERRLOG, já separada em campos (ao contrário do RobotLogEntry genérico,
 * usado pelo OPELOG/PGM_EDT_LOG, que só guarda o texto cru). `raw` continua disponível como
 * texto original completo, para o caso de algum formato de erro não bater com o parser.
 */
data class RobotErrorLogEntry(
    val index: String,
    val timestamp: String,
    val signal: String,
    val speed: String,
    val mode: String,
    val errorCode: String,
    val errorMessage: String,
    val operations: List<RobotErrorLogOperation>,
    val programs: List<RobotErrorLogProgram>,
    val currentPose: List<String>,
    val commandPose: List<String>,
    val endPose: List<String>,
    val raw: String
)

private val ERRLOG_HEADER_REGEX = Regex(
    """^\d+\s*-\s*\[(\S+)\s+(\S+)\s+SIGNAL:(\S+)\s+MON\.SPEED\s*:\s*(\S+)\s+([^]]*)]""",
    RegexOption.IGNORE_CASE
)
private val ERROR_CODE_REGEX = Regex("""^\(([A-Za-z0-9]+)\)\s*(.*)$""")
private val OPERATION_LINE_REGEX = Regex("""^OPERATION(\d+):\[([^]]*)]\s*\(\s*(.*?)\s*\)\s*$""", RegexOption.IGNORE_CASE)
private val PC_PROGRAM_LINE_REGEX = Regex("""^(\S+)\s+PROGRAM:\s*(\S+)\s+Step No:\s*(\S+)\s+STATUS:\s*(\S+)$""", RegexOption.IGNORE_CASE)
private val ROBOT_PROGRAM_LINE_REGEX = Regex("""^PROGRAM:(\S+)\s+Step:(\S+)\s+Cur_Step:(\S+)\s+STATUS:(\S+)$""", RegexOption.IGNORE_CASE)
private val PLACE_HEADER_REGEX = Regex("""^(\w+):$""")
private val POSE_HEADER_REGEX = Regex("""^(Current|Command|End)\s+Pose$""", RegexOption.IGNORE_CASE)

/**
 * Lê a seção .ERRLOG do backup e devolve cada entrada já separada em campos (código/mensagem
 * do erro, sinal/velocidade/modo, operações, programas em execução e as poses). Só existe em
 * backup SAVE/FULL; se a seção não estiver no arquivo, devolve lista vazia.
 */
internal fun parseErrorLogImpl(lines: List<String>): List<RobotErrorLogEntry> {
    val startIndex = lines.indexOfFirst { it.trim().equals(".ERRLOG", ignoreCase = true) }
    if (startIndex == -1) return emptyList()

    val entries = mutableListOf<RobotErrorLogEntry>()
    var headerLine: String? = null
    var bodyLines = mutableListOf<String>()

    fun flush() {
        val header = headerLine ?: return
        entries.add(buildErrorLogEntry(header, bodyLines))
    }

    for (i in (startIndex + 1) until lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith(".")) break

        if (LOG_ENTRY_HEADER_REGEX.find(trimmed) != null) {
            flush()
            headerLine = trimmed
            bodyLines = mutableListOf()
        } else if (headerLine != null && trimmed.isNotEmpty()) {
            bodyLines.add(trimmed)
        }
    }
    flush()
    return entries
}

/**
 * Monta uma entrada do .ERRLOG a partir da linha de cabeçalho ("N - [...]") e das linhas de
 * detalhe embaixo dela. Cada tipo de linha (código do erro, OPERATIONx, programa/PC, pose)
 * é reconhecido pelo seu próprio formato; o que não bate com nenhum é só ignorado nos campos
 * estruturados (mas continua disponível em `raw`).
 */
private fun buildErrorLogEntry(headerLine: String, bodyLines: List<String>): RobotErrorLogEntry {
    val index = LOG_ENTRY_HEADER_REGEX.find(headerLine)?.groupValues?.getOrNull(1).orEmpty()
    val header = ERRLOG_HEADER_REGEX.find(headerLine)
    val date = header?.groupValues?.getOrNull(1).orEmpty()
    val time = header?.groupValues?.getOrNull(2).orEmpty()
    val signal = header?.groupValues?.getOrNull(3).orEmpty()
    val speed = header?.groupValues?.getOrNull(4).orEmpty()
    val mode = header?.groupValues?.getOrNull(5)?.trim().orEmpty()

    var errorCode = ""
    var errorMessage = ""
    val operations = mutableListOf<RobotErrorLogOperation>()
    val programs = mutableListOf<RobotErrorLogProgram>()
    var currentPose: List<String> = emptyList()
    var commandPose: List<String> = emptyList()
    var endPose: List<String> = emptyList()
    var pendingPlace: String? = null

    var i = 0
    while (i < bodyLines.size) {
        val line = bodyLines[i]
        val codeMatch = if (errorCode.isEmpty()) ERROR_CODE_REGEX.find(line) else null
        val opMatch = OPERATION_LINE_REGEX.find(line)
        val pcMatch = PC_PROGRAM_LINE_REGEX.find(line)
        val robotDetailMatch = ROBOT_PROGRAM_LINE_REGEX.find(line)
        val placeMatch = PLACE_HEADER_REGEX.find(line)
        val poseMatch = POSE_HEADER_REGEX.find(line)

        when {
            codeMatch != null -> {
                errorCode = codeMatch.groupValues[1]
                errorMessage = codeMatch.groupValues[2].trim()
            }
            opMatch != null -> {
                operations.add(
                    RobotErrorLogOperation(
                        label = "OPERATION${opMatch.groupValues[1]}",
                        timestamp = opMatch.groupValues[2].trim(),
                        description = opMatch.groupValues[3].trim()
                    )
                )
            }
            pcMatch != null -> {
                programs.add(
                    RobotErrorLogProgram(
                        place = pcMatch.groupValues[1],
                        program = pcMatch.groupValues[2],
                        step = pcMatch.groupValues[3],
                        status = pcMatch.groupValues[4]
                    )
                )
            }
            robotDetailMatch != null && pendingPlace != null -> {
                programs.add(
                    RobotErrorLogProgram(
                        place = pendingPlace!!,
                        program = robotDetailMatch.groupValues[1],
                        // Cur_Step é o passo de verdade que estava rodando; Step costuma ficar em 0.
                        step = robotDetailMatch.groupValues[3],
                        status = robotDetailMatch.groupValues[4]
                    )
                )
                pendingPlace = null
            }
            placeMatch != null -> {
                pendingPlace = placeMatch.groupValues[1]
            }
            poseMatch != null -> {
                val poseType = poseMatch.groupValues[1].lowercase()
                var cursor = i + 1
                if (cursor < bodyLines.size && bodyLines[cursor].contains("JT1", ignoreCase = true)) cursor++
                var values: List<String> = emptyList()
                if (cursor < bodyLines.size) {
                    val candidate = bodyLines[cursor]
                    if (POSE_HEADER_REGEX.find(candidate) == null && candidate.any { it.isDigit() }) {
                        values = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
                        cursor++
                    }
                }
                when (poseType) {
                    "current" -> currentPose = values
                    "command" -> commandPose = values
                    "end" -> endPose = values
                }
                i = cursor - 1
            }
        }
        i++
    }

    return RobotErrorLogEntry(
        index = index,
        timestamp = if (date.isNotEmpty() && time.isNotEmpty()) "$date $time" else headerLine,
        signal = signal,
        speed = speed,
        mode = mode,
        errorCode = errorCode,
        errorMessage = errorMessage,
        operations = operations,
        programs = programs,
        currentPose = currentPose,
        commandPose = commandPose,
        endPose = endPose,
        raw = (listOf(headerLine) + bodyLines).joinToString("\n")
    )
}
