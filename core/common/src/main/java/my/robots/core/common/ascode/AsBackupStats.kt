package my.robots.core.common.ascode

import my.robots.core.common.FileUtil

/**
 * Contagens de um backup AS.
 */
data class BackupCounts(val programs: Int, val variables: Int)

/**
 * Conta programas e variáveis de um backup. Código movido sem mudança do
 * RobotRepository.calculateAndApplyMetadata (v1.1) para ter testes JVM.
 */
object AsBackupStats {
    /**
     * Lê o texto do backup e conta quantos programas e variáveis ele tem. O texto NUNCA é
     * alterado — a contagem só ignora, na leitura, seções que o controlador às vezes anexa e
     * que não são do idioma AS (ex.: despejos de diagnóstico do sistema, sem ".END" e com
     * milhares de linhas). Ver FileUtil.sanitizeAsContent.
     *
     * - Programa: cada linha que começa com ".PROGRAM".
     * - Variável: cada linha com "=" (ou 3+ valores separados por vírgula) dentro
     *   de uma seção .TRANS, .REALS, .STRINGS, .JOINT ou .POINT.
     * - Linhas em branco e comentários (começam com ";") são ignorados.
     */
    fun count(content: String): BackupCounts {
        if (content.isBlank()) return BackupCounts(0, 0)

        var programs = 0
        var variables = 0

        var inVariableSection = false

        FileUtil.sanitizeAsContent(content).lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(";")) {
                if (trimmed.startsWith(".PROGRAM", ignoreCase = true)) {
                    programs++
                    inVariableSection = false
                } else {
                    val isVarHeader = trimmed.startsWith(".TRANS", ignoreCase = true) ||
                                     trimmed.startsWith(".REAL", ignoreCase = true) ||
                                     trimmed.startsWith(".STRING", ignoreCase = true) ||
                                     trimmed.startsWith(".JOINT", ignoreCase = true) ||
                                     trimmed.startsWith(".POINT", ignoreCase = true)

                    if (isVarHeader) {
                        inVariableSection = true
                    } else if (inVariableSection) {
                        if (trimmed.startsWith(".")) {
                            if (trimmed.startsWith(".END", ignoreCase = true)) {
                                inVariableSection = false
                            }
                        } else if (trimmed.contains("=") || trimmed.split(",").size >= 3) {
                            variables++
                        }
                    }
                }
            }
        }

        return BackupCounts(programs = programs, variables = variables)
    }
}
