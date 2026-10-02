package my.robots.feature.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.common.ascode.RobotHealth
import my.robots.core.common.ascode.RobotInfo
import java.util.Locale

/** Cores do desenho: fundo escuro de "tela de controle" e linhas ciano, iguais no tema claro e escuro. */
private val HudBackgroundTop = Color(0xFF07121C)
private val HudBackgroundBottom = Color(0xFF0E2433)
private val HudLine = Color(0xFF4DD0E1)
private val HudAccent = Color(0xFF80FFEA)
private val HudText = Color(0xFFB2EBF2)

private val StatusOk = Color(0xFF2E7D32)
private val StatusAttention = Color(0xFFF9A825)
private val StatusNoData = Color(0xFF607D8B)

private val ptBR = Locale("pt", "BR")

/**
 * Cartão de informações do robô na home do painel.
 *
 * Em cima, o desenho do robô (ver [RobotLineArt]) com o modelo, a série e o status geral.
 * Embaixo, os números lidos do backup SAVE/FULL ([RobotInfo]) e o último erro do .ERRLOG.
 * Um backup sem os dados do controlador mostra só o desenho e um aviso de como obtê-los.
 */
@Composable
fun RobotInfoCard(
    robotName: String,
    info: RobotInfo,
    health: RobotHealth?,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Brush.verticalGradient(listOf(HudBackgroundTop, HudBackgroundBottom)))
        ) {
            RobotLineArt(modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Text(
                    text = info.model ?: robotName,
                    color = HudAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (info.model != null) {
                    Text(robotName, color = HudText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                info.serialNumber?.let {
                    Text("Nº $it", color = HudText.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            health?.let { HealthChip(it.level, Modifier.align(Alignment.TopEnd).padding(12.dp)) }

            info.axes?.let {
                Text(
                    text = "$it EIXOS",
                    color = HudLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info.isEmpty) {
                Text(
                    "Este backup não tem os dados do controlador. Faça um SAVE/FULL no terminal para ver " +
                        "modelo, eixos, horímetro e o status do robô.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                InfoGrid(
                    listOf(
                        "Horímetro" to info.hourMeterHours?.let { formatHours(it) },
                        "Servo ligado" to info.servoOnHours?.let { formatHours(it) },
                        "Motor ligado" to info.motorOnCount?.let { "${formatInt(it)} vezes" },
                        "Emergências" to info.emergencyStopCount?.let { formatInt(it) },
                        "Eixos" to info.axes?.toString(),
                        "Freio acionado" to info.brakeCount?.let { "${formatInt(it)} vezes" },
                        "Versão AS" to info.asVersion,
                        "IP do controlador" to info.controllerIp
                    ).filter { it.second != null }.map { it.first to it.second!! }
                )
            }

            health?.let { HealthSummary(it) }
        }
    }
}

/** Chip do status geral: verde (OK), amarelo (atenção) ou cinza (sem dados). */
@Composable
private fun HealthChip(level: RobotHealth.Level, modifier: Modifier = Modifier) {
    val (text, color) = when (level) {
        RobotHealth.Level.OK -> "OK" to StatusOk
        RobotHealth.Level.ATTENTION -> "ATENÇÃO" to StatusAttention
        RobotHealth.Level.NO_DATA -> "SEM DADOS" to StatusNoData
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Grade de duas colunas com rótulo pequeno e valor em negrito. */
@Composable
private fun InfoGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Linhas do status geral: erros recentes, último erro e idade do backup. */
@Composable
private fun HealthSummary(health: RobotHealth) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text("Status geral", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        val errorsText = when (health.errorsLast7Days) {
            0 -> "Nenhum erro nos 7 dias antes do backup."
            1 -> "1 erro nos 7 dias antes do backup."
            else -> "${health.errorsLast7Days} erros nos 7 dias antes do backup."
        }
        Text(errorsText, style = MaterialTheme.typography.bodySmall)
        health.mostFrequentError?.takeIf { health.errorsLast7Days > 1 }?.let { f ->
            Text(
                "Mais frequente: (${f.code}) ${f.message}  •  ${formatInt(f.count)}×",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        health.lastError?.let { e ->
            Text(
                "Último erro: (${e.errorCode}) ${e.errorMessage}  •  ${RobotHealth.formatErrorTime(e.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        val ageText = when (health.backupAgeDays) {
            0L -> "Backup de hoje."
            1L -> "Backup de ontem."
            else -> "Backup de ${health.backupAgeDays} dias atrás."
        }
        Text(
            if (health.backupAgeDays > RobotHealth.STALE_BACKUP_DAYS) "$ageText Vale fazer um novo." else ageText,
            style = MaterialTheme.typography.bodySmall,
            color = if (health.backupAgeDays > RobotHealth.STALE_BACKUP_DAYS) StatusAttention
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatHours(hours: Double) = String.format(ptBR, "%,.0f h", hours)
private fun formatInt(value: Int) = String.format(ptBR, "%,d", value)

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

        // o robô fica um pouco à direita do centro, para o texto caber à esquerda
        val baseX = w * 0.55f
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
    val vanish = Offset(w * 0.55f, floorY - h * 0.4f)
    for (i in -6..6) {
        val bottomX = w * 0.55f + i * w * 0.12f
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
