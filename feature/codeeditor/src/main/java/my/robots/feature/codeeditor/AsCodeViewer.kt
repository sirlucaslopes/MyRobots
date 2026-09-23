package my.robots.feature.codeeditor

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tela completa para ler e editar um arquivo de código AS.
 *
 * - fileName: nome mostrado no topo.
 * - content: o texto do arquivo.
 * - onBack: chamado ao tocar em voltar.
 * - onSave: chamado ao tocar no disquete, com o texto atual. O padrão não faz
 *   nada, então telas somente de leitura simplesmente não salvam.
 *
 * Na barra do topo: lupa (buscar), lápis (liga/desliga a edição) e disquete (salvar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsCodeViewer(
    fileName: String,
    content: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit = {}
) {
    // Estados da tela: o texto atual, se está no modo de edição,
    // o texto buscado e se a barra de busca está aberta.
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }
    var isEditing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
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
                    IconButton(onClick = { isEditing = !isEditing }) {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.EditOff else Icons.Default.Edit,
                            contentDescription = if (isEditing) "Stop Editing" else "Start Editing"
                        )
                    }
                    IconButton(onClick = { 
                        onSave(textFieldValue.text)
                        isEditing = false
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save Changes")
                    }
                }
            )
        }
    ) { padding ->
        CodeEditorContent(
            textFieldValue = textFieldValue,
            onValueChange = { textFieldValue = it },
            isEditing = isEditing,
            searchQuery = searchQuery,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Área do editor: números das linhas à esquerda e o código (colorido) à direita.
 *
 * - Uma única rolagem horizontal e uma vertical movem números e código juntos.
 * - Se isEditing for false, o texto só pode ser lido.
 * - searchQuery pinta de amarelo tudo o que casar com a busca.
 */
@Composable
fun CodeEditorContent(
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    searchQuery: String = ""
) {
    // Rolagens compartilhadas: todas as linhas se movem juntas, na horizontal e na vertical.
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    
    // Palavras da linguagem AS que ganham cor de "palavra-chave".
    val keywords = remember {
        setOf(
            ".PROGRAM", ".END", "CALL", "IF", "THEN", "ELSE", "ENDIF",
            "FOR", "TO", "STEP", "ENDFOR", "WHILE", "DO", "ENDWHILE",
            "WAIT", "SIGNAL", "SPEED", "ACCEL", "LMOVE", "JMOVE", "PRINT", "SIG",
            ".TRANS", ".REALS", ".STRINGS", ".INTEGER", ".POS", "GOTO", "CASE", "VALUE"
        )
    }

    // arquivos grandes são pesados para colorir, então há dois limites de tamanho:
    val isTooLargeForFullHighlight = textFieldValue.text.length > 100000
    val isMassiveFile = textFieldValue.text.length > 2000000 // mais de 2 MB

    // Aplica as cores ao texto SEM mudar o texto em si. Depende do tamanho:
    // - normal: colore tudo (palavras, números, comentários, busca);
    // - grande (mais de 100 mil caracteres): só pinta a busca;
    // - enorme (mais de 2 MB): não pinta nada, para não travar o celular.
    val visualTransformation = remember(keywords, searchQuery, isTooLargeForFullHighlight, isMassiveFile) {
        VisualTransformation { text: AnnotatedString ->
            val highlighted = if (isMassiveFile) {
                // arquivo enorme: sem cores, para poupar memória e processador
                AnnotatedString(text.text)
            } else if (isTooLargeForFullHighlight) {
                buildAnnotatedString {
                    append(text.text)
                    if (searchQuery.isNotEmpty()) {
                        var start = 0
                        while (true) {
                            start = text.text.indexOf(searchQuery, start, ignoreCase = true)
                            if (start == -1) break
                            addStyle(
                                style = SpanStyle(background = Color(0xFFEBC111), color = Color.Black),
                                start = start,
                                end = start + searchQuery.length
                            )
                            start += searchQuery.length
                        }
                    }
                }
            } else {
                highlightAsCode(text.text, keywords, searchQuery)
            }
            TransformedText(
                text = highlighted,
                offsetMapping = OffsetMapping.Identity
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalScroll(horizontalScrollState)
                .verticalScroll(verticalScrollState)
        ) {
            Row(modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max)) {
                // conta as quebras de linha uma vez só (rápido, mesmo em arquivo grande)
                val lineCount = remember(textFieldValue.text) {
                    var count = 0
                    for (char in textFieldValue.text) {
                        if (char == '\n') count++
                    }
                    count + 1
                }

                val lineNumbersText = remember(lineCount) {
                    if (lineCount > 50000) {
                        "Linhas:\n1 a $lineCount\n(Arquivo Grande)"
                    } else {
                        val sb = StringBuilder()
                        for (i in 1..lineCount) {
                            sb.append(i).append('\n')
                        }
                        sb.toString()
                    }
                }

                Text(
                    text = lineNumbersText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF858585),
                        textAlign = TextAlign.End
                    ),
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .defaultMinSize(minWidth = 40.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF252526))
                        .padding(top = 16.dp, start = 8.dp, end = 8.dp)
                )

                // campo de texto com o código
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 16.dp, start = 12.dp, end = 16.dp),
                    readOnly = !isEditing,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFFD4D4D4)
                    ),
                    cursorBrush = SolidColor(Color.White),
                    visualTransformation = visualTransformation,
                    decorationBox = { innerTextField ->
                        innerTextField()
                    }
                )
            }
        }
    }
}

/**
 * Colore o código AS lendo o texto UMA vez, da esquerda para a direita.
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
                char.isDigit() || (char == '-' && i + 1 < text.length && text[i+1].isDigit()) -> {
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
