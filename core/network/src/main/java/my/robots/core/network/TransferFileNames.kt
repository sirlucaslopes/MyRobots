package my.robots.core.network

/**
 * Valida o nome de arquivo que o CONTROLADOR manda no protocolo de transferência
 * (bloco 'B' do SAVE e bloco 'A' do LOAD).
 *
 * Esse nome vem da rede e não pode ser usado como caminho: um aparelho que responda no IP do
 * robô poderia mandar "../../data/data/my.robots/databases/robot_database" e fazer o app
 * enviar o próprio banco (com as senhas dos robôs) ou gravar fora da pasta do robô.
 * A RobotFileStore também exige um nome aprovado aqui.
 */
object TransferFileNames {

    /** Tamanho máximo aceito para o nome (o controlador usa nomes curtos). */
    const val MAX_LENGTH = 100

    private val ALLOWED = Regex("""[A-Za-z0-9_.\-]+""")

    /**
     * Devolve o nome limpo (sem espaços nas pontas) se ele for seguro, ou null se não for.
     *
     * Seguro = só letras, números, "_", "-" e ".", sem "..", sem começar com ".", e com até
     * MAX_LENGTH caracteres. Barras, caminhos absolutos e letras de unidade ficam de fora.
     */
    fun safeName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > MAX_LENGTH) return null
        if (!ALLOWED.matches(name)) return null
        if (name.startsWith(".") || name.contains("..")) return null
        return name
    }
}
