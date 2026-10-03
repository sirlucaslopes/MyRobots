package my.robots.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cor de uma ação da linha de ações. O texto e o ícone seguem a cor; [Normal] usa a cor
 * padrão do texto.
 * - [Primary]: a ação principal da tela (enviar, salvar com alteração pendente);
 * - [Danger]: apaga ou limpa algo (excluir, limpar o terminal);
 * - [Success]: estado bom ligado (robô conectado: o botão vira "Desconectar" em verde).
 */
enum class ActionTone { Normal, Primary, Danger, Success }

/**
 * Uma ação da linha de ações (embaixo do título).
 * - [selected]: ação de liga/desliga que está ligada (lupa aberta, modo de edição): ganha a
 *   pílula de destaque atrás do ícone;
 * - [badge]: bolinha no canto do ícone (ex.: alteração não salva);
 * - [menu]: em vez de [onClick], abre um menu ancorado na ação (recebe o "fechar").
 */
data class BarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit = {},
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val tone: ActionTone = ActionTone.Normal,
    val badge: Boolean = false,
    val menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null
)

/** Altura da linha de ações, a mesma em todas as telas. */
val ActionStripHeight = 64.dp

/** Largura mínima de cada ação: abaixo disso a linha rola de lado em vez de espremer. */
private val ActionMinWidth = 54.dp

/**
 * Barra do topo padrão do app, igual em todas as telas:
 * 1. a barra do título: voltar (se houver), o título numa linha só (com subtítulo opcional) e,
 *    à direita, só o ⋮ com as opções menos usadas ([menu]);
 * 2. a linha de ações ([actions]), logo abaixo, sempre com [ActionStripHeight]: ícone e nome
 *    de cada ação, repartindo a largura (com muitas ações, rola de lado);
 * 3. [below]: algo que a tela queira logo embaixo (campo de busca, barra de edição).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    backLabel: String = "Voltar",
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    actions: List<BarAction> = emptyList(),
    below: (@Composable () -> Unit)? = null
) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(backIcon, contentDescription = backLabel) }
                }
            },
            actions = {
                if (menu != null) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Mais opções")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            menu { open = false }
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        if (actions.isNotEmpty()) ActionStrip(actions)
        below?.invoke()
    }
}

/**
 * A linha de ações: ícone com o nome embaixo, como a barra de navegação do Android. Sobra
 * espaço: as ações repartem a largura; falta: cada uma fica com [ActionMinWidth] e a linha
 * rola de lado.
 */
@Composable
fun ActionStrip(actions: List<BarAction>, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().height(ActionStripHeight)) {
                val itemWidth = maxOf(ActionMinWidth, maxWidth / actions.size.coerceAtLeast(1))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    actions.forEach { action -> ActionItem(action, Modifier.width(itemWidth)) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ActionItem(action: BarAction, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val base = when (action.tone) {
        ActionTone.Normal -> MaterialTheme.colorScheme.onSurface
        ActionTone.Primary -> MaterialTheme.colorScheme.primary
        ActionTone.Danger -> MaterialTheme.colorScheme.error
        ActionTone.Success -> SuccessGreen
    }
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> base
    }
    val pill by animateColorAsState(
        if (action.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "pill"
    )
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(ActionStripHeight)
                .selectable(
                    selected = action.selected,
                    enabled = action.enabled,
                    role = Role.Button,
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = 32.dp),
                    onClick = { if (action.menu != null) menuOpen = true else action.onClick() }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(width = 52.dp, height = 30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(pill),
                contentAlignment = Alignment.Center
            ) {
                Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                if (action.badge) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 12.dp)
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
            Text(
                action.label,
                color = color,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (action.selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
            )
        }
        if (action.menu != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                action.menu.invoke(this) { menuOpen = false }
            }
        }
    }
}

/** Verde do "conectado", o mesmo dos botões de conexão do app. */
val SuccessGreen = Color(0xFF43A047)
