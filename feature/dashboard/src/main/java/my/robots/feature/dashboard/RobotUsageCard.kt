package my.robots.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.common.ascode.DailyUsage
import my.robots.core.common.ascode.UsageCsv
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Períodos do gráfico de uso, em dias (null = todo o histórico). */
private val PERIODS = listOf(30 to "30 dias", 90 to "90 dias", null to "Tudo")

private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")
private val FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Cartão "Uso do robô": horas em operação (servo ligado) por dia, montadas a partir dos
 * contadores de todos os backups SAVE/FULL ([DailyUsage]).
 *
 * - Em cima, as médias do período: horas em operação por dia, horas ligado por dia e
 *   quantas vezes o motor foi ligado.
 * - O gráfico tem uma medida só (horas em operação); tocar numa barra mostra o dia com os
 *   três números. Barras apagadas são dias entre backups distantes (média do intervalo).
 * - Com menos de dois backups SAVE/FULL não há o que comparar: o cartão explica isso.
 * - "Exportar (Excel)" gera um CSV com todos os dias ([UsageCsv]) e abre o compartilhar.
 */
@Composable
fun RobotUsageCard(days: List<DailyUsage>, robotName: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var periodIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember(days, periodIndex) { mutableStateOf<Int?>(null) }

    val period = PERIODS[periodIndex].first
    val shown = remember(days, period) { if (period == null) days else days.takeLast(period) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Uso do robô",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // planilha com todos os dias (não só o período escolhido), para abrir no Excel
                if (days.isNotEmpty()) {
                    TextButton(onClick = {
                        shareTextFile(
                            context, UsageCsv.fileName(robotName), UsageCsv.build(robotName, days),
                            "Exportar uso do robô", mimeType = "text/csv"
                        )
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Exportar (Excel)")
                    }
                }
            }

            if (days.isEmpty()) {
                Text(
                    "O gráfico aparece com dois ou mais backups SAVE/FULL deste robô: ele compara o " +
                        "horímetro e as horas em operação entre um backup e outro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEachIndexed { i, (_, label) ->
                    FilterChip(selected = i == periodIndex, onClick = { periodIndex = i }, label = { Text(label) })
                }
            }

            val n = shown.size.coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageStat("Em operação", String.format(ptBR, "%.1f h/dia", shown.sumOf { it.operatingHours } / n), Modifier.weight(1f))
                UsageStat("Ligado", String.format(ptBR, "%.1f h/dia", shown.sumOf { it.poweredHours } / n), Modifier.weight(1f))
                UsageStat("Motor ligado", "${formatInt(shown.sumOf { it.motorOnCount }.toInt())} vezes", Modifier.weight(1f))
            }

            // linha de detalhe: o dia tocado, ou uma dica
            val detail = selected?.let { shown.getOrNull(it) }
            Text(
                text = if (detail != null) {
                    String.format(
                        ptBR, "%s · %.1f h em operação · %.1f h ligado · %d vezes motor ligado%s",
                        detail.date.format(FULL_DATE), detail.operatingHours, detail.poweredHours,
                        detail.motorOnCount.toInt(), if (detail.estimated) " (média)" else ""
                    )
                } else "Horas em operação por dia. Toque numa barra para ver o dia.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (detail != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (detail != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )

            UsageBarChart(
                days = shown,
                selected = selected,
                onSelect = { selected = if (selected == it) null else it },
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )

            if (shown.any { it.estimated }) {
                Text(
                    "Barras apagadas: dias entre backups com mais de 2 dias de intervalo (valor é a média do intervalo).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsageStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Barras de horas em operação por dia. Eixo y em horas com linhas de grade discretas;
 * no eixo x, a primeira, a do meio e a última data. Barra tocada fica destacada.
 */
@Composable
private fun UsageBarChart(
    days: List<DailyUsage>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelPx = with(density) { 11.dp.toPx() }
    val axisWidth = with(density) { 30.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }
    val corner = with(density) { 3.dp.toPx() }

    // escala: o maior valor arredondado para cima num passo "redondo" (1, 2, 5 h...)
    val maxValue = days.maxOfOrNull { it.operatingHours } ?: 0.0
    val step = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0).firstOrNull { maxValue / it <= 4 } ?: 50.0
    val top = (ceil(maxValue / step) * step).coerceAtLeast(step)

    val paint = remember(labelColor, labelPx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = labelPx
            color = android.graphics.Color.argb(
                (labelColor.alpha * 255).toInt(), (labelColor.red * 255).toInt(),
                (labelColor.green * 255).toInt(), (labelColor.blue * 255).toInt()
            )
        }
    }

    Canvas(
        modifier = modifier.pointerInput(days) {
            detectTapGestures { pos ->
                val plotW = size.width - axisWidth
                if (days.isEmpty() || pos.x < axisWidth) return@detectTapGestures
                val i = ((pos.x - axisWidth) / (plotW / days.size)).toInt().coerceIn(0, days.lastIndex)
                onSelect(i)
            }
        }
    ) {
        val plotW = size.width - axisWidth
        val plotH = size.height - bottomPad

        // grade e rótulos do eixo y
        var v = 0.0
        while (v <= top + 1e-9) {
            val y = plotH - (v / top * plotH).toFloat()
            drawLine(gridColor, Offset(axisWidth, y), Offset(size.width, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                if (step < 1) String.format(ptBR, "%.1f h", v) else "${v.toInt()} h",
                0f, y + labelPx / 3, paint
            )
            v += step
        }

        if (days.isEmpty()) return@Canvas
        val slot = plotW / days.size
        val gap = (slot * 0.25f).coerceAtMost(2.dp.toPx())
        val barW = (slot - gap).coerceAtLeast(1f)

        days.forEachIndexed { i, d ->
            val h = (d.operatingHours / top * plotH).toFloat()
            if (h <= 0f) return@forEachIndexed
            val x = axisWidth + i * slot + gap / 2
            val alpha = when {
                selected != null && selected != i -> 0.35f
                d.estimated -> 0.45f
                else -> 1f
            }
            val r = corner.coerceAtMost(barW / 2)
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = x, top = plotH - h, right = x + barW, bottom = plotH,
                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                        bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero
                    )
                )
            }
            drawPath(path, barColor.copy(alpha = alpha))
        }

        // datas no eixo x: primeira, do meio e última
        val labelY = size.height - 2f
        listOf(0, days.lastIndex / 2, days.lastIndex).distinct().forEach { i ->
            val text = days[i].date.format(DAY_MONTH)
            val w = paint.measureText(text)
            val cx = axisWidth + i * slot + slot / 2
            val x = (cx - w / 2).coerceIn(axisWidth, size.width - w)
            drawContext.canvas.nativeCanvas.drawText(text, x, labelY, paint)
        }
    }
}
