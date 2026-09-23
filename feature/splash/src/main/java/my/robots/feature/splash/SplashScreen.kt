package my.robots.feature.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Tela de abertura: uma cabeça de robô desenhada na tela que "cresce" e pisca duas vezes.
 * Quando a animação termina, chama onAnimationFinished (o app então abre a lista de robôs).
 */
@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    
    // animação de tamanho: a cabeça cresce de 0 até o tamanho normal (com um leve quique)
    val headScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "HeadScale"
    )

    // animação do piscar: a altura do olho vai a 10% e volta a 100%
    var blinkCount by remember { mutableIntStateOf(0) }
    val eyeHeightScale by animateFloatAsState(
        targetValue = if (blinkCount % 2 == 1) 0.1f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "EyeBlink"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(800)
        // pisca duas vezes (cada piscada = fechar e abrir o olho)
        repeat(2) {
            blinkCount++
            delay(200)
            blinkCount++
            delay(400)
        }
        delay(500)
        onAnimationFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val robotBodyColor = MaterialTheme.colorScheme.primary
        val eyeColor = MaterialTheme.colorScheme.onPrimary

        Canvas(modifier = Modifier.size(200.dp)) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            // aplica o tamanho animado a tudo que for desenhado dentro
            scale(headScale) {
                // cabeça (caixa principal)
                drawRoundRect(
                    color = robotBodyColor,
                    topLeft = Offset(canvasWidth * 0.2f, canvasHeight * 0.3f),
                    size = Size(canvasWidth * 0.6f, canvasHeight * 0.5f),
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                )

                // haste da antena
                drawRect(
                    color = robotBodyColor,
                    topLeft = Offset(canvasWidth * 0.48f, canvasHeight * 0.2f),
                    size = Size(canvasWidth * 0.04f, canvasHeight * 0.1f)
                )
                
                // ponta da antena
                drawRect(
                    color = robotBodyColor,
                    topLeft = Offset(canvasWidth * 0.44f, canvasHeight * 0.17f),
                    size = Size(canvasWidth * 0.12f, canvasHeight * 0.03f)
                )

                // olhos
                val eyeWidth = canvasWidth * 0.1f
                val eyeHeightTotal = canvasHeight * 0.1f
                val eyeY = canvasHeight * 0.42f
                
                // olho esquerdo
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(canvasWidth * 0.35f, eyeY + (eyeHeightTotal * (1 - eyeHeightScale) / 2)),
                    size = Size(eyeWidth, eyeHeightTotal * eyeHeightScale),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // olho direito
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(canvasWidth * 0.55f, eyeY + (eyeHeightTotal * (1 - eyeHeightScale) / 2)),
                    size = Size(eyeWidth, eyeHeightTotal * eyeHeightScale),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // boca: duas linhas que lembram um terminal
                drawRect(
                    color = eyeColor.copy(alpha = 0.7f),
                    topLeft = Offset(canvasWidth * 0.35f, canvasHeight * 0.62f),
                    size = Size(canvasWidth * 0.3f, canvasHeight * 0.02f)
                )
                drawRect(
                    color = eyeColor.copy(alpha = 0.7f),
                    topLeft = Offset(canvasWidth * 0.35f, canvasHeight * 0.67f),
                    size = Size(canvasWidth * 0.2f, canvasHeight * 0.02f)
                )
            }
        }
    }
}
