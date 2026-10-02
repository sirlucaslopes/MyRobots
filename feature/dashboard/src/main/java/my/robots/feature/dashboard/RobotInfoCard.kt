package my.robots.feature.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.common.ascode.ErrorSeverity
import my.robots.core.common.ascode.RobotHealth
import my.robots.core.common.ascode.RobotInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cores do desenho: fundo escuro de "tela de controle" e linhas ciano, iguais no tema claro e escuro. */
private val HudBackgroundTop = Color(0xFF07121C)
private val HudBackgroundBottom = Color(0xFF0E2433)
private val HudLine = Color(0xFF4DD0E1)
private val HudAccent = Color(0xFF80FFEA)
private val HudText = Color(0xFFB2EBF2)

/** Cores de status: reservadas para isso, sempre acompanhadas de ícone e texto. */
internal val StatusOk = Color(0xFF43A047)
internal val StatusAttention = Color(0xFFF9A825)
internal val StatusSerious = Color(0xFFE53935)
private val StatusNoData = Color(0xFF78909C)

internal val ptBR = Locale("pt", "BR")

/**
 * Cartão de informações do robô na home do painel.
 *
 * Em cima, uma faixa com o desenho do robô ([RobotLineArt]), o modelo, a série e o selo do
 * status geral. Tocar no selo abre a lista do que precisa de atenção ([HealthSheet]).
 * Embaixo, os números lidos do backup SAVE/FULL ([RobotInfo]). "Por eixo", abaixo das horas
 * em operação, abre o detalhe de cada servo ([AxisDetailSheet]). Um backup sem os dados do
 * controlador mostra só o desenho e um aviso de como obtê-los.
 */
@Composable
fun RobotInfoCard(
    robotName: String,
    info: RobotInfo,
    health: RobotHealth?,
    backupTimestamp: Long?,
    axisMoveHoursLast30: List<Double>,
    onOpenErrorLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showHealth by remember { mutableStateOf(false) }
    var showAxes by remember { mutableStateOf(false) }
    val hasAxisData = info.axisMoveHours.isNotEmpty() || info.encoderTemperatures.isNotEmpty()

    Card(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Brush.verticalGradient(listOf(HudBackgroundTop, HudBackgroundBottom)))
        ) {
            RobotLineArt(modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text(
                    text = info.model ?: robotName,
                    color = HudAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (info.model != null) {
                    Text(robotName, color = HudText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                info.serialNumber?.let {
                    Text("Nº $it", color = HudText.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            info.axes?.let {
                Text(
                    text = "$it EIXOS",
                    color = HudLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }

            health?.let {
                HealthChip(
                    health = it,
                    onClick = { showHealth = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                )
            }
        }

        if (info.isEmpty) {
            Text(
                "Este backup não tem os dados do controlador. Faça um SAVE/FULL no terminal para ver " +
                    "modelo, eixos, horímetro e o status do robô.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            InfoGrid(
                listOfNotNull(
                    info.hourMeterHours?.let { InfoItem("Horímetro", formatHours(it)) },
                    info.servoOnHours?.let {
                        InfoItem("Em operação (servo)", formatHours(it), if (hasAxisData) "Por eixo" else null) { showAxes = true }
                    },
                    info.motorOnCount?.let { InfoItem("Motor ligado", "${formatInt(it)} vezes") },
                    info.emergencyStopCount?.let { InfoItem("Emergências", formatInt(it)) },
                    info.brakeCount?.let { InfoItem("Freio acionado", "${formatInt(it)} vezes") },
                    info.axes?.let { InfoItem("Eixos", it.toString()) },
                    info.asVersion?.let { InfoItem("Versão AS", it) },
                    info.controllerIp?.let { InfoItem("IP do controlador", it) }
                ),
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    if (showAxes) {
        AxisDetailSheet(
            info = info,
            moveHoursLast30 = axisMoveHoursLast30,
            errorGroups = health?.errorGroups ?: emptyList(),
            onDismiss = { showAxes = false }
        )
    }

    if (showHealth && health != null) {
        HealthSheet(
            health = health,
            backupTimestamp = backupTimestamp,
            onOpenErrorLog = { showHealth = false; onOpenErrorLog() },
            onDismiss = { showHealth = false }
        )
    }
}

/**
 * Selo do status geral, tocável: verde (OK), amarelo com a quantidade de itens (atenção)
 * ou cinza (sem dados). A seta indica que abre os detalhes.
 */
@Composable
private fun HealthChip(health: RobotHealth, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (text, color) = when (health.level) {
        RobotHealth.Level.OK -> "OK" to StatusOk
        RobotHealth.Level.ATTENTION -> "ATENÇÃO · ${health.attentionCount}" to StatusAttention
        RobotHealth.Level.NO_DATA -> "SEM DADOS" to StatusNoData
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Icon(Icons.Rounded.ChevronRight, contentDescription = "Ver status", tint = color, modifier = Modifier.size(18.dp))
    }
}

/**
 * Detalhes do status geral (abre ao tocar no selo): primeiro o que precisa de atenção
 * (alarmes graves e backup antigo), depois os erros de processo e, por último, os de
 * rotina, que não mudam o status. Período: 7 dias antes do backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthSheet(
    health: RobotHealth,
    backupTimestamp: Long?,
    onOpenErrorLog: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Status do robô", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val period = backupTimestamp?.let {
                SimpleDateFormat("dd/MM/yyyy", ptBR).format(Date(it))
            }
            if (period != null) {
                Text(
                    "Alarmes dos ${RobotHealth.WINDOW_DAYS} dias antes do backup de $period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionTitle("Precisa de atenção")
            if (health.attentionCount == 0) {
                StatusRow(Icons.Rounded.CheckCircle, StatusOk, "Nada precisa de atenção.", null)
            }
            if (health.isBackupStale) {
                StatusRow(
                    Icons.Rounded.Schedule, StatusAttention,
                    "Backup de ${health.backupAgeDays} dias atrás",
                    "Faça um novo SAVE/FULL para o painel mostrar a situação atual."
                )
            }
            health.seriousGroups.forEach { g -> ErrorGroupRow(g, Icons.Rounded.Error, StatusSerious) }

            val process = health.errorGroups.filter { it.severity == ErrorSeverity.PROCESS }
            if (process.isNotEmpty()) {
                SectionTitle("Erros de programa e movimento")
                process.forEach { g -> ErrorGroupRow(g, Icons.Rounded.Info, MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            val routine = health.errorGroups.filter { it.severity == ErrorSeverity.ROUTINE }
            if (routine.isNotEmpty()) {
                SectionTitle("Rotina (não mudam o status)")
                routine.forEach { g -> ErrorGroupRow(g, null, MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            OutlinedButton(onClick = onOpenErrorLog, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir o log de erros completo")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** Uma linha do status: ícone colorido, título e detalhe (texto sempre em cores de texto). */
@Composable
private fun StatusRow(icon: ImageVector?, iconTint: Color, title: String, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorGroupRow(group: RobotHealth.ErrorGroup, icon: ImageVector?, iconTint: Color) {
    val times = if (group.count == 1) "1 vez" else "${formatInt(group.count)} vezes"
    StatusRow(
        icon, iconTint,
        if (group.code.isBlank()) "Alarme sem código no log" else "(${group.code}) ${group.message}",
        "$times · última em ${RobotHealth.formatErrorTime(group.lastTimestamp)}"
    )
}

/**
 * Um item da grade de informações. Com [action], aparece um link abaixo do valor e o item
 * inteiro fica tocável.
 */
private class InfoItem(
    val label: String,
    val value: String,
    val action: String? = null,
    val onClick: (() -> Unit)? = null
)

/** Grade de duas colunas com rótulo pequeno e valor em negrito. */
@Composable
private fun InfoGrid(items: List<InfoItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    val clickable = item.action != null && item.onClick != null
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (clickable) Modifier.clickable { item.onClick?.invoke() } else Modifier)
                    ) {
                        Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            item.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (clickable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.action!!,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    Icons.Rounded.ChevronRight, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal fun formatHours(hours: Double) = String.format(ptBR, "%,.0f h", hours)
internal fun formatInt(value: Int) = String.format(ptBR, "%,d", value)

/**
 * Desenho em linhas, estilo "tela de controle", de um robô de pintura articulado: base,
 * coluna giratória, braço inferior, braço superior, punho e pistola, com o leque de tinta
 * animado e as juntas pulsando. É só ilustração: não representa a pose real do robô.
 */
@Composable
fun RobotLineArt(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "robotLineArt")
    val pulse by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse"
    )
    val sprayPhase by transition.animateFloat(
        initialValue = 0f, targetValue = 24f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spray"
    )
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 14f)) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val floorY = h * 0.86f

        drawFloorGrid(floorY)
        drawCornerBrackets()

        // o robô fica perto do centro: o texto ocupa o canto esquerdo e o selo, o direito
        val baseX = w * 0.45f
        val s = h / 200f   // escala: o desenho foi pensado para 200 px de altura

        val shoulder = Offset(baseX, floorY - 70 * s)
        val elbow = Offset(baseX - 30 * s, floorY - 122 * s)
        val wrist = Offset(baseX + 64 * s, floorY - 104 * s)
        val gunTip = Offset(wrist.x + 26 * s, wrist.y + 22 * s)

        // base (trapézio) e coluna
        val base = Path().apply {
            moveTo(baseX - 38 * s, floorY)
            lineTo(baseX + 38 * s, floorY)
            lineTo(baseX + 26 * s, floorY - 16 * s)
            lineTo(baseX - 26 * s, floorY - 16 * s)
            close()
        }
        glowPath(base)
        val column = Path().apply {
            moveTo(baseX - 18 * s, floorY - 16 * s)
            lineTo(baseX - 14 * s, shoulder.y + 10 * s)
            lineTo(baseX + 14 * s, shoulder.y + 10 * s)
            lineTo(baseX + 18 * s, floorY - 16 * s)
        }
        glowPath(column)

        // braços: cada um com duas linhas paralelas, como uma peça vazada
        glowLimb(shoulder, elbow, 9 * s)
        glowLimb(elbow, wrist, 7 * s)
        glowLimb(wrist, gunTip, 4 * s)

        // leque de tinta saindo da pistola
        val spread = 30 * s
        val reach = 46 * s
        listOf(-1f, 0f, 1f).forEach { k ->
            drawLine(
                color = HudAccent.copy(alpha = 0.55f),
                start = gunTip,
                end = Offset(gunTip.x + reach * 0.55f + k * spread * 0.4f, gunTip.y + reach + k * spread * 0.3f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), -sprayPhase)
            )
        }

        // linha de medida tracejada do chão até o ombro
        drawLine(HudLine.copy(alpha = 0.35f), Offset(baseX + 52 * s, floorY), Offset(baseX + 52 * s, shoulder.y), 1f, pathEffect = dash)

        // juntas
        listOf(shoulder, elbow, wrist).forEach { joint ->
            drawCircle(HudAccent.copy(alpha = 0.15f * pulse), radius = 14 * s, center = joint)
            drawCircle(HudLine, radius = 7 * s, center = joint, style = Stroke(1.5f))
            drawCircle(HudAccent.copy(alpha = pulse), radius = 2.5f * s, center = joint)
        }
    }
}

/** Traço com brilho: uma linha larga e transparente por baixo e a linha fina por cima. */
private fun DrawScope.glowPath(path: Path) {
    drawPath(path, HudLine.copy(alpha = 0.18f), style = Stroke(width = 7f, cap = StrokeCap.Round))
    drawPath(path, HudLine, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
}

/** Um segmento do braço entre duas juntas, desenhado como duas linhas paralelas com brilho. */
private fun DrawScope.glowLimb(from: Offset, to: Offset, halfWidth: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / len * halfWidth
    val ny = dx / len * halfWidth
    val outline = Path().apply {
        moveTo(from.x + nx, from.y + ny)
        lineTo(to.x + nx, to.y + ny)
        moveTo(from.x - nx, from.y - ny)
        lineTo(to.x - nx, to.y - ny)
    }
    glowPath(outline)
    drawLine(HudLine.copy(alpha = 0.3f), from, to, 1f)
}

/** Chão em perspectiva: linhas horizontais cada vez mais próximas e linhas que fogem para o centro. */
private fun DrawScope.drawFloorGrid(floorY: Float) {
    val w = size.width
    val h = size.height
    var y = floorY
    var gap = 4f
    while (y < h) {
        val alpha = 0.10f + 0.25f * ((y - floorY) / (h - floorY)).coerceIn(0f, 1f)
        drawLine(HudLine.copy(alpha = alpha), Offset(0f, y), Offset(w, y), 1f)
        y += gap
        gap *= 1.6f
    }
    val vanish = Offset(w * 0.45f, floorY - h * 0.4f)
    for (i in -6..6) {
        val bottomX = w * 0.45f + i * w * 0.12f
        val t = (floorY - vanish.y) / (h - vanish.y)
        val topX = vanish.x + (bottomX - vanish.x) * t
        drawLine(HudLine.copy(alpha = 0.12f), Offset(topX, floorY), Offset(bottomX, h), 1f)
    }
    drawLine(HudLine.copy(alpha = 0.5f), Offset(0f, floorY), Offset(w, floorY), 1f)
}

/** Cantos em "L" nas bordas, como uma mira de tela de controle. */
private fun DrawScope.drawCornerBrackets() {
    val l = 16f
    val m = 8f
    val c = HudLine.copy(alpha = 0.6f)
    val w = size.width
    val h = size.height
    listOf(
        Offset(m, m) to Offset(1f, 1f),
        Offset(w - m, m) to Offset(-1f, 1f),
        Offset(m, h - m) to Offset(1f, -1f),
        Offset(w - m, h - m) to Offset(-1f, -1f)
    ).forEach { (p, d) ->
        drawLine(c, p, Offset(p.x + l * d.x, p.y), 1.5f)
        drawLine(c, p, Offset(p.x, p.y + l * d.y), 1.5f)
    }
}
