# Guia de Telas e Implementações - MyRobots

Este documento é o **roteiro do app**: descreve o que cada módulo e cada tela faz hoje,
com os detalhes de comportamento (não só a lista de arquivos). A ideia é que, quando
alguém quiser planejar uma melhoria, escreva na seção do módulo/tela certa, em
"Pendências / Próximos passos". Assim o guia fica sempre valendo como norte do que
o app faz e do que falta fazer — atualize esta seção sempre que mudar o comportamento
de uma tela, e não só o código.

Todo o código tem comentários em português explicando o que cada classe e função faz.

## 0. Estrutura de módulos

O app é dividido em módulos Gradle. Cada módulo tem uma responsabilidade só, então dá
para melhorar uma parte sem mexer nas outras.

```
:app                     MainActivity, MyRobotsApp e o mapa de navegação (liga as telas)

:core:common             FileUtil (nomes de arquivo), AsProgramBlocks (blocos .PROGRAM) e LayoutOps (grade da cabine)
:core:model              Robot, Backup, QuickCommand, Manufacturer, ProjectLayout, ProjectEquipment
:core:database           Room: AppDatabase, os DAOs, as migrações e o schema exportado
:core:network            KawasakiTerminalManager (terminal TCP/telnet)
:core:data               RobotRepository (junta banco + arquivos)
:core:designsystem       Tema (cores, fontes, formas) + bibliotecas de Compose compartilhadas

:feature:splash          Tela de abertura
:feature:robots          Lista e cadastro de robôs, status do Wifi
:feature:backup          Histórico de backups (criar, importar, duplicar, exportar)
:feature:codeeditor      AsCodeViewer: ver/editar código AS
:feature:dashboard       Painel do robô: terminal, programas, variáveis, Data Bank
:feature:terminal        Terminal Geral (vários robôs) e comandos rápidos
:feature:project         Tela de Projeto: a cabine com os robôs e o editor do layout
```

### Regras de dependência (para manter tudo organizado)
- `:feature:*` pode usar `:core:*`, mas **uma feature nunca usa outra feature**. Quem liga uma
  tela na outra é o `:app` (na `MainActivity`).
- `:core:model` não depende de ninguém. `:core:database` e `:core:network` dependem só de `:core:model`.
- `:core:data` (repositório) junta database + network + common. Para dados (robôs, backups,
  comandos, arquivos), as telas falam só com o `RobotRepository`, nunca com os DAOs.
- A conversa ao vivo com o robô é a exceção: os ViewModels que usam o terminal (painel,
  Terminal Geral, comandos rápidos, robôs conectados) recebem o `KawasakiTerminalManager` direto
  do `:app`, pela factory. Lógica nova com várias etapas sobre o terminal (ex.: backup de vários
  robôs) deve ir para uma classe do `:core:data`, não para o ViewModel.
- Para uma parte nova e independente, crie um novo módulo `:feature:nome` (copie o `build.gradle.kts`
  de outra feature) e inclua em `settings.gradle.kts` e em `app/build.gradle.kts`.

### Como compilar
- No Android Studio: sincronize o Gradle e rode o app normalmente.
- Pelo terminal: `./gradlew assembleDebug` (precisa de internet na primeira vez).
- `minSdk` 29 (Android 10) desde a v1.2.

### Como testar
- Testes JVM (lógica AS, nomes de arquivo, validação de arquivo externo, deslocar pontos):
  `./gradlew testDebugUnitTest`. Ficam em `:core:common`, `:core:network` e `:feature:codeeditor`.
- Teste de migração do banco (precisa de celular ou emulador):
  `./gradlew :core:database:connectedDebugAndroidTest`.

---

## 1. `:core:model` — os dados que o app entende

- **`Robot`**: um robô cadastrado (nome, IP, porta, projeto/célula, fabricante, dados de
  login automático). `name` também define o nome da pasta do robô (ver seção 14). No banco,
  `loginPassword` fica **cifrada** (ver seção 14); o `RobotRepository` entrega sempre decifrada.
  `layoutRow`/`layoutCol` são a vaga do robô na cabine do projeto (começam em 0; `null` = fora
  do layout). Só a tela de Projeto mexe neles.
- **`ProjectLayout`**: tamanho da grade da cabine de um projeto (`rowCount` × `colCount`). O
  projeto é identificado pelo nome (`Robot.project`). Sem linha na tabela, vale o padrão 2×2.
- **`ProjectEquipment`** / **`EquipmentType`** (`CONVEYOR` = Transportador, `OTHER` = Outro, com
  nome obrigatório): faixas desenhadas entre as linhas da cabine. `position` = 0 acima da linha 1
  ... `rowCount` abaixo da última; `flowDirection` 1 → / -1 ← / 0 sem sentido; `sortOrder` ordena
  vários na mesma faixa.
- **`Manufacturer`**: `KAWASAKI` (único com suporte completo hoje: terminal, backups e
  comandos rápidos), `FANUC`, `ABB`, `UNIVERSAL_ROBOTS` (cadastráveis, mas sem função própria ainda).
- **`Backup`** / **`BackupSummary`**: um backup é o texto completo (`content`) de um arquivo
  `.as`, mais contagens (`programsCount`, `variablesCount`, `framesCount`) e `memoryUsage`
  calculados ao salvar. `BackupSummary` é a versão sem `content`, usada nas listas para não
  estourar memória com arquivos grandes. `robotId = -1` marca um backup temporário (arquivo
  aberto de fora do app, sem robô dono).
- **`QuickCommand`**: um botão de comando pronto (`label` + `command`). O `command` aceita
  `[ROBOT]` (nome do robô) e `[DATA]` (data/hora `_aaaammdd_hhmm`), trocados na hora de enviar.
  Pode pertencer a um robô específico (`robotId`) ou a um fabricante inteiro (`manufacturer`,
  com `robotId` negativo fixo por fabricante — ver `RobotRepository.getQuickCommandsByManufacturer`).
- **`HeartbeatState`**: `ALIVE`/`STALE`/`DISCONNECTED`, o pulso da conexão de um robô (calculado
  pelo `KawasakiTerminalManager`, ver seção 3). Fica aqui para a bolinha de status
  (`HeartbeatDot`, `:core:designsystem`) poder ser usada por qualquer tela.
- **`RobotCommandLibrary`**: biblioteca de comandos por fabricante (existe no módulo, ver o
  arquivo para o conteúdo atual).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 2. `:core:database` — persistência local (Room)

`AppDatabase` (versão 5) + os DAOs `RobotDao`, `BackupDao`, `QuickCommandDao` e `ProjectDao`.
Guarda robôs, backups, comandos rápidos e o layout da cabine de cada projeto
(`project_layouts`, `project_equipment`). O `ProjectDao` grava a edição do layout numa transação
(`saveLayout`), renomeia o projeto em todas as tabelas (`renameProject`) e apaga o layout de um
projeto que ficou sem robôs (`deleteLayoutIfEmpty`).

- **Schema exportado:** o plugin Gradle do Room grava o schema de cada versão em
  `core/database/schemas/my.robots.core.database.AppDatabase/<versão>.json` (versionado no git).
- **Migrações escritas à mão:** o banco **nunca é apagado**. `MyRobotsApp` abre o banco com
  `addMigrations(*ALL_MIGRATIONS)` (lista em `DatabaseMigrations.kt`). Subir
  a versão sem escrever a migração faz o app falhar ao abrir, em vez de apagar os dados.
- **Para mudar uma entidade:** subir a versão no `AppDatabase`, compilar (gera o `.json` novo),
  escrever o `Migration(antiga, nova)` comparando os dois `.json`, incluir em `ALL_MIGRATIONS` e
  acrescentar o caso no `MigrationTest`.
- **Migrações:** `MIGRATION_4_5` (v1.2, tela de Projeto) acrescenta `layoutRow`/`layoutCol` em
  `robots` (robôs antigos ficam fora do layout) e cria `project_layouts` e `project_equipment`.
- **Teste de migração:** `MigrationTest` (androidTest, `MigrationTestHelper`) cria o banco v4
  com dados e confere que eles continuam lá na versão atual, e valida a `MIGRATION_4_5` contra o
  `5.json` com `runMigrationsAndValidate`. Roda com o celular ligado:
  `.\gradlew.bat :core:database:connectedDebugAndroidTest` (passou em 02/10/2026 num Galaxy S25).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 3. `:core:network` — conversa com o robô pela rede

- **`KawasakiTerminalManager`**: fala com os controladores Kawasaki por telnet/TCP. Uma única
  instância vive o app inteiro (criada em `MyRobotsApp`), então a conexão de um robô continua
  aberta mesmo trocando de tela. Faz:
  - Conectar/desconectar por robô (uma conexão cada), com histórico de até 1000 linhas por robô.
  - Login automático: observa o texto do robô por "login:"/"user:" e "password:" e digita
    sozinho, letra por letra (o controlador perde caractere se receber tudo de uma vez).
  - Entende o protocolo de transferência de arquivo do controlador: quando o robô manda um
    `SAVE`, grava o arquivo na pasta do robô; quando pede um `LOAD`, envia o arquivo da pasta
    do robô em blocos de 512 bytes. Os arquivos passam pela `RobotFileStore` (seção 14).
    **Atenção:** um bloco do protocolo só é interpretado se chegar inteiro no mesmo pacote de
    rede — se vier partido em dois pacotes, é descartado.
  - **O nome do arquivo que o robô manda é validado** (`TransferFileNames.safeName`: só
    `[A-Za-z0-9_.-]`, sem `..`, até 100 caracteres). Ele vem da rede: sem essa conferência, um
    aparelho respondendo no IP do robô podia pedir um `LOAD` de
    `../../data/data/my.robots/databases/...` e receber o banco do app. Nome recusado não grava
    nem envia nada e aparece como `>>> SAVE recusado`/`>>> LOAD recusado` no terminal.
  - `sendChar`/`sendCommand`: enviam tecla a tecla (usado enquanto o usuário digita no terminal
    real-time) ou um comando inteiro com Enter.
  - `deleteProgram`/`deleteVariable`: montam o comando `DELETE` certo (com `/P`, `/D`, `/L`,
    `/R`, `/S`, `/INT` conforme o caso).
  - **Heartbeat (`HeartbeatState`)**: `isConnected` sozinho só diz que o socket TCP está
    aberto, não que o robô está respondendo. Por isso, a cada robô conectado roda um
    `heartbeatLoop` que reavalia o estado a cada 3s comparando `lastActivityAt` (atualizado
    em `appendLog` sempre que chega algo de verdade do robô) com o tempo atual: `ALIVE` se
    chegou algo nos últimos 8s, `STALE` se está conectado mas quieto. **O heartbeat é
    puramente passivo — não escreve nada no socket.** Uma primeira versão mandava um NOP de
    telnet (`0xFF 0xF1`) para sondar a conexão ativamente, mas o controlador Kawasaki lê o
    canal caractere por caractere (só processa a linha no Enter) e não reconhece esse NOP
    como protocolo: o byte `0xF1` aparecia literalmente como "ñ" misturado no meio do comando
    que o usuário estava digitando. Uma queda de conexão de verdade continua sendo detectada
    pelo `readLoop` (EOF/erro de leitura), só que sem a checagem ativa a cada 3s. Consumido
    por `getHeartbeat(robotId)`.
- Não existe API HTTP: o controlador não tem servidor HTTP, e todo backup (SAVE) e envio
  (LOAD) passa pelo terminal. A antiga `RobotApiService` (Retrofit, apontando para
  `http://localhost/`) foi removida na v1.2.

**Pendências / Próximos passos:** tratar blocos de handshake partidos entre pacotes de rede
(Fase 2.0 de `docs/PLANO_V1_2.md`).

---

## 4. `:core:data` — `RobotRepository`

Junta banco (Room) + a pasta dos arquivos (`RobotFilesStorage`, seção 14). É a única porta de
entrada de dados para as telas — nenhuma feature fala direto com o DAO nem com a pasta. (O
terminal ao vivo fica fora: ver as regras de dependência na seção 0.)

**O banco é a fonte da verdade:** o texto completo de cada backup fica no banco; o arquivo na
pasta é uma cópia para o terminal (`LOAD`) e para o usuário (PC, compartilhar).

Principais responsabilidades:
- CRUD de robôs, comandos rápidos e backups.
- `insertBackup`: recalcula `programsCount`/`variablesCount` (`AsBackupStats`) e `memoryUsage`
  antes de gravar.
- `saveBackupToFile`/`saveFileToRobotFolder`: gravam o texto na pasta do robô e devolvem
  `true`/`false` (o erro não é mais engolido).
- `deleteBackupAndFile`: apaga o backup do banco e o arquivo da pasta.
- `syncRobotFolder`/`syncAllRobotFolders`: trazem para o banco os `.as` da pasta que ainda não
  estão nele ("Sinc: <arquivo>"). **Só importam, nunca apagam backup do banco** — até a v1.1
  um arquivo ausente apagava o backup, o que com a pasta nova (que pode não enxergar arquivos
  antigos depois de reinstalar, ou perder a permissão) apagaria tudo.
- `restoreMissingFiles`: regrava na pasta os backups do banco cujo arquivo não está lá (usado na
  migração da v1.2 e ao trocar de pasta).
- `storageLocation`, `useStorageFolder`, `useDefaultStorage`, `filesFolderUri`: a janela "Pasta
  dos arquivos" (seção 7) e o botão "Arquivos" do terminal (seção 10).
- Não há nenhum dado simulado: `performBackup` (que criava um backup de exemplo quando a API
  de teste falhava), `getRobotLogs` e `getRobotStatus` foram removidos na v1.2.

**Pendências / Próximos passos:** um `SAVE` feito pelo terminal grava o arquivo na pasta, mas só
vira backup no banco quando a lista de robôs abre (sincronização do `RobotViewModel`) ou no
ícone de sincronizar do histórico. Registrar na hora é a Fase 2.0 de `docs/PLANO_V1_2.md`.

---

## 5. `:core:designsystem` e `:core:common`

- **`:core:designsystem`**: `Theme.kt`, `Color.kt`, `Shape.kt`, `Type.kt` — o tema visual
  (`MyRobotsTheme`) usado em todo o app — e `HeartbeatIndicator.kt` (`HeartbeatDot` e o texto
  de cada `HeartbeatState`). Depende de `:core:model`.
- **`:core:common`**:
  - `FileUtil`: resolve o nome de um arquivo a partir de uma `Uri` do Android, limpa nomes de
    arquivo e separa as seções AS conhecidas (`sanitizeAsContent`).
  - `ascode.AsProgramBlocks`: lê e troca blocos `.PROGRAM nome(...)` ... `.END` no texto de um
    backup (`extract`, `extractMany`, `replace`, `remove`, `list`, `renameHeader`). **Sempre
    compara o nome exato**: antes, `startsWith(".PROGRAM pg1")` também pegava o `pg10`, e salvar
    o `pg1` apagava os dois. Toda tela que mexe em programa deve usar este objeto. Também lê
    data/hora e comentário do cabeçalho (`parseHeader`). Tem testes JVM.
  - `ascode.AsControllerLogs`: leitura dos logs `.ERRLOG`, `.OPELOG` e `.PGM_EDT_LOG` do backup
    (as classes `RobotLogEntry`/`RobotErrorLog*` moram aqui). Testes de caracterização.
  - `ascode.AsBackupStats.count`: contagem de programas e variáveis de um backup.
  - `ExternalAsFile`: leitura segura de arquivo vindo de fora do app (ver seção 14).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 6. `:feature:splash` — Tela de Abertura

**Arquivo:** `SplashScreen.kt`

Uma cabeça de robô desenhada em `Canvas` que cresce (com efeito de mola) e pisca os dois
olhos duas vezes. Depois de ~2,5 segundos chama `onAnimationFinished`, e o app navega para
`robot_list` removendo a splash do histórico de voltar (`popUpTo("splash") { inclusive = true }`).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 7. `:feature:robots` — Lista e Cadastro de Robôs

**Arquivos:** `RobotListScreen.kt`, `RobotDialog.kt`, `RobotViewModel.kt`, `ConnectedRobotsSheet.kt`, `ConnectedRobotsViewModel.kt`

### Lista de robôs (`RobotListScreen`)
- Tela inicial de verdade do app (depois da splash). Agrupa os robôs em
  **Fabricante > Projeto > Robô**, com cada nível podendo ser expandido/recolhido.
- Barra do topo: robôs conectados (ícone de hub — ver abaixo), ordenar A-Z (liga/desliga
  ordenação alfabética nos três níveis), ícone de Wifi (mostra SSID e IP do celular,
  atualizado a cada 3 segundos, para conferir se está na mesma rede do robô) e engrenagem
  (abre um menu com o status do Wifi, atalho para "Configurar Wifi", que leva para as
  configurações de Wifi **do próprio Android**, e **"Pasta dos arquivos"** — ver abaixo).
- Botão "+" abre `RobotDialog` para cadastrar um robô novo.
- Cada robô mostra nome e `ip:porta`, com botões de terminal (abre o dashboard direto na
  seção Terminal), editar e excluir (com confirmação).
- Cada projeto tem um ícone (grade) que abre a **tela de Projeto** (seção 15), com a cabine
  e, no menu e no fim da tela, o **Terminal Geral** (seção 11).
- Tocar num robô abre o **painel** dele, já com o backup mais recente
  (`robot_dashboard/{id}/-1`).

### Janela "Pasta dos arquivos" (`StorageFolderDialog`)
- Mostra onde os backups estão sendo gravados: **Documentos/MyRobots** (padrão) ou uma pasta
  escolhida pelo usuário. Avisa quando a pasta escolhida sumiu ou perdeu a permissão (os
  arquivos vão para a pasta padrão enquanto isso).
- "Escolher pasta" abre o seletor de pastas do Android. Ao escolher, o app grava na pasta os
  backups que faltam e importa os `.as` que já estavam lá (escolher a pasta `/MyRobots` antiga
  traz de volta os arquivos da v1.1). "Usar a pasta padrão" volta para Documentos/MyRobots.

### Cadastro/edição (`RobotDialog`)
- Campos: fabricante (dropdown), projeto (texto livre com sugestões dos projetos já
  existentes), nome (só letras/números/`_`, pois vira nome de pasta), IP, porta (padrão 23,
  a porta padrão do telnet) e bloco de login automático (usuário/senha, opcional).
- Botão "Confirmar" só liga com nome e IP preenchidos. Porta inválida vira 23; projeto vazio
  vira "Padrão".

### ViewModel (`RobotViewModel`)
- Expõe a lista de robôs (`StateFlow`) direto do `RobotRepository`.
- **Sincronização automática ao abrir a lista:** `RobotRepository.syncAllRobotFolders` — arquivo
  `.as` que está na pasta do robô mas não no banco vira um backup novo ("Sinc: <arquivo>").
  Backup cujo arquivo sumiu da pasta **continua no banco**.
- Janela "Pasta dos arquivos": `storageLocation`, `chooseStorageFolder`, `useDefaultStorage`.

### Popup "Robôs Conectados" (`ConnectedRobotsSheet` + `ConnectedRobotsViewModel`)
- Aberto pelo ícone de hub na barra do topo da lista de robôs. Um `ModalBottomSheet` agrupa
  todos os robôs cadastrados **por Projeto** (sem o nível de Fabricante, para focar em "quem
  está online agora").
- Cada linha mostra: bolinha de heartbeat (`HeartbeatDot`; ver `HeartbeatState`), nome,
  `ip:porta`, o texto do status ("Ativo"/"Sem resposta"/"Desconectado") e um botão
  Conectar/Desconectar — dá para conectar em quantos robôs quiser ao mesmo tempo, cada um
  com sua própria conexão TCP (mesmo mecanismo do Terminal Geral).
- Cada cabeçalho de projeto tem um atalho "Conectar Todos"/"Desconectar Todos" que liga ou
  desliga de uma vez todos os robôs daquele projeto.
- A bolinha de heartbeat pulsa (anima opacidade) só quando `ALIVE`; fica parada em amarelo
  (`STALE`) ou cinza (`DISCONNECTED`) — evita animação constante quando não há nada de novo.
- `ConnectedRobotsViewModel` observa `getConnectionStatus`/`getHeartbeat` do
  `KawasakiTerminalManager` para cada robô da lista (um coletor por robô, iniciado uma vez só
  por id para não duplicar assinaturas).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 8. `:feature:backup` — Histórico de Backups

**Arquivos:** `BackupHistoryScreen.kt`, `BackupViewModel.kt`, `BackupViewModelFactory.kt`

- Lista os backups de um robô, com busca por texto e ordenação por data (crescente/decrescente).
- Cada item tem: ver código (abre no `AsCodeViewer`), duplicar (pede um novo nome), compartilhar
  e excluir (com confirmação; também apaga o arquivo físico).
- **Compartilhar** abre um menu com duas opções: exportar para uma pasta escolhida pelo usuário
  (`ActivityResultContracts.CreateDocument`) ou compartilhar via outro app (WhatsApp, e-mail
  etc.), usando `FileProvider` para copiar o arquivo para uma pasta de cache temporária antes
  de enviar.
- Botão "+" abre um menu com duas formas de criar backup: **baixar do robô conectado** (navega
  para o dashboard/terminal) ou **importar** um arquivo `.as` já existente no celular
  (`ActivityResultContracts.OpenDocument`, lido por `ExternalAsFile`: até 20 MB e só texto;
  recusa com mensagem).
- Ícone do robô (`SmartToy`) traz na hora os `.as` novos da pasta do robô (ex.: um `SAVE` feito
  pelo terminal). Não apaga nada.

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 9. `:feature:codeeditor` — `AsCodeViewer`

**Arquivo:** `AsCodeViewer.kt`

- Editor de texto completo com numeração de linha e destaque de sintaxe da linguagem AS
  (comentários em verde, textos entre aspas em laranja, seções `.PROGRAM`/`.END`/`.TRANS`
  em amarelo, comandos de movimento em azul, sinais/esperas em verde-água, outras
  palavras-chave em roxo, números em verde-claro).
- Barra do topo: lupa (busca com destaque amarelo no texto), lápis (liga/desliga o modo de
  edição) e disquete (chama `onSave` com as linhas juntas por `\n` — o padrão não faz nada,
  então uma tela somente-leitura simplesmente não grava).
- **O texto nunca é editável direto na área de código** (não é mais um campo de texto livre
  — foi assim numa versão anterior, mas digitar dentro de um arquivo gigante rolando na tela
  do celular era fácil de errar sem querer). Cada linha é uma linha de uma `LazyColumn`
  (`CodeLinesList`/`CodeLineRow`), colorida com `highlightAsCode` só para as linhas visíveis
  na tela — por isso funciona liso mesmo em arquivo com dezenas de milhares de linhas, sem
  precisar degradar o destaque de sintaxe como a versão antiga fazia.
- **Modo de edição** (ícone de lápis): cada linha ganha uma caixa de seleção (pode marcar
  mais de uma, em qualquer ordem) e aparece uma barra de ações embaixo da barra do topo
  (`LineActionsToolbar`), agindo sobre o que estiver marcado:
  - **Copiar** (1+ marcadas): manda o texto das linhas para a área de transferência.
  - **Colar** (exatamente 1 marcada): insere o texto da área de transferência acima da
    linha marcada — se o que foi copiado tiver várias linhas, todas entram de uma vez.
  - **Alterar** (exatamente 1 marcada): abre `LineEditDialog` só com o texto daquela linha,
    para editar isolado, sem risco de mexer em outra parte do arquivo.
  - **Inserir** (exatamente 1 marcada): abre a mesma janela vazia; o texto digitado vira uma
    linha nova acima da marcada, empurrando o resto do arquivo para baixo.
  - **Excluir** (1+ marcadas): remove as linhas marcadas.
- É usado em quatro rotas diferentes no `:app` (ver seção 13): código completo, um programa
  só, só as variáveis, e arquivo aberto de fora do app (somente leitura).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 10. `:feature:dashboard` — Painel do Robô

**Arquivos:** `RobotDashboardScreen.kt`, `RobotDashboardViewModel.kt`, `RobotDashboardViewModelFactory.kt`

Tela principal de UM robô, organizada em uma "home" (`DashboardHome`) e seções alternadas por
`DashboardFeature`:

- **Home:** de cima para baixo:
  - **Cartão do robô** (`RobotInfoCard.kt`): faixa baixa com o desenho em linhas de um robô
    de pintura, estilo tela de controle (só ilustração, não mostra a pose real), o modelo, a
    série, o nome, a quantidade de eixos e o selo do status. Embaixo, os dados lidos do backup
    SAVE/FULL por `AsRobotInfo` (`:core:common`): horímetro (`HOUR_MTR`, ou `CONT_TIM`; é o
    tempo com o controlador ligado), em operação (`SERV_TIM`, servo ligado), vezes que o motor
    ligou (`MTON_CNT`), emergências (`ESTP_CNT`), freio acionado (`BRKE_CNT`), eixos e série
    (`ZROBOT.TYPE`), versão do AS (cabeçalho `.*=== AS GROUP ===`) e IP do controlador
    (`.NETCONF2`). Backup sem esses dados (só programas) mostra um aviso para fazer SAVE/FULL.
    O link **"Por eixo"**, abaixo das horas em operação, abre `AxisDetailSheet.kt` com cada
    servo (JT1, JT2...): horas em movimento (`MOVE_TJT`) com barra proporcional ao eixo mais
    usado, horas nos últimos 30 dias (diferença entre os backups do período,
    `RobotUsageHistory.axisMoveHoursLast`), deslocamento acumulado (`DIST_DJT`, na unidade do
    controlador), menor e maior temperatura do encoder (`.ENCTEMPLOG`, com a data) e os
    alarmes dos 7 dias que citam o eixo ("Jt 5 motor overloaded"; os de rotina ficam de fora).
    **Memória de programas**, no fim do cartão: o backup não traz essa informação, então o
    `ControllerMemoryReader` (`:core:data`) manda o comando `FREE` pelo terminal e lê a
    resposta (`AsFreeMemory`, em `:core:common`): "Total memory, 8192 KBbytes." e "Available
    memory size 8175 KBbytes.( 99 %)". Lê sozinho a cada conexão, depois do login (espera o
    prompt `>`), e no botão "Ler agora". A última leitura fica nas SharedPreferences
    `controller_memory` e aparece mesmo sem conexão, com a data. Abaixo de 10% livre, a barra
    fica amarela e o texto avisa.
  - **Status geral** (`RobotHealth` + `AsErrorSeverity`): selo OK / ATENÇÃO · n / SEM DADOS.
    Os alarmes do `.ERRLOG` dos 7 dias antes do backup são classificados em **rotina**
    (porta da cabine, motor desligado, falta de energia...), **programa/movimento** (fora de
    alcance, singularidade...) e **graves** (encoder, servo, sobrecarga, temperatura, purga,
    códigos `D` do hardware...). Só os graves e um backup com mais de 30 dias ligam o
    ATENÇÃO; `n` é a quantidade desses itens. Tocar no selo abre a lista: o que precisa de
    atenção, os de programa/movimento e os de rotina, agrupados por código, com quantas
    vezes e a última ocorrência (data convertida de `aa/mm/dd` para `dd/mm/aaaa`), e um
    botão para o log de erros. As listas de classificação ficam em `AsErrorSeverity`.
  - **Uso do robô** (`RobotUsageCard.kt` + `RobotUsageHistory`): gráfico de barras com as
    horas em operação por dia (30 dias, 90 dias ou tudo) e as médias de horas em operação,
    horas ligado e vezes que o motor ligou. Vem da diferença dos contadores entre um backup
    e outro: o `BackupDao.getUsageSnippets` recorta no SQLite só o trecho `.OPE_INFO1` de cada
    backup, sem carregar o texto inteiro. A hora de cada backup vem do nome do arquivo
    (`R10_20260919_0810.as`) quando ele segue o padrão do app. Entre backups com mais de 2
    dias de intervalo, o valor é a média do intervalo, e a barra aparece apagada. Tocar numa
    barra mostra o dia. Precisa de pelo menos dois backups SAVE/FULL. Só entram backups do
    mesmo controlador (série do `OPEINFO`) do backup mais novo, e um intervalo com mais horas
    do que o tempo que passou (por exemplo, um backup do K-ROSET com a mesma série) é descartado. Programas executados
    por dia não aparecem: o `.EXECPGLOG` do controlador guarda só os últimos dias.
  - **Backup analisado**: nome, data, total de linhas, aviso quando não é o mais recente e o
    botão "Histórico de backups".
  - **Atalhos em grade**: Programas, Variáveis, Data Bank e os três logs do controlador
    (Erros, Operação, Edição), cada um com a contagem de itens.
  - O arquivo completo (Código AS, abre o `AsCodeViewer` em tela cheia, fora do dashboard)
    fica no menu "⋮" da barra do topo, item "Ver arquivo completo".
- **Terminal (`DashboardFeature.Terminal`)**: terminal de verdade — caixa preta com texto verde (o que o usuário
  digitou aparece em azul-claro). Cada tecla digitada é enviada ao robô na hora (como um
  terminal real); apagar manda backspace; setas ⬆⬇ mandam histórico de comando do robô; o
  raio abre a biblioteca de comandos rápidos (`:feature:terminal`); um botão abre o gerenciador
  de arquivos do Android na pasta dos arquivos atual (Documentos/MyRobots ou a escolhida; se não
  conseguir abrir, cai no histórico de backups); botão Conectar/Desconectar muda de cor
  conforme o estado.
- **Programas**: lista os programas do backup atual com caixa de seleção em cada linha; cada
  item mostra, além do nome, o comentário de descrição, o tamanho, a quantidade de linhas
  (do `.PROGRAM` ao `.END`) e a data/hora de modificação lidos do próprio cabeçalho do
  programa (formato real:
  `.PROGRAM nome(params)@dd/mm/aa hh:mm#N;comentário` — `PROGRAM_HEADER_REGEX` no
  `RobotDashboardViewModel`; qualquer uma dessas partes pode faltar em backups mais antigos).
  Tocar na linha (fora da caixa) ou no ícone de olho abre o programa isolado no editor
  (`program_viewer`). Enviar,
  compartilhar e excluir **não ficam mais na linha — ficam na barra do topo** e operam sobre
  todos os programas marcados de uma vez (desabilitados sem nenhum marcado); um botão na barra
  do topo alterna "Selecionar Todos"/"Desmarcar Todos". Enviar empacota os blocos
  `.PROGRAM...END` de todos os selecionados num arquivo só (`packProgramsContent` no
  ViewModel); compartilhar usa o mesmo pacote para abrir o menu de compartilhar do Android
  (`FileProvider`, igual ao histórico de backups); excluir remove todos numa passada só
  (`deletePrograms`), para não perder uma exclusão por causa de outra sendo salva ao mesmo
  tempo. "Duplicar" continua por linha, pois é uma ação de um programa só (copia o bloco com
  `AsProgramBlocks` e troca só o nome no cabeçalho, mantendo parâmetros, data e comentário).
- **Variáveis**: tabela com nome fixo à esquerda e valores `X, Y, Z, O, A, T, JT7, JT8`
  rolando para o lado — variáveis do tipo `FRAME` (posição) mostram um valor por coluna, as
  outras mostram o valor inteiro numa célula só. Nomes que começam com `!` aparecem em
  amarelo-escuro (indicando alguma marcação especial do robô). Toolbar: criar, ordenar,
  editar, duplicar, enviar, excluir — todas exigem uma variável selecionada, exceto criar.
- **Data Bank**: tabela parecida (linhas da seção `.sprdb`), mas com checkbox por linha para
  selecionar várias de uma vez e enviar em lote (`onUploadSelected`). Colunas fixas: número e
  comentário; roláveis: `FRATE, PATTERN, ATOMIZE, HVOLT, SPEED, JSPEED`.
- **Logs do controlador (Erros/Operação/Edição)**: três seções que só existem quando o
  backup foi feito com `SAVE/FULL` no robô — sem isso, aparecem zerados (contagem 0 e uma
  mensagem explicando o motivo). Lidos direto do backup por `parseLogSection` (função
  privada em `RobotDashboardViewModel.kt`), que reconhece o formato `N - [...]` de cada
  entrada e junta linhas de detalhe até a próxima entrada ou até a próxima seção do backup
  (essas seções não têm `.END` próprio, ao contrário de `.PROGRAM`/`.sprdb`):
  - **`.ERRLOG`** (Log de Erros): é o único dos três com parser estruturado
    (`RobotErrorLogEntry`, `parseErrorLog`/`buildErrorLogEntry`), porque cada entrada tem
    várias linhas com informação bem diferente (código/mensagem do erro, sinal/velocidade/
    modo, as `OPERATIONx` daquele momento, o status de cada robô/PC do sistema e as poses
    Current/Command/End). A lista (`ErrorLogPanel`) mostra só código + mensagem + data/hora
    por linha (`ErrorLogSummaryCard`); tocar abre `ErrorLogDetailDialog` em tela cheia,
    dividido em seções (Estado no Momento do Erro, Programas em Execução, Sequência de
    Operações, Poses) com um "Ver Texto Original" reaproveitando o `LogEntryCard` genérico,
    para o caso de algum formato de erro não bater com o parser.
  - **`.OPELOG`** (Log de Operação): uma linha por evento (conectar, `SAVE`, `RESET`, troca de
    step etc.), com a origem entre colchetes (`TP`, `AUX1`...). Continua no `LogPanel`
    genérico (lista simples com o texto cru de cada linha), pois já é compacto por natureza.
  - **`.PGM_EDT_LOG`** (Log de Edição): uma linha por edição de programa feita no ensino
    (`Step addition`, `Step deletion`...), com o nome do programa e o step afetado. Também no
    `LogPanel` genérico, pelo mesmo motivo do `.OPELOG`.
  - **Busca:** as três telas de log têm lupa na barra do topo (mesmo padrão do `AsCodeViewer`
    — troca o título por um campo de texto). Filtra por `entry.raw.contains(query)`, ou seja,
    casa com qualquer parte do texto da entrada (no `.ERRLOG` isso inclui código, mensagem,
    operações e poses, já que tudo está junto em `raw`). Sem resultado mostra uma mensagem
    diferente conforme o motivo: log vazio (sem `SAVE/FULL`) ou busca sem resultado.
- **Enviar para outro robô:** ao enviar um programa, variável ou linhas de Data Bank,
  `RobotSelectionDialog` pergunta o robô de destino. Se o destino for o próprio robô aberto,
  a tela muda para a seção Terminal; se for outro robô, `onNavigateToRobot` navega para o
  dashboard dele (mantendo `robot_list` no topo da pilha de navegação).

**Pendências / Próximos passos:** "Compartilhar programa" ainda não está implementado
(`onShare = { /* ainda não implementado */ }` em `ProgramsPanel`).

---

## 11. `:feature:terminal` — Terminal Geral e Comandos Rápidos

**Arquivos:** `MultiRobotTerminalScreen.kt`, `MultiRobotTerminalViewModel.kt`, `QuickCommandScreen.kt`, `QuickCommandViewModel.kt`

### Terminal Geral (`MultiRobotTerminalScreen`)
- Fala com **todos os robôs de um projeto ao mesmo tempo**. O botão "Conectar" tenta ligar em
  todos e abre uma janela mostrando o andamento robô por robô (ícone verde quando conecta).
  "Desconectar" desliga todos, mas mantém o histórico de cada terminal individual.
- O histórico mostrado na tela é só o que o usuário enviou (`> comando`), diferente do
  terminal do painel do robô que mostra a resposta completa — aqui é broadcast, não uma
  sessão interativa por robô.
- Comando digitado (ou comando rápido escolhido no botão do raio) é enviado a **todos os
  robôs conectados** do projeto de uma vez, com `[ROBOT]`/`[DATA]` trocados individualmente
  por robô ao usar comando rápido.

### Comandos Rápidos (`QuickCommandScreen`)
- Biblioteca de comandos por fabricante. Tocar num comando envia ao robô e volta
  automaticamente para o terminal. Cada comando tem editar/excluir; botão "+" cria um novo
  (dica na própria janela sobre `[ROBOT]`/`[DATA]`).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 12. `:feature:settings` — removido

Removido na v1.2: `SettingsScreen` e `WifiSettingsScreen` nunca foram ligadas à navegação, e o
Wifi é resolvido abrindo as configurações do próprio Android (ver seção 7). O número da seção
fica reservado para não mudar as referências às seções seguintes.

---

## 13. `:app` — Navegação e ligação das peças

**Arquivos:** `MainActivity.kt`, `MyRobotsApp.kt`

- **`MyRobotsApp`** (roda uma vez, antes de qualquer tela): aumenta o limite do `CursorWindow`
  (backups FULL grandes), abre o banco Room com as migrações (`ALL_MIGRATIONS`), cria a
  `RobotFilesStorage`, o `RobotRepository` e o `KawasakiTerminalManager` (vivem o app inteiro)
  e, na primeira abertura da v1.2, regrava na pasta nova os backups do banco
  (`migrateFilesToNewFolderOnce`, ver seção 14).
- **`MainActivity`**: **não pede permissão de armazenamento** (desde a v1.2). Trata arquivos
  `.as`/`.pg` abertos de fora do app (`handleIntent`, ação VER/EDITAR — o texto fica só na
  memória e abre no editor; vira backup se o usuário escolher um robô para salvar). A leitura
  passa por `ExternalAsFile` (seção 14); o pedido é guardado num estado (`incomingIntent`)
  preenchido no `onCreate` e no `onNewIntent`, então um segundo arquivo aberto com o app já
  aberto também é tratado, e girar a tela não reabre o arquivo. Define o mapa de rotas do
  `NavHost`:

| Rota | Tela | Observação |
|---|---|---|
| `splash` | `SplashScreen` | início; some do histórico ao terminar |
| `robot_list` | `RobotListScreen` | lista de robôs; tocar no robô → `robot_dashboard/{id}/-1` |
| `project/{projectName}` | `ProjectScreen` | cabine do projeto; segurar um robô → `robot_dashboard/{id}/-1`; renomear troca a rota pelo nome novo |
| `multi_terminal/{projectName}` | `MultiRobotTerminalScreen` | terminal de todos os robôs do projeto (aberto pela tela de Projeto) |
| `quick_commands/{manufacturer}/{robotId}` | `QuickCommandScreen` | biblioteca de comandos |
| `backup_list/{robotId}` | `BackupHistoryScreen` | histórico de backups do robô |
| `robot_dashboard/{robotId}/{backupId}?feature={feature}` | `RobotDashboardScreen` | `backupId = -1` usa o backup mais recente; `feature` abre direto uma seção (ex.: `?feature=Terminal`) |
| `program_viewer/{backupId}/{programName}` | `AsCodeViewer` | extrai só o bloco `.PROGRAM ... .END` daquele nome exato (`AsProgramBlocks`), e ao salvar troca só esse bloco |
| `variable_viewer/{backupId}` | `AsCodeViewer` | junta as seções `.TRANS`/`.REALS`/`.STRINGS` do backup, somente leitura |
| `code_viewer/{backupId}` | `AsCodeViewer` | backup inteiro, com salvar |
| `external_viewer/{backupId}` | `AsCodeViewer` | arquivo importado de fora do app, somente leitura |

**Pendências / Próximos passos:** as mudanças de navegação da v1.2 (tocar no robô abre o
painel, tela de Projeto) estão em `docs/PLANO_V1_2.md`.

---

## 14. Arquivos e permissões

**Onde ficam os arquivos (desde a v1.2).** O app **não usa mais "acesso a todos os arquivos"**
(`MANAGE_EXTERNAL_STORAGE`, recusado pela Play para este tipo de app) nem
`READ/WRITE_EXTERNAL_STORAGE`. As únicas permissões são de rede (`INTERNET`,
`ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`).

- `RobotFileStore` (interface em `:core:network`, para o terminal poder usar): listar, ler,
  gravar e apagar os `.as` de um robô. `robotDirName(nome)` é a única regra do nome da pasta do
  robô (minúsculo; o que não for letra, número ou `_` vira `_`). Só aceita nomes de arquivo
  aprovados por `TransferFileNames.safeName`.
- `MediaStoreRobotFileStore` (`:core:data`): pasta padrão **Documentos/MyRobots/<robô>/**. Não
  precisa de permissão, mas o MediaStore **só enxerga os arquivos que o próprio app gravou**:
  um `.as` copiado pelo PC não aparece, e depois de desinstalar/reinstalar o app perde a posse
  dos arquivos antigos (o banco continua com tudo).
- `SafRobotFileStore` (`:core:data`): pasta escolhida pelo usuário no seletor do Android
  (Storage Access Framework), com a mesma estrutura `<pasta>/<robô>/`. Enxerga tudo o que está
  na pasta. A permissão fica guardada (`takePersistableUriPermission`).
- `RobotFilesStorage` (`:core:data`): lembra a escolha (preferência `storage`), usa a pasta
  escolhida enquanto ela estiver disponível e cai na padrão se ela sumir.
- Arquivos gravados com o tipo `application/octet-stream`, para o sistema não trocar a extensão
  `.as` (ex.: `.as.txt`).

**Migração da v1.1.** A pasta `/MyRobots` antiga deixa de ser acessível. Na primeira abertura, o
`MyRobotsApp` regrava cada backup do banco (que tem o texto completo) na pasta nova. Arquivos que
estavam só na pasta antiga e nunca entraram no banco continuam no disco: escolher `/MyRobots` em
"Pasta dos arquivos" os importa.

**Arquivos vindos de fora (`ExternalAsFile`, `:core:common`).** Usado pelo "abrir com" de outro
app e pela importação do histórico:
- só `content://` (um `file://` de outro app poderia apontar para os arquivos privados do app);
- até 20 MB (confere o tamanho declarado e lê com limite, sem carregar o resto na memória);
- só texto (recusa byte nulo);
- no "abrir com", só `.as`/`.pg`.
Recusas aparecem para o usuário com o motivo. O filtro do manifesto aceita só `content://`, sem
`BROWSABLE`.

**Compartilhar.** Os arquivos compartilhados são copiados para `cacheDir/shared_backups`, a única
pasta liberada no `FileProvider` (`file_paths.xml`). O nome do arquivo é limpo por
`FileUtil.sanitizeFileName`, que também limpa a extensão.

**Codificação.** Os arquivos são lidos e gravados em UTF-8 (como na v1.1), num lugar só
(`RobotRepository.encodeAsText/decodeAsText`). O controlador provavelmente usa ISO-8859-1; a
troca está pendente de um arquivo real com acento (Fase 0-B.D do plano).

**Senha de login do controlador.** Guardada cifrada no banco (`KeystoreSecretCipher`, AES-256
GCM com chave do Android Keystore, formato `enc1:<iv>:<cifra>` em `StoredSecret`). O
`RobotRepository` cifra ao gravar e decifra ao ler; `encryptLegacyPasswords` (chamado a cada
abertura) cifra as senhas que ficaram em texto puro da v1.1. A chave não sai do aparelho nem
vai para o backup do Android: depois de trocar de celular ou restaurar um backup, a senha volta
vazia e o usuário digita de novo ao editar o robô. O login continua indo **sem criptografia**
pela rede (telnet), porque é o protocolo do controlador.

**Pendências / Próximos passos:**
- Testar num aparelho com Android 10 e num com Android 13+: gravar pela pasta padrão, conectar
  uma pasta, apagar a pasta e reabrir o app.
- Confirmar que o MediaStore mantém a extensão `.as` no Android 10 (pasta Documentos).
- Codificação ISO-8859-1 (0-B.D).

---

## 15. `:feature:project` — Tela de Projeto (cabine)

**Arquivos:** `ProjectScreen.kt`, `ProjectViewModel.kt` (com a `ProjectViewModelFactory`). As regras
da grade ficam em `LayoutOps` (`:core:common`, pacote `layout`, testadas na JVM).

Abre pelo ícone de grade do projeto na lista de robôs (`project/{projectName}`).

### Visualização
- "N de M conectados", **Conectar todos** e **Desconectar todos**.
- A grade da cabine: cada robô num cartão com o LED de heartbeat (`HeartbeatDot`), o nome e o
  estado. **Tocar conecta ou desconecta; segurar abre o painel do robô.** Linhas sem nenhum robô
  ficam ocultas.
- Equipamentos como faixas entre as linhas, com setas do sentido do fluxo.
- **Fora do layout:** robôs sem vaga (todos, antes de montar a cabine), também com LED.
- **Modo avançado:** cartão (e item do menu) que abre o Terminal Geral do projeto.
- Conexão e heartbeat são observados com um coletor por robô (`watchedIds`), como no popup de
  robôs conectados.

### Editar layout (lápis)
- A edição acontece numa **cópia**; "Salvar" grava tudo de uma vez (`saveProjectLayout`, uma
  transação) e o "X" descarta.
- Tocar num robô o seleciona. Com ele selecionado: tocar numa vaga vazia move; tocar em outro
  robô troca os dois; **Tirar do layout** (ou tocar na área "Fora do layout") tira da grade.
- **Posicionar todos:** coloca os robôs de fora nas vagas livres, por linha, e cria linhas se
  precisar.
- "+" no alto da grade adiciona coluna; **Adicionar linha** embaixo. "−" aparece só em linha ou
  coluna vazia, e sempre sobra uma. Limites: 1 a 5 linhas, 1 a 6 colunas.
- **Adicionar equipamento:** Transportador (começa com sentido →) ou Outro (nome obrigatório,
  sem sentido). Entra abaixo da última linha. Na faixa: tocar alterna o sentido (→ ← nenhum),
  ↑↓ mudam a faixa e a lixeira exclui.
- O que vem do banco passa por `LayoutOps.sanitize`: robô fora dos limites ou numa vaga já
  ocupada vai para fora do layout.

### Regras ligadas ao repositório
- Editar um robô (`RobotDialog`) **mantém** a vaga dele; se o projeto mudar, ele vai para fora do
  layout no projeto novo.
- Projeto que fica sem robôs (robô excluído ou movido) perde o layout e os equipamentos.
- **Renomear projeto** (menu): muda o nome nos robôs, no layout e nos equipamentos. Se já existir
  um projeto com o nome novo, os robôs passam para ele, que mantém o próprio layout.

**Pendências / Próximos passos:** as ações em lote do projeto (backup de todos, buscar e copiar
programa, verificar erros) são a Fase 2.2 do plano e dependem da infraestrutura da 2.0. Arrastar
robôs na grade pode vir depois, em cima das mesmas funções do `LayoutOps`.
