package my.robots.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Janela de formulário que convive com o teclado.
 *
 * O AlertDialog padrão não encolhe quando o teclado abre: os campos de baixo ficam escondidos
 * e não dá para rolar até eles. Aqui a janela ocupa a tela inteira (decorFitsSystemWindows =
 * false), desconta a barra do sistema e o teclado (systemBarsPadding + imePadding) e o conteúdo
 * rola. Assim o campo em edição sempre aparece, e dá para arrastar para ver os outros.
 *
 * Os botões ficam fixos embaixo do conteúdo (fora da rolagem). Tocar fora não fecha a janela,
 * para não perder o que foi digitado sem querer: fecha-se pelo botão de cancelar.
 *
 * [stackedButtons]: com mais de uma ação (ex.: Compartilhar e Salvar), os botões ficam um
 * embaixo do outro em largura total (confirmar primeiro, cancelar por último), em vez de
 * espremidos numa linha só. Quem chama passa `Modifier.fillMaxWidth()` em cada botão.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    stackedButtons: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content
                    )
                    if (stackedButtons) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            confirmButton()
                            dismissButton()
                        }
                    } else {
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
