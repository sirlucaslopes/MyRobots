package my.robots.feature.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.robots.core.data.hierarchy.ClientNode
import my.robots.core.data.hierarchy.ClientTree
import my.robots.core.data.hierarchy.LineNode
import my.robots.core.data.hierarchy.StationLink
import my.robots.core.data.hierarchy.StationNode
import my.robots.core.designsystem.FormDialog
import my.robots.core.model.HeartbeatState
import my.robots.core.model.Robot

/** Azul das ligações dentro da linha e amarelo das ligações com outra linha. */
internal val LinkBlue = Color(0xFF42A5F5)
internal val CrossLineYellow = Color(0xFFFFB300)

/** Cor do status do robô, a mesma do LED (HeartbeatDot) do resto do app. */
internal fun stateColor(state: HeartbeatState): Color = when (state) {
    HeartbeatState.ALIVE -> Color(0xFF4CAF50)
    HeartbeatState.STALE -> Color(0xFFFFA000)
    HeartbeatState.DISCONNECTED -> Color(0xFF9E9E9E)
}

/** Sem as estações ocultas dentro das linhas (as linhas ocultas continuam, para quem as lista à parte). */
internal fun ClientNode.withoutHiddenStations(): ClientNode =
    copy(lines = lines.map { l -> l.copy(stations = l.stations.filter { !it.layout.hidden }) })

/** Sem linhas e estações ocultas: o que aparece no cartão de um cliente oculto. */
internal fun ClientNode.withoutHiddenInside(): ClientNode = withoutHiddenStations().copy(lines = lines.filter { !it.line.hidden }.map { l ->
    l.copy(stations = l.stations.filter { !it.layout.hidden })
})

/** "X de Y conectados". */
internal fun connectedText(robots: List<Robot>, ui: ClientsUi): String =
    "${ClientTree.connectedCount(robots, ui::state)} de ${robots.size} conectados"

/**
 * A cabine da estação em miniatura: um quadrado por vaga da grade, pintado na cor do status do
 * robô que está nela; os robôs fora do layout vêm numa linha a mais, embaixo.
 */
@Composable
internal fun StationMiniGrid(station: StationNode, ui: ClientsUi, cell: Dp = 10.dp) {
    val rows = station.layout.rowCount.coerceAtLeast(1)
    val cols = station.layout.colCount.coerceAtLeast(1)
    val placed = station.robots
        .filter { r -> r.layoutRow != null && r.layoutCol != null && r.layoutRow!! in 0 until rows && r.layoutCol!! in 0 until cols }
        .associateBy { it.layoutRow!! to it.layoutCol!! }
    val outside = station.robots.filter { it !in placed.values }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0 until cols) MiniCell(placed[r to c]?.let { ui.state(it.id) }, cell)
            }
        }
        outside.chunked(cols).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { chunk.forEach { MiniCell(ui.state(it.id), cell) } }
        }
    }
}

@Composable
private fun MiniCell(state: HeartbeatState?, size: Dp) {
    val shape = RoundedCornerShape(2.dp)
    if (state == null) {
        Surface(Modifier.size(size).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape), shape = shape, color = Color.Transparent) {}
    } else {
        Surface(Modifier.size(size), shape = shape, color = stateColor(state)) {}
    }
}

/**
 * A mini planta de uma linha: as estações na ordem do processo, cada uma com a mini grade e o
 * nome embaixo. Entre duas estações vai "→", ou "⇄" azul quando uma reaproveita os programas da
 * outra. Um "⇄" amarelo no fim avisa que a linha tem ligação com outra linha.
 */
@Composable
internal fun MiniPlant(line: LineNode, ui: ClientsUi, onStationClick: ((StationNode) -> Unit)? = null) {
    val names = line.stations.map { it.name }.toSet()
    val crossLine = ui.links.any { it.crossLine && (it.master in names || it.slave in names) }
    if (line.stations.isEmpty()) {
        Text("Nenhuma estação", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        line.stations.forEachIndexed { i, s ->
            if (i > 0) {
                val prev = line.stations[i - 1].name
                val linked = ui.links.any { !it.crossLine && setOf(it.master, it.slave) == setOf(prev, s.name) }
                Text(
                    if (linked) "⇄" else "→",
                    color = if (linked) LinkBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (linked) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 16.sp
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (onStationClick != null) Modifier.clickable { onStationClick(s) } else Modifier)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(6.dp)
            ) {
                StationMiniGrid(s, ui)
                Text(
                    s.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 90.dp).padding(top = 4.dp)
                )
            }
        }
        if (crossLine) Text("⇄", color = CrossLineYellow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Etiqueta pequena (tipo de trabalho, "oculto"). */
@Composable
internal fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = textColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Janela simples de um nome (novo cliente, nova linha, renomear). */
@Composable
internal fun NameDialog(title: String, label: String, initial: String = "", confirm: String = "Salvar", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    FormDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** Uma escolha numa lista (cliente da linha nova, linha de destino, tipo de trabalho). */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    text: (T) -> String,
    selected: (T) -> Boolean = { false },
    extra: (@Composable () -> Unit)? = null,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    Text(
                        text(o),
                        fontWeight = if (selected(o)) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected(o)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(o) }.padding(vertical = 12.dp, horizontal = 4.dp)
                    )
                }
                extra?.invoke()
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Linha "LED + nome" usada nos cabeçalhos. */
@Composable
internal fun Led(state: HeartbeatState, size: Dp = 10.dp) {
    Surface(Modifier.size(size), shape = RoundedCornerShape(50), color = stateColor(state)) {}
}

/** Envio de programas entre estações, no texto: "Primer CAT → Top Coat CAT" (de onde → para onde). */
internal fun linkText(link: StationLink): String = "${link.master.trim()} → ${link.slave.trim()}"
