package my.robots.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import my.robots.core.model.HeartbeatState

/**
 * Texto curto para cada estado de heartbeat ("Ativo", "Sem resposta", "Desconectado").
 */
fun HeartbeatState.label(): String = when (this) {
    HeartbeatState.ALIVE -> "Ativo"
    HeartbeatState.STALE -> "Sem resposta"
    HeartbeatState.DISCONNECTED -> "Desconectado"
}

/**
 * Bolinha de status: verde pulsando enquanto ALIVE (o robô está respondendo de
 * verdade), amarelo parado quando STALE (conectado mas quieto) e cinza quando
 * DISCONNECTED. O pulso só anima em ALIVE, para não poluir a tela à toa.
 *
 * Usada no card de cada robô da lista, na cabine do projeto e nos mini terminais.
 */
@Composable
fun HeartbeatDot(state: HeartbeatState, modifier: Modifier = Modifier) {
    val color = when (state) {
        HeartbeatState.ALIVE -> Color(0xFF4CAF50)
        HeartbeatState.STALE -> Color(0xFFFFA000)
        HeartbeatState.DISCONNECTED -> Color(0xFF9E9E9E)
    }

    val alpha: Float = if (state == HeartbeatState.ALIVE) {
        val infiniteTransition = rememberInfiniteTransition(label = "heartbeat_pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "heartbeat_alpha"
        )
        animatedAlpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/** O que cada cor do LED quer dizer, para a legenda. */
fun HeartbeatState.meaning(): String = when (this) {
    HeartbeatState.ALIVE -> "conectado e respondendo"
    HeartbeatState.STALE -> "conectado, sem resposta há 8 s"
    HeartbeatState.DISCONNECTED -> "sem conexão"
}

/**
 * Legenda das cores, numa faixa compacta: o LED de cada estado com o nome (o que cada um quer
 * dizer fica no [meaning], para leitores de tela) e o botão verde de "conectado".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeartbeatLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HeartbeatState.entries.forEach { state ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${state.label()}: ${state.meaning()}" }
            ) {
                HeartbeatDot(state)
                Spacer(Modifier.width(6.dp))
                Text(state.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 16.dp, height = 10.dp).background(SuccessGreen, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(6.dp))
            Text("Botão verde: conectado", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
