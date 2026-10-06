package my.robots.feature.project

import my.robots.core.common.ascode.AsMasterTransfer
import my.robots.core.common.ascode.AsProgramBlocks
import my.robots.core.data.ProjectOperations.MasterSlavePair
import my.robots.core.model.Robot

/** Um programa num backup: linhas do corpo, data de alteração e comentário do cabeçalho. */
data class ProgramState(val lines: Int, val modifiedAt: String, val comment: String)

/** Um frame (.TRANS) usado na base: a linha dele na origem e no destino (null = não existe). */
data class FrameCheck(val name: String, val origin: String?, val target: String?)

/**
 * Um programa de um par mestre -> escravo:
 * - origin / target: o programa no último backup da origem e do destino (null = não existe);
 * - baseChanges: as linhas BASE que mudam ("BASE fr_[100]" -> "BASE fr_[100]+top_offset");
 * - frames: os frames da base que vão junto (só com "Enviar a .TRANS").
 */
data class ProgramCheck(
    val name: String,
    val origin: ProgramState?,
    val target: ProgramState?,
    val baseChanges: List<Pair<String, String>>,
    val frames: List<FrameCheck>
)

/** Origem e destino de um par, para a análise antes de transferir. */
data class PairAnalysis(
    val pair: MasterSlavePair,
    val originBackupAt: Long?,
    val targetBackupAt: Long?,
    val checks: List<ProgramCheck>
) {
    /** Programas que vão (existem na origem). */
    val toSend: List<String> get() = checks.filter { it.origin != null }.map { it.name }
    /** Programas que já existem no destino e serão substituídos. */
    val replaced: List<String> get() = checks.filter { it.origin != null && it.target != null }.map { it.name }
    /** Programas que não existem na origem (não vão). */
    val missing: List<String> get() = checks.filter { it.origin == null }.map { it.name }
    /** Frames que já existem no destino com outro valor (serão sobrescritos pela .TRANS). */
    val framesOverwritten: Int get() = checks.sumOf { c -> c.frames.count { it.origin != null && it.target != null && it.origin.trim() != it.target.trim() } }
}

data class TransferAnalysis(
    val programs: List<String>,
    val pairs: List<PairAnalysis>,
    val applyOffset: Boolean,
    val withFrames: Boolean,
    val offset: String
)

/**
 * Duplicação num robô, pelo último backup dele: o programa de origem e o nome novo e, com a cópia
 * do frame, a linha do frame de origem (null = não existe), a do frame novo (null = não existe:
 * será criado) e quantas vezes o programa usa o frame de origem.
 */
data class DupCheck(
    val robot: Robot,
    val backupAt: Long?,
    val source: ProgramState?,
    val target: ProgramState?,
    val frameSource: String? = null,
    val frameTarget: String? = null,
    val frameUses: Int = 0
) {
    val canDo: Boolean get() = source != null
}

/** A duplicação escolhida; frameFrom/frameTo = null: sem cópia do frame. */
data class DuplicateAnalysis(
    val source: String,
    val newName: String,
    val comment: String?,
    val frameFrom: String?,
    val frameTo: String?,
    val checks: List<DupCheck>
)

/** Regras das análises (sem Android). */
object GroupAnalysis {

    fun programState(block: String): ProgramState {
        val lines = block.lines()
        val header = AsProgramBlocks.parseHeader(lines.firstOrNull().orEmpty())
        return ProgramState(
            // linhas do corpo: sem o cabeçalho e o .END
            lines = (lines.count { it.isNotBlank() } - 2).coerceAtLeast(0),
            modifiedAt = header?.modifiedAt.orEmpty(),
            comment = header?.comment.orEmpty()
        )
    }

    /** O programa [name] na origem e no destino, a mudança na base e os frames que vão junto. */
    fun programCheck(
        name: String,
        origin: String?,
        target: String?,
        applyOffset: Boolean,
        withFrames: Boolean,
        offset: String,
        pattern: String?
    ): ProgramCheck {
        val block = origin?.let { AsProgramBlocks.extract(it, name) }
        val targetBlock = target?.let { AsProgramBlocks.extract(it, name) }
        var changes = emptyList<Pair<String, String>>()
        var frames = emptyList<FrameCheck>()
        if (block != null) {
            if (applyOffset) {
                val after = AsMasterTransfer.applyBaseOffset(block, offset, pattern).first.lines()
                changes = block.lines().zip(after).filter { (a, b) -> a != b }.map { (a, b) -> a.trim() to b.trim() }.distinct()
            }
            if (withFrames) {
                frames = AsMasterTransfer.framesUsed(block, pattern).map { f ->
                    FrameCheck(
                        f,
                        AsMasterTransfer.transLines(origin, listOf(f)).first.firstOrNull()?.trim(),
                        target?.let { AsMasterTransfer.transLines(it, listOf(f)).first.firstOrNull()?.trim() }
                    )
                }
            }
        }
        return ProgramCheck(name, block?.let(::programState), targetBlock?.let(::programState), changes, frames)
    }

    /**
     * Próximo nome livre depois de [source] com o mesmo prefixo ("pg100" -> o primeiro pgN, N >
     * 100, que não está em [taken]). Sem número no fim: "<nome>_copia", "<nome>_copia2"...
     */
    fun nextFreeName(source: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        val m = Regex("""^(.*?)(\d+)$""").find(source)
        if (m != null) {
            val prefix = m.groupValues[1]
            var n = m.groupValues[2].toInt() + 1
            while ("$prefix$n".lowercase() in used) n++
            return "$prefix$n"
        }
        var i = 1
        while (true) {
            val name = if (i == 1) "${source}_copia" else "${source}_copia$i"
            if (name.lowercase() !in used) return name
            i++
        }
    }

    /** Nome de programa aceito pelo controlador: começa com letra, depois letras, números, _ e . (até 15). */
    fun isValidProgramName(name: String): Boolean = Regex("""^[A-Za-z][A-Za-z0-9_.]{0,14}$""").matches(name)
}
