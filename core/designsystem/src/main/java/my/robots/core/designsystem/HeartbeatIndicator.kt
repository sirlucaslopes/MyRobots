package my.robots.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * Usada no popup "Robôs Conectados", no card de cada robô da lista e na cabine do projeto.
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
