package my.robots.core.network

import java.io.IOException
import java.io.OutputStream

/**
 * Um arquivo da pasta de um robô.
 * - lastModified: data/hora da última alteração, em milissegundos (0 se desconhecida).
 */
data class RobotFileInfo(val name: String, val lastModified: Long)

/**
 * Acesso aos arquivos de cada robô (backups .as), sem depender de onde eles moram.
 *
 * Desde a v1.2 o app não usa mais "acesso a todos os arquivos": os arquivos ficam em
 * Documentos/MyRobots/<robô>/ (MediaStore) ou numa pasta que o usuário escolheu (SAF). As
 * implementações moram no :core:data; esta interface fica aqui para o terminal (SAVE/LOAD)
 * poder usá-la sem depender do :core:data.
 *
 * Todas as funções fazem E/S e bloqueiam: chame fora da thread principal.
 * `robotName` é o nome do robô como cadastrado; a pasta é robotDirName(robotName).
 * `fileName` precisa ser um nome simples (TransferFileNames.safeName), senão dá IOException.
 */
interface RobotFileStore {
    /** Arquivos .as da pasta do robô (vazio se a pasta ainda não existe). */
    fun list(robotName: String): List<RobotFileInfo>

    /** Conteúdo do arquivo, ou null se ele não existir (ou não puder ser lido). */
    fun read(robotName: String, fileName: String): ByteArray?

    /** Abre o arquivo para gravar do zero (cria se não existir, cria a pasta se preciso). */
    @Throws(IOException::class)
    fun openOutput(robotName: String, fileName: String): OutputStream

    /** Apaga o arquivo. Devolve true se apagou. */
    fun delete(robotName: String, fileName: String): Boolean

    companion object {
        /**
         * Nome da pasta de um robô: minúsculo, e qualquer caractere fora de letras, números
         * e "_" vira "_". Único lugar do app que define isso (o terminal e o repositório
         * usavam regras diferentes antes da v1.2).
         */
        fun robotDirName(robotName: String): String =
            robotName.lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")

        /** Confere o nome do arquivo antes de qualquer E/S. */
        @Throws(IOException::class)
        fun requireSafeName(fileName: String): String =
            TransferFileNames.safeName(fileName) ?: throw IOException("Nome de arquivo inválido: \"${fileName.take(60)}\"")
    }
}
