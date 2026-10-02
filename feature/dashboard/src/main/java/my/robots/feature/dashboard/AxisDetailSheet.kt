package my.robots.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.robots.core.common.ascode.AsRobotInfo
import my.robots.core.common.ascode.ErrorSeverity
import my.robots.core.common.ascode.RobotHealth
import my.robots.core.common.ascode.RobotInfo

/**
 * Detalhe de cada servo (eixo), aberto pelo "Por eixo" das horas em operação.
 *
 * Para cada eixo (JT1, JT2...):
 * - horas em movimento (MOVE_TJT) com uma barra proporcional ao eixo mais usado, e quanto
 *   ele andou nos últimos 30 dias, quando há backups suficientes;
 * - deslocamento acumulado (DIST_DJT), na unidade do controlador;
 * - menor e maior temperatura do encoder (.ENCTEMPLOG), com a data;
 * - alarmes dos 7 dias antes do backup que citam o eixo ("Jt 5 motor overloaded"). Os de
 *   rotina ficam de fora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AxisDetailSheet(
    info: RobotInfo,
    moveHoursLast30: List<Double>,
    errorGroups: List<RobotHealth.ErrorGroup>,
    onDismiss: () -> Unit
) {
    val axisCount = maxOf(info.axes ?: 0, info.axisMoveHours.size, info.encoderTemperatures.maxOfOrNull { it.axis } ?: 0)
    val maxHours = info.axisMoveHours.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val alarmsByAxis = errorGroups
        .filter { it.severity != ErrorSeverity.ROUTINE }
        .mapNotNull { g -> AsRobotInfo.axisOf(g.message)?.let { it to g } }
        .groupBy({ it.first }, { it.second })

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
            Text("Servos por eixo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Dados do backup analisado. As temperaturas são a menor e a maior já registradas pelo controlador.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            (1..axisCount).forEach { axis ->
                AxisRow(
                    axis = axis,
                    moveHours = info.axisMoveHours.getOrNull(axis - 1),
                    maxHours = maxHours,
                    last30 = moveHoursLast30.getOrNull(axis - 1),
                    distance = info.axisDistance.getOrNull(axis - 1),
                    temperature = info.encoderTemperatures.firstOrNull { it.axis == axis },
                    alarms = alarmsByAxis[axis].orEmpty()
                )
            }
        }
    }
}

@Composable
private fun AxisRow(
    axis: Int,
    moveHours: Double?,
    maxHours: Double,
    last30: Double?,
    distance: Double?,
    temperature: my.robots.core.common.ascode.EncoderTemperature?,
    alarms: List<RobotHealth.ErrorGroup>
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("JT$axis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (moveHours != null) {
                    Text(
                        String.format(ptBR, "%.1f h em movimento", moveHours),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (moveHours != null) {
                // barra proporcional ao eixo que mais se moveu
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((moveHours / maxHours).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            val details = buildList {
                last30?.let { add(String.format(ptBR, "+%.1f h nos últimos 30 dias", it)) }
                distance?.let { add(String.format(ptBR, "Deslocamento acumulado: %,.1f", it)) }
                temperature?.let { t ->
                    val min = t.minCelsius?.let { c ->
                        String.format(ptBR, "mín %.1f °C", c) + (t.minAt?.let { " (${RobotHealth.formatErrorTime(it).take(10)})" } ?: "")
                    }
                    val max = t.maxCelsius?.let { c ->
                        String.format(ptBR, "máx %.1f °C", c) + (t.maxAt?.let { " (${RobotHealth.formatErrorTime(it).take(10)})" } ?: "")
                    }
                    add("Encoder: " + listOfNotNull(min, max).joinToString(" · "))
                }
            }
            details.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            alarms.forEach { g ->
                Row(verticalAlignment = Alignment.Top) {
                    val serious = g.severity == ErrorSeverity.SERIOUS
                    Icon(
                        if (serious) Icons.Rounded.Error else Icons.Rounded.Info,
                        contentDescription = if (serious) "Alarme grave" else "Alarme",
                        tint = if (serious) StatusSerious else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val times = if (g.count == 1) "1 vez" else "${formatInt(g.count)} vezes"
                    Text(
                        "(${g.code}) ${g.message} · $times",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (serious) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
