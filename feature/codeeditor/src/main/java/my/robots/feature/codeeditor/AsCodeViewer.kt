package my.robots.feature.codeeditor

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ação pendente na janela de edição de linha: alterar uma linha existente (guarda o
 * índice dela) ou inserir uma linha nova antes de um índice (empurra o resto para baixo).
 */
private sealed class LineDialogAction {
    data class Change(val index: Int) : LineDialogAction()
    data class Insert(val beforeIndex: Int) : LineDialogAction()
}

/**
 * Tela completa para ler e editar um arquivo de código AS, linha por linha.
 *
 * - fileName: nome mostrado no topo.
 * - content: o texto do arquivo.
 * - onBack: chamado ao tocar em voltar.
 * - isNewFile: true para um arquivo que ainda não tem um destino certo (ex.: aberto de
 *   fora do app, sem robô dono ainda). Muda o ícone de salvar para deixar claro que falta
 *   escolher onde ele vai ser guardado.
 * - onSave: chamado ao tocar no disquete, com o texto atual (linhas juntas por "\n").
 *   É suspend e devolve se salvou de verdade (false = usuário cancelou, ex.: fechou a
 *   janela de escolher o robô) — só aí o ícone volta ao estado "salvo". O padrão sempre
 *   devolve true sem fazer nada, então uma tela somente-leitura funciona normalmente.
 *
 * O ícone de salvar muda de acordo com o estado do arquivo: normal (nada para salvar),
 * com uma bolinha quando há alteração ainda não salva, ou o ícone "salvar como" (com a
 * mesma bolinha) quando o arquivo é novo e precisa de um destino antes de gravar.
 *
 * O texto NUNCA é editável direto na área de código — num arquivo com milhares de
 * linhas, digitar dentro de um campo rolando na tela do celular é fácil de errar sem
 * querer. Toda mudança passa pelo modo de edição (ícone de lápis): cada linha ganha uma
 * caixa de seleção, e uma barra de ações aparece embaixo da barra do topo, agindo sobre
 * o que estiver marcado:
 * - Copiar: manda o texto das linhas marcadas (uma ou mais) para a área de transferência.
 * - Colar: insere o texto que estiver na área de transferência acima da linha marcada
 *   (pode ter várias linhas; todas entram, empurrando o resto do arquivo para baixo).
 * - Alterar: abre uma janela só com o texto da linha marcada (exige exatamente uma),
 *   para editar isolado, sem risco de mexer em outra parte do arquivo.
 * - Inserir: abre a mesma janela, vazia; o texto digitado vira uma linha nova acima da
 *   marcada.
 * - Excluir: remove todas as linhas marcadas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsCodeViewer(
    fileName: String,
    content: String,
    onBack: () -> Unit,
    isNewFile: Boolean = false,
    onSave: suspend (String) -> Boolean = { true }
) {
    // content.lines() é rápido para um arquivo comum, mas um backup Full pode ter dezenas
    // de milhares de linhas — feito direto na composição, isso trava a tela (a UI congela
    // até terminar). Por isso a divisão roda em segundo plano; "lines" só existe depois
    // que "isLoadingLines" termina, e a tela mostra um carregando até lá.
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingLines by remember { mutableStateOf(true) }
    var isEditMode by remember { mutableStateOf(false) }
    var selectedLines by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var lineDialog by remember { mutableStateOf<LineDialogAction?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDirty by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val hasSelection = selectedLines.isNotEmpty()
    val hasSingleSelection = selectedLines.size == 1
    val disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    LaunchedEffect(content) {
        isLoadingLines = true
        lines = withContext(Dispatchers.Default) { content.lines() }
        isLoadingLines = false
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (isSearchActive) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                                placeholder = { Text("Pesquisar...") },
                                trailingIcon = {
                                    IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp)
                            )
                        } else {
                            Text(fileName)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (!isSearchActive) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, null)
                            }
                        }
                        IconButton(
                            enabled = !isLoadingLines,
                            onClick = {
                                isEditMode = !isEditMode
                                selectedLines = emptySet()
                            }
                        ) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Default.EditOff else Icons.Default.Edit,
                                contentDescription = if (isEditMode) "Sair do Modo de Edição" else "Modo de Edição"
                            )
                        }
                        IconButton(
                            enabled = !isSaving && !isLoadingLines,
                            onClick = {
                                scope.launch {
                                    isSaving = true
                                    val saved = onSave(lines.joinToString("\n"))
                                    if (saved) isDirty = false
                                    isSaving = false
                                }
                            }
                        ) {
                            SaveIcon(isSaving = isSaving, isDirty = isDirty, isNewFile = isNewFile)
                        }
                    }
                )

                if (isEditMode) {
                    LineActionsToolbar(
                        hasSelection = hasSelection,
                        hasSingleSelection = hasSingleSelection,
                        disabledTint = disabledTint,
                        onCopy = {
                            val selectedText = selectedLines.sorted().joinToString("\n") { lines[it] }
                            clipboardManager.setText(AnnotatedString(selectedText))
                        },
                        onPaste = {
                            val index = selectedLines.first()
                            val clip = clipboardManager.getText()?.text
                            if (clip.isNullOrEmpty()) {
                                Toast.makeText(context, "Nada para colar", Toast.LENGTH_SHORT).show()
                            } else {
                                val pastedLines = clip.lines()
                                lines = lines.toMutableList().apply { addAll(index, pastedLines) }
                                selectedLines = emptySet()
                                isDirty = true
                            }
                        },
                        onChange = { lineDialog = LineDialogAction.Change(selectedLines.first()) },
                        onInsert = { lineDialog = LineDialogAction.Insert(selectedLines.first()) },
                        onDelete = {
                            lines = lines.filterIndexed { index, _ -> index !in selectedLines }
                            selectedLines = emptySet()
                            isDirty = true
                        }
                    )
                }
            }
        }
    ) { padding ->
        if (isLoadingLines) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            CodeLinesList(
                lines = lines,
                isEditMode = isEditMode,
                selectedLines = selectedLines,
                onToggleSelect = { index ->
                    selectedLines = if (index in selectedLines) selectedLines - index else selectedLines + index
                },
                searchQuery = searchQuery,
                modifier = Modifier.padding(padding)
            )
        }

        lineDialog?.let { action ->
            val initialText = when (action) {
                is LineDialogAction.Change -> lines.getOrElse(action.index) { "" }
                is LineDialogAction.Insert -> ""
            }
            LineEditDialog(
                title = if (action is LineDialogAction.Change) "Alterar Linha" else "Inserir Linha",
                initialText = initialText,
                onDismiss = { lineDialog = null },
                onSave = { newText ->
                    lines = when (action) {
                        is LineDialogAction.Change -> lines.toMutableList().apply { this[action.index] = newText }
                        is LineDialogAction.Insert -> lines.toMutableList().apply { add(action.beforeIndex, newText) }
                    }
                    selectedLines = emptySet()
                    lineDialog = null
                    isDirty = true
                }
            )
        }
    }
}

/**
 * Ícone do botão de salvar, de acordo com o estado do arquivo:
 * - salvando: um círculo de carregando;
 * - novo (sem destino ainda): "salvar como" na cor de destaque, com uma bolinha;
 * - com alteração pendente: o disquete normal, com a mesma bolinha;
 * - tudo salvo: o disquete normal, sem bolinha.
 */
@Composable
private fun SaveIcon(isSaving: Boolean, isDirty: Boolean, isNewFile: Boolean) {
    when {
        isSaving -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        isNewFile -> BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
            Icon(
                imageVector = Icons.Default.SaveAs,
                contentDescription = "Salvar em um Robô",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        isDirty -> BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
            Icon(Icons.Default.Save, contentDescription = "Salvar Alterações")
        }
        else -> Icon(
            imageVector = Icons.Default.Save,
            contentDescription = "Tudo Salvo",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * Barra de ações do modo de edição: copiar/colar/alterar/inserir/excluir, agindo sobre
 * as linhas marcadas. Alterar/Inserir/Colar exigem exatamente uma linha marcada (é o
 * ponto de referência); Copiar/Excluir aceitam uma ou mais.
 */
@Composable
fun LineActionsToolbar(
    hasSelection: Boolean,
    hasSingleSelection: Boolean,
    disabledTint: Color,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onChange: () -> Unit,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onCopy, enabled = hasSelection) {
                Icon(Icons.Default.ContentCopy, "Copiar", tint = if (hasSelection) LocalContentColor.current else disabledTint)
            }
            IconButton(onClick = onPaste, enabled = hasSingleSelection) {
                Icon(Icons.Default.ContentPaste, "Colar", tint = if (hasSingleSelection) LocalContentColor.current else disabledTint)
            }
            IconButton(onClick = onChange, enabled = hasSingleSelection) {
                Icon(Icons.Default.EditNote, "Alterar Linha", tint = if (hasSingleSelection) MaterialTheme.colorScheme.primary else disabledTint)
            }
            IconButton(onClick = onInsert, enabled = hasSingleSelection) {
                Icon(Icons.Default.PlaylistAdd, "Inserir Linha", tint = if (hasSingleSelection) MaterialTheme.colorScheme.primary else disabledTint)
            }
            IconButton(onClick = onDelete, enabled = hasSelection) {
                Icon(Icons.Default.Delete, "Excluir", tint = if (hasSelection) MaterialTheme.colorScheme.error else disabledTint)
            }
        }
    }
}

/**
 * Lista das linhas do arquivo: número + código (colorido), com caixa de seleção quando
 * o modo de edição está ligado. Cada linha vira uma linha da lista (LazyColumn) — só as
 * visíveis na tela são desenhadas/coloridas, então funciona bem mesmo num arquivo com
 * dezenas de milhares de linhas (diferente da versão anterior, que precisava degradar o
 * destaque de sintaxe em arquivo grande — aqui não precisa mais).
 */
@Composable
fun CodeLinesList(
    lines: List<String>,
    isEditMode: Boolean,
    selectedLines: Set<Int>,
    onToggleSelect: (Int) -> Unit,
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    val keywords = remember {
        setOf(
            ".PROGRAM", ".END", "CALL", "IF", "THEN", "ELSE", "ENDIF",
            "FOR", "TO", "STEP", "ENDFOR", "WHILE", "DO", "ENDWHILE",
            "WAIT", "SIGNAL", "SPEED", "ACCEL", "LMOVE", "JMOVE", "PRINT", "SIG",
            ".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", "GOTO", "CASE", "VALUE"
        )
    }
    val horizontalScrollState = rememberScrollState()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFF1E1E1E))
    ) {
        itemsIndexed(lines) { index, line ->
            CodeLineRow(
                lineNumber = index + 1,
                text = line,
                isEditMode = isEditMode,
                isSelected = index in selectedLines,
                onToggleSelect = { onToggleSelect(index) },
                keywords = keywords,
                searchQuery = searchQuery,
                horizontalScrollState = horizontalScrollState
            )
        }
    }
}

/**
 * Uma linha do editor: caixa de seleção (só no modo de edição), número e o código
 * colorido, com rolagem horizontal compartilhada entre todas as linhas (para as colunas
 * ficarem alinhadas ao rolar de lado).
 */
@Composable
fun CodeLineRow(
    lineNumber: Int,
    text: String,
    isEditMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    keywords: Set<String>,
    searchQuery: String,
    horizontalScrollState: ScrollState
) {
    val highlighted = remember(text, searchQuery) { highlightAsCode(text, keywords, searchQuery) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFF264F78) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Espaço da caixa de seleção sempre reservado (mesmo fora do modo de edição), pra
        // número e código não deslocarem para o lado ao ligar/desligar a edição. O Checkbox
        // do Material tem uma área de toque padrão de 48dp — bem maior que a linha de código
        // (~24dp) — então ele é encolhido para não esticar a altura da linha.
        Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            if (isEditMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = lineNumber.toString(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFF858585),
                textAlign = TextAlign.End
            ),
            modifier = Modifier
                .width(48.dp)
                .background(Color(0xFF252526))
                .padding(vertical = 2.dp, horizontal = 8.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
        ) {
            Text(
                text = highlighted,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFFD4D4D4)
                ),
                softWrap = false,
                modifier = Modifier
                    .width(2000.dp)
                    .padding(vertical = 2.dp, horizontal = 4.dp)
            )
        }
    }
}

/**
 * Janela de edição de UMA linha, usada tanto para "Alterar" (texto atual pré-preenchido)
 * quanto para "Inserir" (começa vazia). Aceita quebra de linha dentro do texto (vira mais
 * de uma linha ao salvar, se for o caso).
 */
@Composable
fun LineEditDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text) }) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/**
 * Colore uma linha de código AS lendo o texto UMA vez, da esquerda para a direita.
 *
 * Cores:
 * - verde: comentários (começam com ";" e vão até o fim da linha);
 * - laranja: textos entre aspas;
 * - amarelo: seções (.PROGRAM, .END, .TRANS...);
 * - azul: comandos de movimento (LMOVE, JMOVE, SPEED...);
 * - verde-água: sinais e esperas (SIGNAL, WAIT...);
 * - roxo: outras palavras-chave (IF, FOR, CALL...);
 * - verde-claro: números.
 * Por último, pinta de amarelo os trechos que casam com a busca (searchTerm).
 */
fun highlightAsCode(text: String, keywords: Set<String>, searchTerm: String): AnnotatedString {
    val commentColor = Color(0xFF6A9955)
    val keywordColor = Color(0xFFC586C0)
    val moveColor = Color(0xFF569CD6)
    val sectionColor = Color(0xFFDCDCAA)
    val signalColor = Color(0xFF4EC9B0)
    val stringColor = Color(0xFFCE9178)
    val numberColor = Color(0xFFB5CEA8)
    val defaultColor = Color(0xFFD4D4D4)
    val searchMatchBg = Color(0xFFEBC111)
    val searchMatchFg = Color.Black

    // Grupos de palavras: cada grupo tem a sua cor na tela.
    val moveCommands = setOf("LMOVE", "JMOVE", "MOVE", "DRIVE", "APPRO", "DEPART", "SPEED", "ACCEL")
    val signalCommands = setOf("SIGNAL", "WAIT", "PULSE", "SIG", "BITS")
    val sectionCommands = setOf(".PROGRAM", ".END", ".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS")

    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val char = text[i]

            when {
                // comentário: do ";" até o fim da linha
                char == ';' -> {
                    val start = i
                    while (i < text.length && text[i] != '\n') i++
                    withStyle(SpanStyle(color = commentColor)) {
                        append(text.substring(start, i))
                    }
                }
                // texto entre aspas (termina na aspa seguinte ou no fim da linha)
                char == '"' -> {
                    val start = i
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') i++
                    if (i < text.length && text[i] == '"') i++
                    withStyle(SpanStyle(color = stringColor)) {
                        append(text.substring(start, i))
                    }
                }
                // palavra: junta letras/números/_/. e vê a qual grupo ela pertence
                char.isLetter() || char == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                    val word = text.substring(start, i)
                    val upperWord = word.uppercase()

                    val color = when {
                        upperWord in sectionCommands -> sectionColor
                        upperWord in moveCommands -> moveColor
                        upperWord in signalCommands -> signalColor
                        upperWord in keywords -> keywordColor
                        else -> defaultColor
                    }

                    if (color != defaultColor) {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                            append(word)
                        }
                    } else {
                        append(word)
                    }
                }
                // número (inclusive negativo e com ponto decimal)
                char.isDigit() || (char == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    withStyle(SpanStyle(color = numberColor)) {
                        append(text.substring(start, i))
                    }
                }
                else -> {
                    append(char)
                    i++
                }
            }
        }

        // destaque da busca, por cima das cores (ignora maiúsculas/minúsculas)
        if (searchTerm.isNotEmpty()) {
            var start = 0
            while (true) {
                start = text.indexOf(searchTerm, start, ignoreCase = true)
                if (start == -1) break
                addStyle(
                    style = SpanStyle(background = searchMatchBg, color = searchMatchFg),
                    start = start,
                    end = start + searchTerm.length
                )
                start += searchTerm.length
            }
        }
    }
}
