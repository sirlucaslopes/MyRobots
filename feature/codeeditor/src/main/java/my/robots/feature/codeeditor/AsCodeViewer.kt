package my.robots.feature.codeeditor

import my.robots.core.designsystem.FormDialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import my.robots.core.common.ascode.AsInstructions
import my.robots.core.common.ascode.InstructionDef
import my.robots.core.common.ascode.ParsedInstruction
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    /** Linha nova na posição [beforeIndex]; [after] = "Adicionar" (depois da marcada) em vez de "Inserir". */
    data class Insert(val beforeIndex: Int, val refIndex: Int, val after: Boolean) : LineDialogAction()
}

/** Quantos passos de desfazer ficam guardados (o mais antigo cai fora depois disso). */
private const val MAX_UNDO_STEPS = 50

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
 * - Marcar Linhas (ícone de lista): marcar todas de uma vez, limpar a marcação, marcar da
 *   linha atual para cima/para baixo, ou marcar tudo entre duas linhas já marcadas — sem
 *   precisar tocar caixa por caixa num intervalo grande.
 * - Copiar: manda o texto das linhas marcadas (uma ou mais) para a área de transferência.
 * - Colar: insere o texto que estiver na área de transferência acima da linha marcada
 *   (pode ter várias linhas; todas entram, empurrando o resto do arquivo para baixo).
 * - Alterar (ou segurar a linha): se a linha é uma instrução conhecida (AsInstructions: SPRAY,
 *   SPRAY_SPEED, LMOVE, TWAIT, CALL_DBK...), abre a edição campo a campo, como o CHANGE do
 *   teach pendant, com as outras instruções do mesmo grupo para trocar. Senão, ou em "Editar
 *   como texto", abre a janela com o texto da linha.
 * - Inserir: escolhe o grupo e a instrução (como a lista do pendant) ou "Texto livre"; a linha
 *   nova entra acima da marcada, com o mesmo recuo.
 * - Excluir: remove todas as linhas marcadas.
 * - Deslocar/Espelhar Pontos: veem PointTransform.kt. Agem sobre os pontos usados pelos
 *   comandos LMOVE/JMOVE dentro das linhas marcadas (o "intervalo" é a seleção) — só altera
 *   pontos que pertencem exclusivamente ao programa das linhas escolhidas, pulando os que
 *   também são usados em outro programa.
 *
 * Pesquisa (lupa): destaca o texto e mostra "N de M" com setas para ir de uma linha encontrada à
 * outra (a lista rola até ela). O botão "Comandos" lista as instruções que existem no arquivo,
 * como a pesquisa de instrução do pendant.
 *
 * Toda mudança (manual ou pelas funções de ponto) passa por updateLines(), que alimenta uma
 * pilha de desfazer/refazer (ícones no topo, sempre visíveis).
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
    // o que está sendo digitado; vira searchQuery ao tocar em pesquisar (num arquivo FULL,
    // pesquisar a cada letra seria pesado)
    var searchInput by remember { mutableStateOf("") }
    var showEditChoice by remember { mutableStateOf(false) }
    var showConversionMenu by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    // linha encontrada em destaque (posição dentro de searchMatches)
    var currentMatch by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    // edição de instrução: força o texto livre, ou a instrução escolhida no "Inserir"
    var forceTextEdit by remember { mutableStateOf(false) }
    var insertDef by remember { mutableStateOf<InstructionDef?>(null) }
    var insertAsText by remember { mutableStateOf(false) }
    var isDirty by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showShiftDialog by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }

    // Pilhas de desfazer/refazer: guardam versões anteriores de "lines". Qualquer mudança
    // (manual ou pelas funções de ponto) deve passar por updateLines(), nunca atribuir
    // "lines" direto, senão ela não entra no histórico.
    var undoStack by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    fun updateLines(newLines: List<String>) {
        undoStack = (undoStack + listOf(lines)).takeLast(MAX_UNDO_STEPS)
        redoStack = emptyList()
        lines = newLines
        isDirty = true
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = listOf(lines) + redoStack
        lines = previous
        undoStack = undoStack.dropLast(1)
        isDirty = true
    }

    fun redo() {
        val next = redoStack.firstOrNull() ?: return
        undoStack = undoStack + listOf(lines)
        lines = next
        redoStack = redoStack.drop(1)
        isDirty = true
    }

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

    // linhas que casam com a pesquisa (só o índice), refeitas em segundo plano
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var lastSearch by remember { mutableStateOf("") }
    LaunchedEffect(lines, searchQuery) {
        searchMatches = if (searchQuery.isBlank()) emptyList() else withContext(Dispatchers.Default) {
            lines.indices.filter { lines[it].contains(searchQuery, ignoreCase = true) }
        }
        if (searchQuery != lastSearch) {
            // pesquisa nova: vai para a primeira linha encontrada
            lastSearch = searchQuery
            currentMatch = 0
            searchMatches.firstOrNull()?.let { listState.scrollToItem(it) }
        } else {
            // só o texto mudou (edição): mantém o resultado atual
            currentMatch = currentMatch.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0))
        }
    }
    fun goToMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        currentMatch = (currentMatch + delta).mod(searchMatches.size)
        scope.launch { listState.animateScrollToItem(searchMatches[currentMatch]) }
    }

    // Voltar do sistema: primeiro fecha a pesquisa, depois sai do modo de edição; só então
    // sai do editor (igual ao botão de voltar da barra do topo para a pesquisa)
    androidx.activity.compose.BackHandler(enabled = isSearchActive || isEditMode) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
            searchInput = ""
        } else {
            isEditMode = false
            selectedLines = emptySet()
        }
    }

    fun openChange(index: Int) {
        forceTextEdit = false
        lineDialog = LineDialogAction.Change(index)
    }
    /** Inserir: a linha nova fica no lugar da marcada (que desce). Adicionar: logo depois dela. */
    fun openInsert(index: Int, after: Boolean = false) {
        insertDef = null
        insertAsText = false
        lineDialog = LineDialogAction.Insert(if (after) index + 1 else index, index, after)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(fileName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    },
                    actions = {
                        // lupa: abre/fecha a barra de pesquisa
                        IconToggleButton(
                            checked = isSearchActive,
                            onCheckedChange = { on ->
                                isSearchActive = on
                                if (!on) { searchQuery = ""; searchInput = "" }
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Pesquisar",
                                tint = if (isSearchActive) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        // lápis: abre/fecha a barra de edição
                        IconToggleButton(
                            checked = isEditMode,
                            enabled = !isLoadingLines,
                            onCheckedChange = { on ->
                                isEditMode = on
                                selectedLines = emptySet()
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Modo de edição",
                                tint = if (isEditMode) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
                        // ⋮: conversão de programa (deslocar, espelhar...)
                        Box {
                            IconButton(onClick = { showConversionMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Conversão de programa")
                            }
                            ProgramConversionMenu(
                                expanded = showConversionMenu,
                                hasSelection = hasSelection,
                                onDismiss = { showConversionMenu = false },
                                onShift = { showConversionMenu = false; showShiftDialog = true },
                                onMirror = { showConversionMenu = false; showMirrorDialog = true }
                            )
                        }
                    }
                )

                if (isSearchActive) {
                    SearchNavigationBar(
                        input = searchInput,
                        onInputChange = { searchInput = it },
                        onSearch = {
                            if (searchInput == searchQuery) goToMatch(1) else searchQuery = searchInput
                        },
                        query = searchQuery,
                        matchCount = searchMatches.size,
                        current = currentMatch,
                        lines = lines,
                        onPrev = { goToMatch(-1) },
                        onNext = { goToMatch(1) },
                        onPickCommand = { searchInput = it; searchQuery = it }
                    )
                }
                if (isEditMode) {
                    LineActionsToolbar(
                        hasSelection = hasSelection,
                        hasSingleSelection = hasSingleSelection,
                        hasTwoSelected = selectedLines.size == 2,
                        disabledTint = disabledTint,
                        onSelectAll = { selectedLines = lines.indices.toSet() },
                        onClearSelection = { selectedLines = emptySet() },
                        onSelectUpward = {
                            val anchor = selectedLines.first()
                            selectedLines = (0..anchor).toSet()
                        },
                        onSelectDownward = {
                            val anchor = selectedLines.first()
                            selectedLines = (anchor until lines.size).toSet()
                        },
                        onSelectRange = {
                            val sorted = selectedLines.sorted()
                            selectedLines = (sorted.first()..sorted.last()).toSet()
                        },
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
                                updateLines(lines.toMutableList().apply { addAll(index, pastedLines) })
                                selectedLines = emptySet()
                            }
                        },
                        onEdit = { showEditChoice = true },
                        onDelete = {
                            updateLines(lines.filterIndexed { index, _ -> index !in selectedLines })
                            selectedLines = emptySet()
                        },
                        canUndo = undoStack.isNotEmpty(),
                        canRedo = redoStack.isNotEmpty(),
                        onUndo = ::undo,
                        onRedo = ::redo
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
                listState = listState,
                currentMatchLine = searchMatches.getOrNull(currentMatch),
                onLongPress = { index ->
                    // segurar a linha: como o CHANGE do pendant
                    isEditMode = true
                    selectedLines = setOf(index)
                    openChange(index)
                },
                modifier = Modifier.padding(padding)
            )
        }

        if (showEditChoice && selectedLines.size == 1) {
            val index = selectedLines.first()
            EditChoiceDialog(
                lineNumber = index + 1,
                onEdit = { showEditChoice = false; openChange(index) },
                onInsert = { showEditChoice = false; openInsert(index) },
                onAdd = { showEditChoice = false; openInsert(index, after = true) },
                onDismiss = { showEditChoice = false }
            )
        }

        lineDialog?.let { action ->
            fun saveLine(newText: String) {
                updateLines(
                    when (action) {
                        is LineDialogAction.Change -> lines.toMutableList().apply { this[action.index] = newText }
                        is LineDialogAction.Insert -> lines.toMutableList().apply { add(action.beforeIndex, newText) }
                    }
                )
                selectedLines = emptySet()
                lineDialog = null
            }
            val close = { lineDialog = null }
            when (action) {
                is LineDialogAction.Change -> {
                    val current = lines.getOrElse(action.index) { "" }
                    val parsed = if (forceTextEdit) null else AsInstructions.parse(current)
                    if (parsed != null) {
                        InstructionEditDialog(
                            title = "Alterar linha ${action.index + 1}",
                            start = parsed,
                            onEditAsText = { forceTextEdit = true },
                            onDismiss = close,
                            onSave = ::saveLine
                        )
                    } else {
                        LineEditDialog(title = "Alterar Linha", initialText = current, onDismiss = close, onSave = ::saveLine)
                    }
                }
                is LineDialogAction.Insert -> {
                    val chosen = insertDef
                    when {
                        insertAsText -> LineEditDialog(
                            title = if (action.after) "Adicionar linha" else "Inserir linha",
                            initialText = "", onDismiss = close, onSave = ::saveLine
                        )
                        chosen == null -> InstructionPickerDialog(
                            title = if (action.after) "Adicionar instrução" else "Inserir instrução",
                            onPick = { insertDef = it },
                            onFreeText = { insertAsText = true },
                            onDismiss = close
                        )
                        else -> {
                            // a linha nova entra com o mesmo recuo da linha marcada
                            val reference = lines.getOrElse(action.refIndex) { "" }
                            val indent = reference.takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
                            InstructionEditDialog(
                                title = (if (action.after) "Adicionar: " else "Inserir: ") + chosen.label,
                                start = ParsedInstruction(chosen, chosen.defaults(), indent, ""),
                                onEditAsText = { insertAsText = true },
                                onDismiss = close,
                                onSave = ::saveLine
                            )
                        }
                    }
                }
            }
        }

        if (showShiftDialog) {
            ShiftPointsDialog(
                onDismiss = { showShiftDialog = false },
                onApply = { moveFilter, deltas ->
                    val result = applyPointShift(lines, selectedLines.toList(), moveFilter, deltas)
                    if (result.error == null) {
                        updateLines(result.lines)
                        selectedLines = emptySet()
                    }
                    Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                    showShiftDialog = false
                }
            )
        }

        if (showMirrorDialog) {
            MirrorPointsDialog(
                onDismiss = { showMirrorDialog = false },
                onApply = { moveFilter, axis ->
                    val result = applyPointMirror(lines, selectedLines.toList(), moveFilter, axis)
                    if (result.error == null) {
                        updateLines(result.lines)
                        selectedLines = emptySet()
                    }
                    Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                    showMirrorDialog = false
                }
            )
        }
    }
}

/**
 * Barra de pesquisa (abre na lupa): campo de texto, botão pesquisar, setas para a linha
 * encontrada anterior/próxima e "N de M". No campo, o ícone de lista abre "Comandos": as
 * instruções do catálogo que existem no arquivo (com quantas vezes aparecem), como a pesquisa
 * de instrução do teach pendant. A pesquisa só roda ao tocar em pesquisar (ou no teclado);
 * tocar de novo com o mesmo texto vai para a próxima.
 */
@Composable
private fun SearchNavigationBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    query: String,
    matchCount: Int,
    current: Int,
    lines: List<String>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickCommand: (String) -> Unit
) {
    var showCommands by remember { mutableStateOf(false) }
    // ao pesquisar, o teclado fecha para a lista aparecer inteira
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val search = { keyboard?.hide(); onSearch() }
    // instruções do arquivo: contadas só quando a lista abre
    val commands = remember(showCommands, lines) {
        if (!showCommands) emptyList()
        else AsInstructions.all.map { it.keyword }.distinct().mapNotNull { k ->
            val n = lines.count { it.trimStart().startsWith("$k ", ignoreCase = true) }
            if (n > 0) k to n else null
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Pesquisar...") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    Box {
                        IconButton(onClick = { showCommands = true }) {
                            Icon(Icons.Default.ManageSearch, contentDescription = "Comandos do arquivo")
                        }
                        DropdownMenu(expanded = showCommands, onDismissRequest = { showCommands = false }) {
                            if (commands.isEmpty()) {
                                DropdownMenuItem(text = { Text("Nenhuma instrução conhecida") }, onClick = { showCommands = false })
                            }
                            commands.forEach { (k, n) ->
                                DropdownMenuItem(
                                    text = { Text("$k  ($n)") },
                                    onClick = { showCommands = false; keyboard?.hide(); onPickCommand("$k ") }
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = search, enabled = input.isNotBlank()) {
                Icon(Icons.Default.Search, contentDescription = "Pesquisar", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onPrev, enabled = matchCount > 0, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Anterior")
            }
            IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Próxima")
            }
            Text(
                when {
                    query.isBlank() -> "–"
                    matchCount == 0 -> "0"
                    else -> "${current + 1} de\n$matchCount"
                },
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 44.dp)
            )
        }
    }
}

/**
 * Menu ⋮ "Conversão de programa": funções que transformam as linhas marcadas no modo de edição
 * (deslocar e espelhar pontos, ver PointTransform.kt). Sem linhas marcadas, só mostra a dica.
 */
@Composable
private fun ProgramConversionMenu(
    expanded: Boolean,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onShift: () -> Unit,
    onMirror: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "Conversão de programa",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (!hasSelection) {
            Text(
                "Marque as linhas no modo de edição (lápis).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 240.dp)
            )
        }
        DropdownMenuItem(
            text = { Text("Deslocar pontos") },
            leadingIcon = { Icon(Icons.Default.OpenWith, null) },
            enabled = hasSelection,
            onClick = onShift
        )
        DropdownMenuItem(
            text = { Text("Espelhar pontos") },
            leadingIcon = { Icon(Icons.Default.Flip, null) },
            enabled = hasSelection,
            onClick = onMirror
        )
    }
}

/**
 * "Edit" da barra de edição: o que fazer com a linha marcada.
 * - Editar: altera a linha (por campos, se for uma instrução conhecida, ou como texto).
 * - Inserir: a linha nova entra no lugar da marcada, que desce junto com as de baixo.
 * - Adicionar: a linha nova entra logo depois da marcada.
 */
@Composable
private fun EditChoiceDialog(
    lineNumber: Int,
    onEdit: () -> Unit,
    onInsert: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linha $lineNumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditChoiceRow(Icons.Default.EditNote, "Editar", "Alterar o comando ou o texto desta linha", onEdit)
                EditChoiceRow(Icons.Default.VerticalAlignTop, "Inserir", "Linha nova aqui; esta e as de baixo descem", onInsert)
                EditChoiceRow(Icons.Default.PlaylistAdd, "Adicionar", "Linha nova logo depois desta", onAdd)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun EditChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * Barra de edição (abre no lápis): marcar linhas em lote, copiar, colar, Edit (Editar /
 * Inserir / Adicionar), excluir e, à direita, desfazer e refazer. Colar e Edit exigem
 * exatamente uma linha marcada; copiar e excluir aceitam várias. Deslocar e espelhar pontos
 * ficam no menu ⋮ "Conversão de programa" e agem sobre as linhas marcadas aqui.
 */
@Composable
fun LineActionsToolbar(
    hasSelection: Boolean,
    hasSingleSelection: Boolean,
    hasTwoSelected: Boolean,
    disabledTint: Color,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectUpward: () -> Unit,
    onSelectDownward: () -> Unit,
    onSelectRange: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    var showSelectionMenu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = { showSelectionMenu = true }) {
                    Icon(Icons.Default.Checklist, "Marcar Linhas", tint = LocalContentColor.current)
                }
                DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Marcar Todas") },
                        onClick = { showSelectionMenu = false; onSelectAll() }
                    )
                    DropdownMenuItem(
                        text = { Text("Limpar Marcação") },
                        enabled = hasSelection,
                        onClick = { showSelectionMenu = false; onClearSelection() }
                    )
                    DropdownMenuItem(
                        text = { Text("Da Linha Marcada para Cima") },
                        enabled = hasSingleSelection,
                        onClick = { showSelectionMenu = false; onSelectUpward() }
                    )
                    DropdownMenuItem(
                        text = { Text("Da Linha Marcada para Baixo") },
                        enabled = hasSingleSelection,
                        onClick = { showSelectionMenu = false; onSelectDownward() }
                    )
                    DropdownMenuItem(
                        text = { Text("Preencher Entre as 2 Marcadas") },
                        enabled = hasTwoSelected,
                        onClick = { showSelectionMenu = false; onSelectRange() }
                    )
                }
            }
            IconButton(onClick = onCopy, enabled = hasSelection) {
                Icon(Icons.Default.ContentCopy, "Copiar", tint = if (hasSelection) LocalContentColor.current else disabledTint)
            }
            IconButton(onClick = onPaste, enabled = hasSingleSelection) {
                Icon(Icons.Default.ContentPaste, "Colar", tint = if (hasSingleSelection) LocalContentColor.current else disabledTint)
            }
            // Edit: pergunta Editar / Inserir / Adicionar na linha marcada
            IconButton(onClick = onEdit, enabled = hasSingleSelection) {
                Icon(Icons.Default.EditNote, "Editar, inserir ou adicionar", tint = if (hasSingleSelection) MaterialTheme.colorScheme.primary else disabledTint)
            }
            IconButton(onClick = onDelete, enabled = hasSelection) {
                Icon(Icons.Default.Delete, "Excluir", tint = if (hasSelection) MaterialTheme.colorScheme.error else disabledTint)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer", tint = if (canUndo) LocalContentColor.current else disabledTint)
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Refazer", tint = if (canRedo) LocalContentColor.current else disabledTint)
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
    listState: LazyListState = rememberLazyListState(),
    currentMatchLine: Int? = null,
    onLongPress: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val keywords = remember {
        setOf(
            ".PROGRAM", ".END", "CALL", "IF", "THEN", "ELSE", "ENDIF",
            "FOR", "TO", "STEP", "ENDFOR", "WHILE", "DO", "ENDWHILE",
            "WAIT", "SIGNAL", "SPEED", "ACCEL", "LMOVE", "JMOVE", "PRINT", "SIG",
            ".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", "GOTO", "CASE", "VALUE",
            "SPRAY", "PRE_SPRAY", "SPRAY_SPEED", "AIRCUT_SPEED", "SPRAY_JSPEED", "AIRCUT_JSPEED",
            "ACCEL", "SMOOTH_RANGE", "CALL_DBK", "CALL_PGM", "TWAIT", "TIMER_WAIT", "UC_JUMP",
            "LABEL", "GUN", "DOUT"
        )
    }
    val horizontalScrollState = rememberScrollState()
    // largura da coluna de números pelo maior número (um arquivo FULL passa de 10.000 linhas)
    val numberWidth = (lines.size.toString().length * 9 + 18).dp

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(Color(0xFF1E1E1E))
    ) {
        itemsIndexed(lines) { index, line ->
            CodeLineRow(
                numberWidth = numberWidth,
                lineNumber = index + 1,
                text = line,
                isEditMode = isEditMode,
                isSelected = index in selectedLines,
                isCurrentMatch = index == currentMatchLine,
                onToggleSelect = { onToggleSelect(index) },
                onLongPress = { onLongPress(index) },
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
    numberWidth: androidx.compose.ui.unit.Dp = 48.dp,
    lineNumber: Int,
    text: String,
    isEditMode: Boolean,
    isSelected: Boolean,
    isCurrentMatch: Boolean = false,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit = {},
    keywords: Set<String>,
    searchQuery: String,
    horizontalScrollState: ScrollState
) {
    val highlighted = remember(text, searchQuery) { highlightAsCode(text, keywords, searchQuery) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isSelected -> Color(0xFF264F78)
                    isCurrentMatch -> Color(0xFF4B4318)   // linha da pesquisa em destaque
                    else -> Color.Transparent
                }
            )
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
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
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .width(numberWidth)
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

    FormDialog(
        onDismiss = onDismiss,
        title = title,
        content = {
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
 * Chips para escolher quais comandos de movimento (LMOVE/JMOVE/ambos) contam na busca por
 * pontos nas linhas marcadas. Usado pelos diálogos de Deslocar e Espelhar.
 */
@Composable
private fun MoveFilterSelector(selected: MoveFilter, onSelect: (MoveFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MoveFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/**
 * Janela da função "Deslocar Pontos": um campo de delta por eixo (X, Y, Z, O, A, T e os 3
 * eixos externos) — deixar em 0 é o mesmo que não mexer naquele eixo — e o filtro de qual
 * comando (LMOVE/JMOVE/ambos) considerar. Ao confirmar, devolve o filtro e os deltas prontos
 * (já convertidos para número) para quem chamou aplicar com [applyPointShift].
 */
@Composable
private fun ShiftPointsDialog(
    onDismiss: () -> Unit,
    onApply: (MoveFilter, Map<PointAxis, Double>) -> Unit
) {
    var moveFilter by remember { mutableStateOf(MoveFilter.BOTH) }
    val deltaTexts = remember {
        mutableStateMapOf<PointAxis, String>().apply { PointAxis.entries.forEach { put(it, "0") } }
    }

    FormDialog(
        onDismiss = onDismiss,
        title = "Deslocar Pontos",
        content = {
            Column(modifier = Modifier) {
                Text(
                    "Só altera pontos definidos e usados exclusivamente neste programa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Considerar pontos de:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                MoveFilterSelector(selected = moveFilter, onSelect = { moveFilter = it })
                Spacer(modifier = Modifier.height(16.dp))
                Text("Delta por eixo (0 = não mexe):", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                PointAxis.entries.chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        row.forEach { axis ->
                            OutlinedTextField(
                                value = deltaTexts[axis] ?: "0",
                                onValueChange = { deltaTexts[axis] = it },
                                label = { Text(axis.label) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val deltas = PointAxis.entries.associateWith { axis ->
                    deltaTexts[axis]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                }
                onApply(moveFilter, deltas)
            }) { Text("Aplicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/**
 * Janela da função "Espelhar Pontos": escolhe o eixo (X, Y ou Z) a multiplicar por -1 e o
 * filtro de comando (LMOVE/JMOVE/ambos).
 */
@Composable
private fun MirrorPointsDialog(
    onDismiss: () -> Unit,
    onApply: (MoveFilter, PointAxis) -> Unit
) {
    var moveFilter by remember { mutableStateOf(MoveFilter.BOTH) }
    var axis by remember { mutableStateOf(PointAxis.X) }
    val mirrorAxes = listOf(PointAxis.X, PointAxis.Y, PointAxis.Z)

    FormDialog(
        onDismiss = onDismiss,
        title = "Espelhar Pontos",
        content = {
            Column {
                Text(
                    "Só altera pontos definidos e usados exclusivamente neste programa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Considerar pontos de:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                MoveFilterSelector(selected = moveFilter, onSelect = { moveFilter = it })
                Spacer(modifier = Modifier.height(16.dp))
                Text("Eixo de espelhamento:", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    mirrorAxes.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { axis = a }
                                .padding(end = 8.dp)
                        ) {
                            RadioButton(selected = axis == a, onClick = { axis = a })
                            Text(a.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(moveFilter, axis) }) { Text("Aplicar") }
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
