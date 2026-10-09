package my.robots.feature.project

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * F6: antes de ligar, transferir ou duplicar entre estações de LINHAS DIFERENTES (fora do mesmo
 * processo). [target] é a estação de destino; [action] o verbo ("transferir", "reaproveitar").
 */
@Composable
internal fun CrossLineWarning(target: String, action: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linha diferente") },
        text = {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Atenção: ") }
                append("$target é de outra linha e não está no mesmo processo. Tem certeza que deseja $action?")
            })
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
            ) { Text("Continuar mesmo assim") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
