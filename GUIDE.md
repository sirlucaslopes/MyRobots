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
  do layout). Só a tela de Projeto mexe neles. `serialNumber` é a série do controlador, o "CPF"
  do robô: vem do backup SAVE/FULL ou do comando `ID` ao conectar (`null` = ainda não conhecida).
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

`AppDatabase` (versão 6) + os DAOs `RobotDao`, `BackupDao`, `QuickCommandDao` e `ProjectDao`.
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
  `MIGRATION_5_6` acrescenta `serialNumber` em `robots` (nulo até ser descoberto).
- **Teste de migração:** `MigrationTest` (androidTest, `MigrationTestHelper`) cria o banco v4
  com dados e confere que eles continuam lá na versão atual, e valida a `MIGRATION_4_5` (contra o
  `5.json`) e a `MIGRATION_5_6` (contra o `6.json`) com `runMigrationsAndValidate`. Roda com o celular ligado:
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
  - Entende o protocolo de transferência de arquivo do controlador (conferido byte a byte com
    o K-ROSET em 03/10/2026). O robô manda blocos `05 02 <tipo> <conteúdo> 17` misturados com o
    texto:
    - **LOAD:** `A<arquivo>` → o app responde `02 A "    0" 17`; a cada `C` o app manda
      `02 C "    0" <até 512 bytes> 17` e, no fim, `02 C "    0" 1A 17`; o robô fecha com `E`
      (o app responde `E`) e escreve "File load completed. (N errors)".
    - **SAVE:** `B<arquivo>` → o app responde `B`; vários `D<texto>` (gravados na pasta do
      robô, sem ir para a tela: o terminal mostra só "Recebendo…" e "recebido (N KB)"); `E` no
      fim (o app responde `E`) e "File save completed.".
  - **Regras para nunca deixar o controlador preso** (um controlador esperando o app trava e
    só volta reiniciando; aconteceu num LOAD em 03/10/2026):
    - todo pedido `C` recebe resposta. Sem o arquivo (não achado, nome inválido, ou o app sem
      LOAD em andamento), a resposta é o fim de arquivo: o controlador termina o LOAD vazio
      ("0 errors") e o app marca como **falha**;
    - o arquivo do LOAD sai da memória (`stageLoad`, usado pelo `RobotCommands`), não depende
      da pasta; LOAD digitado à mão no terminal usa a pasta do robô;
    - os bytes saem por uma **fila única** por conexão (antes, cada envio era uma tarefa
      paralela e os bytes podiam sair fora de ordem);
    - bloco partido entre dois pacotes de rede é **juntado** antes de ser lido (o K-ROSET manda
      o texto em pedaços de 1 a 16 bytes);
    - **pergunta do controlador no meio da transferência** (ex.: passo com erro de sintaxe:
      "STEP syntax error. (0:Change to comment and continue, 1:Delete program and abort)", ou o
      "Load?" do `LOAD/Q`): vira `getQuestion` e o app espera a resposta (`answerQuestion`).
      Foi isso que travou o controlador: o app não respondia e o controlador ficava parado
      na pergunta. A pergunta aparece em qualquer tela (janela do `ControllerChecks.questions`
      no `MainActivity`), com as opções do controlador; não dá para fechar sem escolher;
    - transferência sem nenhum bloco do robô por 30 s (`transferStallMs`) e **sem pergunta
      pendente** é encerrada pelo app (LOAD: manda o fim de arquivo; SAVE: fecha o arquivo como
      incompleto);
    - **desconectar no meio de um SAVE/LOAD fica adiado** até o fim (fechar a conexão no meio
      deixa o controlador esperando); a conexão que cai no meio avisa no terminal.
  - `getSave`/`getLoad`: o último SAVE e LOAD de cada robô (arquivo, bytes, terminou, ok e o
    motivo); `getTransfer`/`isTransferring`: se há um em andamento.
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

**Pendências / Próximos passos:** os backups são gravados em UTF-8 e o controlador fala
ISO-8859-1: um comentário com acento vindo do robô pode virar caractere inválido no backup.
O manual diz que LOAD de um programa que já existe é recusado; no K-ROSET ele foi sobrescrito.
Conferir num robô real (o protocolo de testes cobre o K-ROSET).

### Protocolo de testes (`tools/protocolo/`)
Roda sozinho e escreve um relatório; ninguém precisa acompanhar.

```
python tools/protocolo/rodar_testes.py                    # K-ROSET em 127.0.0.1:9205
python tools/protocolo/rodar_testes.py --kroset 127.0.0.1:9105
python tools/protocolo/rodar_testes.py --kroset nao       # só o controlador falso
python tools/protocolo/rodar_testes.py --celular          # também instala e abre no celular
```

- **`controlador_falso.py`**: imita o terminal AS do K-ROSET byte a byte e provoca as falhas
  que o K-ROSET não faz quando a gente quer. O cenário vai no usuário do login: `normal`,
  `fragmentado` (pacotes de 1 a 3 bytes), `erro_load` (2 errors), `recusa_load`, `corta_load`
  e `corta_save` (queda no meio), `para_load` (robô para de pedir), `pede_dados` (pedido `C`
  sem LOAD) e `pergunta_load` (erro de sintaxe com a pergunta 0/1). Ele registra **"preso"**
  sempre que pede algo e o app não responde: é o alerta principal do relatório.
- **Testes** (JUnit, no PC, com o mesmo código do app: `KawasakiTerminalManager` +
  `RobotCommands`), em `core/data/src/test/.../protocolo/`: `ControladorFalsoTest` (F01–F18) e
  `KRosetTest` (K01–K99, usa o programa `pgtesteapp` e o apaga no fim; uma conexão só).
  Sem as propriedades `protocolo.*` (o build normal), esses testes ficam pulados.
- **`rodar_testes.py`**: sobe o controlador falso, roda todos os testes JVM com `--rerun`,
  junta os XML do JUnit, os eventos do falso e o terminal de cada teste que falhou, e grava
  `tools/protocolo/relatorios/<data>/relatorio.md` (cópia em `relatorios/ultimo.md`, fora do
  git). Sai com código 0 só sem falha e sem "preso".
- Teste novo de protocolo: um cenário no `controlador_falso.py` (se precisar) e um `@Test` no
  `ControladorFalsoTest` ou no `KRosetTest`.

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
- `syncRobotFolder`/`syncAllRobotFolders`: ignoram os arquivos de envio (`FileUtil.isTransferFile`:
  `transfer_*`, `var_*`, `db_*`), que não são backups, e trazem para o banco os `.as` da pasta que ainda não
  estão nele ("Sinc: <arquivo>"). **Só importam, nunca apagam backup do banco** — até a v1.1
  um arquivo ausente apagava o backup, o que com a pasta nova (que pode não enxergar arquivos
  antigos depois de reinstalar, ou perder a permissão) apagaria tudo.
- `restoreMissingFiles`: regrava na pasta os backups do banco cujo arquivo não está lá (usado na
  migração da v1.2 e ao trocar de pasta).
- `storageLocation`, `useStorageFolder`, `useDefaultStorage`, `filesFolderUri`: a janela "Pasta
  dos arquivos" (seção 7) e o botão "Arquivos" do terminal (seção 10).
- Não há nenhum dado simulado: `performBackup` (que criava um backup de exemplo quando a API
  de teste falhava), `getRobotLogs` e `getRobotStatus` foram removidos na v1.2.

### Conversa em vários passos (`RobotCommands`)
Classe sem Android (roda nos testes do protocolo). Uma **trava por robô**, a mesma das
checagens do login, para nunca misturar respostas:
- `sendAndAwaitPrompt`: manda o comando e espera o prompt voltar (contador de prompts do
  terminal, que só aumenta).
- `loadFile(robô, arquivo, texto)`: deixa o arquivo pronto na memória (ISO-8859-1), manda
  `LOAD` e só devolve **ok** com as quatro provas: o robô pediu este arquivo, ele foi inteiro, o
  robô fechou a transferência e respondeu "File load completed. (0 errors)" sem pergunta no
  meio. "0 errors" sozinho não basta (o controlador diz isso também para um arquivo vazio).
  Pergunta no meio: com `answer`, responde; sem, espera a tela.
- `saveFile(robô, comando, nome)`: manda o `SAVE…` e confere o arquivo recebido (nome, fim da
  transferência e "File save completed.").
Todos os envios do app passam por aqui: "Enviar para robôs" do painel, a fila de transferência
pendente, o backup de todos e o mestre → escravo da tela de Projeto.

### Checagens depois do login (`ControllerChecks`)

Criado no `MyRobotsApp` e iniciado uma vez (`start()`). Vigia a conexão de todos os robôs e, a
cada login (em qualquer tela: painel, Projeto, Terminal Geral), espera o prompt `>` e manda, um
de cada vez e esperando a resposta de cada um:
1. **`ID`**: lê o número de série ("Serial No. 2503", `AsControllerReplies`). Robô sem série
   cadastrada passa a ter essa. Série diferente da cadastrada vira um `SerialMismatch`: a
   `MainActivity` pergunta se é para atualizar o cadastro (troca de controlador) ou manter (pode
   ser o robô errado, IP trocado).
2. **Relógio** (AS Language Reference Manual, 5-57): manda `TIME`; o controlador mostra
   "TIME 26-10-03(Sat) 08:03:28" e pergunta "Change? (If not, Press RETURN only.)", e o app sai
   com Enter em branco. Mais de 2 min de diferença para o celular vira um `ClockIssue`, e a
   `MainActivity` pergunta se deve corrigir. Corrigir manda `TIME aa-mm-dd hh:mm:ss` (digitado
   letra por letra) com a hora do celular; o controlador grava, mostra a hora gravada e pergunta
   de novo, o app sai com Enter e compara. Se ainda estiver errado, avisa que não aceitou.
   **O K-ROSET mostra sempre 12 h a mais do que foi gravado**, então lá a checagem sempre acusa
   ~12 h; num robô real isso não deve acontecer.
3. **`FREE`**: memória de programas (`AsFreeMemory`), guardada nas SharedPreferences
   `controller_memory`.

Enquanto roda, o robô fica em `busy`. `connectAndWait(robot)` conecta (se preciso) e espera o
login e as checagens; é usado pelo "Conectar e ler", pela escolha do destino de um envio e antes
do `LOAD` de um envio pendente, para os comandos não se misturarem.

**Pendências / Próximos passos:** um `SAVE` feito pelo terminal grava o arquivo na pasta, mas só
vira backup no banco quando a lista de robôs abre (sincronização do `RobotViewModel`) ou no
ícone de sincronizar do histórico. Registrar na hora é a Fase 2.0 de `docs/PLANO_V1_2.md`.

---

## 5. `:core:designsystem` e `:core:common`

- **`:core:designsystem`**: `Theme.kt`, `Color.kt`, `Shape.kt`, `Type.kt` — o tema visual
  (`MyRobotsTheme`) usado em todo o app — e `HeartbeatIndicator.kt` (`HeartbeatDot` e o texto
  de cada `HeartbeatState`). Depende de `:core:model`.
  - **`AppTopBar` (padrão de todas as telas):** a barra do título tem só voltar, o título numa
    linha (com subtítulo opcional, ex.: "R10 · 259 programas · 3 marcados") e, à direita,
    **só o ⋮** com as opções menos usadas. **Todas as outras ações ficam na linha de ações
    logo abaixo** (`ActionStrip`, sempre com `ActionStripHeight` = 64 dp): cada ação
    (`BarAction`) tem ícone e nome, repartindo a largura (mínimo de 54 dp; com mais ações, a
    linha rola de lado). Ação de liga/desliga ligada (lupa aberta, modo de edição) ganha a
    pílula de destaque; desabilitada fica apagada; `ActionTone` dá a cor (`Primary` para a
    ação principal, `Danger` para excluir/limpar, `Success` para conectado); `badge` marca
    alteração não salva; `menu` abre um menu ancorado na ação. O parâmetro `below` põe algo
    embaixo (campo de busca, barra de edição). Título, ações e busca usam a mesma cor
    (`surface`) e formam um bloco só, com uma linha divisória embaixo. Tela nova usa o
    `AppTopBar`, nunca um `TopAppBar` com ícones ao lado do título.
  - `FormDialog`, `RobotPickerSheet`: janela de formulário que não fica atrás do teclado e a
    lista de robôs para enviar.
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

**Arquivos:** `RobotListScreen.kt`, `RobotDialog.kt`, `RobotViewModel.kt`, `ConnectedRobotsViewModel.kt`

### Lista de robôs (`RobotListScreen`)
- Tela inicial de verdade do app (depois da splash). Agrupa os robôs em
  **Fabricante > Projeto > Robô**, com cada nível podendo ser expandido/recolhido.
- **Uma tela só para ver e conectar** (o antigo popup "Robôs Conectados" foi removido).
- Barra do topo (`AppTopBar`): "My Robots" e, no subtítulo, quantos estão conectados e a rede do
  celular (SSID e IP, atualizados a cada 3 s). Sem linha de ações. No **⋮**: **Ordenar A-Z**
  (liga/desliga, com ✓; ordena projetos e robôs), **Fabricantes: pesquisa e comandos** (ver
  abaixo), os dados do Wi-Fi, **Configurar Wi-Fi** (abre
  as configurações do Android) e **Pasta dos arquivos** (ver abaixo).
- **Legenda** no topo da lista (`HeartbeatLegend`, `:core:designsystem`), só do status da
  conexão: LED verde **Conectado** (respondendo), amarelo **Sem sinal** (conectado, mas quieto
  há 8 s) e cinza **Desligado** (sem conexão). Os três nomes têm 9 letras e são os mesmos em
  todo o app (`HeartbeatState.label()`): lista, cabine do projeto, painel e lista de envio.
- **Faixa do projeto:** nome, "N de M conectados" (em verde se algum estiver), **Conectar
  todos**/**Desconectar todos** e o ícone de grade que abre a **tela de Projeto** (seção 15).
- **Cartão do robô:** LED de pulso (`HeartbeatDot`), nome e série (Nº), `ip:porta`, o estado
  (Conectado/Sem sinal/Desligado, na cor do LED), o botão **Conectar** (azul) ou
  **Desconectar** (verde), o terminal (abre o painel na seção Terminal) e um **⋮** com Editar e
  Excluir (com confirmação). Dá para conectar quantos robôs quiser, cada um com a sua conexão.
- Botão "+" abre `RobotDialog` para cadastrar um robô novo.
- A lista guarda a posição da rolagem e os grupos fechados (`rememberSaveable`): ao voltar do
  painel de um robô, ela reaparece igual.
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

### Tela "Fabricantes" (`ManufacturerSettingsScreen`, rota `manufacturers`)
- Aberta pelo ⋮ da lista de robôs. A linha de ações escolhe o fabricante (Kawasaki, Fanuc, ABB,
  Universal). Tudo é gravado na hora no aparelho (`ManufacturerSettings`, `:core:data`,
  SharedPreferences "manufacturer_settings"); sem nada gravado, valem os padrões.
- **Pesquisa rápida do editor:** os termos do botão de lista no campo de pesquisa do editor de
  programas, na ordem da lista. Adicionar (campo ou toque numa sugestão dos padrões que não
  estão na lista), subir/descer, remover. O editor (código AS) usa os da Kawasaki, passados pelo
  `MainActivity` com `LocalSearchTerms` (`:core:designsystem`). Padrão da Kawasaki: .PROGRAM,
  .END, LMOVE, JMOVE, SPRAY, SPRAY_SPEED, AIRCUT_SPEED, …, GUN, CALL_DBK, CALL_PGM, CALL, BASE,
  TOOL, HOME, SPEED, ACCEL, TWAIT, SWAIT, SIGNAL, DOUT, IF, GOTO, LABEL, UC_JUMP, PAUSE, RETURN.
- **Comandos rápidos padrão:** os que um robô novo daquele fabricante recebe ao ser cadastrado
  (`RobotRepository.defaultCommandsFor`). Adicionar, editar (nome, comando, explicação),
  subir/descer e remover. Os comandos de cada robô já cadastrado continuam no terminal dele.
- ⋮: restaurar a pesquisa rápida ou os comandos padrão do fabricante (com confirmação).

### Conexão na lista (`ConnectedRobotsViewModel`)
- Observa `getConnectionStatus`/`getHeartbeat` do `KawasakiTerminalManager` para cada robô
  (um coletor por robô, iniciado uma vez só por id) e conecta/desconecta um robô ou o projeto
  inteiro (o "Conectar todos" recebe os robôs da tela). A bolinha pulsa só quando `ALIVE`.
- Cada tentativa aparece no cartão: "Conectando…" (botão com carregando) e, se o socket não abrir
  em 7 s, "Não conectou: <motivo>" em vermelho por 8 s (recusado pelo robô, sem resposta do IP,
  IP fora de alcance…). Antes, um robô fora de alcance não dava nenhum sinal.

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

**Arquivos:** `AsCodeViewer.kt`, `InstructionDialogs.kt`, `PointTransform.kt`

- Editor de texto completo com numeração de linha e destaque de sintaxe da linguagem AS
  (comentários em verde, textos entre aspas em laranja, seções `.PROGRAM`/`.END`/`.TRANS`
  em amarelo, comandos de movimento em azul, sinais/esperas em verde-água, outras
  palavras-chave em roxo, números em verde-claro).
- **Barra do topo:** voltar, nome do arquivo, lupa, lápis, disquete (chama `onSave` com as
  linhas juntas por quebra de linha; numa tela somente-leitura o padrão não grava) e ⋮.
- **Lupa** abre uma barra embaixo (`SearchNavigationBar`): campo de texto, botão pesquisar,
  setas para a linha encontrada anterior/próxima e "N de M". A pesquisa só roda no botão (ou
  no "pesquisar" do teclado, que fecha); tocar de novo com o mesmo texto vai para a próxima. A
  linha encontrada fica em destaque. O ícone de lista dentro do campo abre a **"Pesquisa rápida"**:
  os termos configurados na tela "Fabricantes" (Kawasaki), cada um com quantas linhas do arquivo
  o contêm (contado em segundo plano; termo que não aparece fica apagado). Tocar pesquisa por
  ele, como a pesquisa de instrução do teach pendant.
- **O texto nunca é editável direto na área de código.** Cada linha é uma linha de uma
  `LazyColumn` (`CodeLinesList`/`CodeLineRow`), colorida com `highlightAsCode` só nas linhas
  visíveis, então funciona liso em arquivo com dezenas de milhares de linhas. Cada linha tem,
  da esquerda para a direita: o número (coluna fixa do tamanho de 4 dígitos; números maiores
  que 9999 diminuem a fonte para caber), a caixa de seleção (só no modo de edição; o espaço
  fica reservado) e o código.
- **Substituir** (aparece na linha de ações só no modo de edição): abre a pesquisa e, embaixo, o
  campo **"Substituir por"** (`ReplaceBar`). O que procurar é o campo da pesquisa (sem
  diferença de maiúsculas). **Substituir** troca a ocorrência atual e vai para a próxima (numa
  linha com várias, troca uma de cada vez, sem trocar de novo o texto que acabou de entrar; a
  primeira vez, se a pesquisa ainda não foi feita, só pesquisa). **Todos** troca todas de uma
  vez e avisa "N substituições em M linhas". Cada troca entra no desfazer (Todos = um passo).
- **Alteração pendente:** o editor compara o texto da tela com a versão salva (a que abriu ou a
  do último salvar); desfazer até voltar ao original deixa de contar como alteração.
- **Lápis** liga o modo de edição: cada linha ganha uma caixa de seleção e abre a barra de
  edição (`LineActionsToolbar`): marcar linhas em lote (todas, limpar, da marcada para
  cima/baixo, entre duas), **copiar** (1+), **colar** (1, entra acima da marcada), **Edit** (1),
  **excluir** (1+) e, à direita, **desfazer** e **refazer**. As duas barras (pesquisa e
  edição) podem ficar abertas juntas.
- **Edit** pergunta o que fazer com a linha marcada (`EditChoiceDialog`):
  - **Editar** (também segurando a linha): se for uma instrução do catálogo `AsInstructions`
    (`:core:common`), abre `InstructionEditDialog`, como o CHANGE do teach pendant: o grupo,
    as outras instruções do grupo para trocar (levando os valores; ex.: SPRAY_SPEED →
    AIRCUT_SPEED, SPRAY → PRE_SPRAY, LMOVE XYZ1 → XYZ2), um campo por parâmetro (ON/OFF em
    botões, números com a unidade) e a prévia da linha. Recuo e comentário (`;...`) voltam
    iguais. "Editar como texto" (e linhas fora do catálogo, como comentários e IF) abre
    `LineEditDialog`.
  - **Inserir**: a linha nova entra no lugar da marcada, que desce junto com as de baixo.
  - **Adicionar**: a linha nova entra logo depois da marcada.
  Nos dois, escolhe-se o grupo e a instrução (`InstructionPickerDialog`, como a lista do
  pendant) ou "Texto livre"; a linha nova usa o recuo da marcada.
- **Catálogo** (`AsInstructions`, grupos do Manual de Operação 5.3): SPRAY_SPEED, AIRCUT_SPEED,
  SPRAY_JSPEED, AIRCUT_JSPEED, SPRAY, PRE_SPRAY, DOUT, ACCEL, SMOOTH_RANGE, CALL_DBK, CALL_PGM,
  TWAIT, TIMER_WAIT, UC_JUMP, LABEL, GUN, LMOVE XYZ1/XYZ2 e JMOVE JOINT, no formato de texto
  dos backups. Reconhece 99,6% dessas instruções nos programas do R10.
- **⋮ "Conversão de programa"** (`ProgramConversionMenu`): deslocar e espelhar pontos
  (`PointTransform.kt`), sobre as linhas marcadas no modo de edição; sem linhas marcadas, o
  menu mostra a dica.
- O voltar do sistema fecha primeiro a pesquisa, depois o modo de edição, e só então sai.
- É usado em quatro rotas diferentes no `:app` (ver seção 13): código completo, um programa
  só, só as variáveis, e arquivo aberto de fora do app (somente leitura).

**Pendências / Próximos passos:** os 4 dígitos do início do LMOVE/JMOVE (`0004`, `0000`) são
editados como um texto só, porque o manual não explica o que cada dígito significa. Faltam no
catálogo as instruções de Data Bank por sinal (FLOWRATE etc.), comparação e salto condicional,
que não aparecem nos backups atuais.

---

## 10. `:feature:dashboard` — Painel do Robô

**Arquivos:** `RobotDashboardScreen.kt`, `RobotDashboardViewModel.kt`, `RobotDashboardViewModelFactory.kt`

Tela principal de UM robô, organizada em uma "home" (`DashboardHome`) e seções alternadas por
`DashboardFeature`:

- **Backup mostrado:** sem um backup pedido, o painel usa o mais recente que não seja arquivo de
  envio (até a v1.2 a sincronização importava `transfer_pg635.as` etc. como backup).
- **Home:** de cima para baixo:
  - **Cartão do robô** (`RobotInfoCard.kt`): faixa baixa com o desenho em linhas de um robô
    de pintura, estilo tela de controle (só ilustração, não mostra a pose real), o modelo, a
    série, o nome, a quantidade de eixos, o selo do status e, no canto de baixo, **Atualizar** e
    o atalho para o **terminal** do robô. **Atualizar** conecta (login e checagens), faz
    `SAVE/FULL <robô>_<aaaammdd_hhmm>` conferido (`RobotCommands.saveFile`), registra o arquivo
    como backup e o painel passa a mostrar esse backup (mesmo se tinha sido aberto num backup
    antigo). O andamento e o resultado aparecem numa faixa logo abaixo do desenho. Embaixo, os dados lidos do backup
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
    **Memória de programas**, no fim do cartão: o backup não traz essa informação; ela vem do
    comando `FREE`, lido a cada login pelo `ControllerChecks` (seção 4): "Total memory, 8192
    KBbytes." e "Available memory size 8175 KBbytes.( 99 %)". O botão é "Ler agora" com o robô
    conectado e "Conectar e ler" sem conexão (conecta, e o login já lê). A última leitura aparece
    mesmo sem conexão, com a data. Abaixo de 10% livre, a barra fica amarela e o texto avisa.
    A série mostrada é a cadastrada no robô; sem ela, a do backup (que passa a ser a do robô).
  - **Linha de conexão**, no alto da home: LED, "Conectado · estado" ou "Desconectado" e o botão
    Conectar/Desconectar.
  - **Status geral** (`RobotHealth` + `AsErrorSeverity`): selo OK / ATENÇÃO · n / SEM DADOS.
    Os alarmes do `.ERRLOG` dos 7 dias antes do backup são classificados em **rotina**
    (porta da cabine, motor desligado, falta de energia...), **programa/movimento** (fora de
    alcance, singularidade...) e **graves** (encoder, servo, sobrecarga, temperatura, purga,
    códigos `D` do hardware...). Só os graves, um backup com mais de 30 dias e **arquivos de
    outro robô na pasta** (backup com outra série que a do robô, pelo `OPEINFO`) ligam o
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
    barra mostra o dia. Precisa de pelo menos dois backups SAVE/FULL. **"Exportar (Excel)"** gera
    `uso_<robô>_<aaaammdd>.csv` com todos os dias (`UsageCsv`, em `:core:common`): colunas
    separadas por ";", vírgula decimal e BOM UTF-8, para abrir direto no Excel em português, e
    abre o compartilhar do Android. Só entram backups do
    mesmo controlador (série do `OPEINFO`) do backup mais novo, e um intervalo com mais horas
    do que o tempo que passou (por exemplo, um backup do K-ROSET com a mesma série) é descartado. Programas executados
    por dia não aparecem: o `.EXECPGLOG` do controlador guarda só os últimos dias.
  - **Backup analisado**: nome, data, total de linhas, aviso quando não é o mais recente e o
    botão "Histórico de backups".
  - **Atalhos em grade**: Programas, Variáveis, Data Bank e os três logs do controlador
    (Erros, Operação, Edição), cada um com a contagem de itens.
  - O arquivo completo (Código AS, abre o `AsCodeViewer` em tela cheia, fora do dashboard)
    fica no menu "⋮" da barra do topo, item "Ver arquivo completo".
- **Terminal (`DashboardFeature.Terminal`)**: com o campo vazio, Enviar (ou o Enter do teclado)
  manda um Enter em branco, para responder perguntas do controlador como "Change? (If not, Press
  RETURN only.)". Terminal de verdade — caixa preta com texto verde (o que o usuário
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
- **Lupa (Programas, Variáveis e Data Bank)**: na barra do topo; abre um campo embaixo dela
  que filtra os cartões enquanto se digita ("N de M"). Programas: nome, comentário ou grupo;
  Variáveis: nome ou valor; Data Bank: número (DB12), comentário ou um valor igual. O
  "Selecionar todos" marca só o que aparece. Voltar ou o X fecha a busca; sair da seção limpa.
- **Variáveis**: no mesmo estilo de Programas e Data Bank. Agrupadas por tipo (Posições/TRANS,
  Reais, Textos, Inteiros...), cada grupo abre e fecha e mostra quantas tem; dentro dele, em
  ordem de nome. Cada cartão (`VariableCard`) tem caixa de seleção, nome (os que começam com `!`
  em amarelo-escuro) e o valor: nas posições, `X, Y, Z, O, A, T` em grade (e `JT7`/`JT8` quando
  existem); nas outras, o valor inteiro. Editar e duplicar por cartão (tocar também edita); "+"
  cria uma variável nova e **pergunta o tipo** (AS Language Reference Manual, 3.4): posição em
  transformação (`.TRANS`, X..T e eixos extras), posição em juntas (`#nome`, `.JOINTS`, JT1..JTn
  pelos eixos do robô), real (`.REALS`, `nome = valor`) ou texto (`$nome`, `.STRINGS`, gravado
  entre aspas). O prefixo é posto sozinho, e a variável entra antes do `.END` da seção do tipo
  (a seção é criada no fim do arquivo se não existir). Editar também só troca linhas de dentro
  das seções de variáveis. A barra do topo tem selecionar todas, enviar, compartilhar e excluir
  as marcadas. Enviar e compartilhar usam `variablesContent`, que tira do backup as linhas das
  variáveis escolhidas dentro das suas seções (`.TRANS ... .END`, `.REALS ... .END`); excluir
  várias é uma gravação só (`deleteVariables`) e só mexe nas linhas de dentro das seções de
  variáveis (antes, uma linha de programa que começasse com o nome também era apagada).
- **Data Bank**: no mesmo estilo da seção Programas: um cartão por linha da seção `.sprdb`
  (`DataBankCard`), em ordem de número, com caixa de seleção, `DBn`, comentário e os seis
  valores (`FRATE, PATTERN, ATOMIZE, HVOLT, SPEED, JSPEED`) em duas linhas, mais editar e
  duplicar. Tocar no cartão também edita; o "+" (botão flutuante) cria uma linha nova. A barra
  do topo tem as mesmas ações de Programas sobre os marcados: selecionar todos, **editar
  selecionados**, enviar, compartilhar e excluir. "Editar selecionados" (`DataBankBulkEditDialog`)
  abre os campos com o valor comum às linhas (vazio, "vários", quando diferem) e aplica só as
  colunas alteradas em todas as linhas marcadas, numa gravação só (`updateDataBankEntries`);
  excluir vários também é uma gravação só (`deleteDataBankEntries`). As duas passam pelo
  `rewriteDataBank`, que percorre a seção `.sprdb` e troca/apaga as linhas pelo número.
- **Posição da rolagem**: a home (cartões) e as listas de Programas, Variáveis, Data Bank e
  dos três logs guardam a posição (`rememberScrollState`/`rememberLazyListState` no nível da
  tela, fora da seção aberta) e os grupos fechados de Programas, então voltam onde estavam ao
  trocar de seção ou ao voltar do editor.
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
- **Enviar para robôs:** ao enviar programas, uma variável ou linhas de Data Bank, abre a lista
  `RobotPickerSheet` (`:core:designsystem`), no formato do popup de robôs conectados: agrupada
  por projeto ("Marcar todos" por projeto), com LED, estado e série. **Marcam-se um ou mais
  robôs** e "Enviar para N robôs". Para cada robô, ao mesmo tempo (`sendFileToRobots`):
  conecta se preciso e espera o login e as checagens (`ControllerChecks.connectAndWait`), grava
  uma cópia na pasta dele (`transfer_<nome>.as`, `var_<nome>.as`, `db_<n>.as`...) e faz o LOAD
  conferido (`RobotCommands.loadFile`, seção 4): ✓ só com o arquivo inteiro, a transferência
  fechada e "0 errors"; qualquer outra coisa é ✗ com o motivo.
  Cada linha mostra o andamento (Conectando…, Enviando…, ✓ ou ✗ com o motivo) e no fim
  aparece "N de M enviados". Um robô que falha não para os outros. O envio fica no painel
  aberto (não navega para outro robô).

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
  `RobotFilesStorage`, o `RobotRepository`, o `KawasakiTerminalManager` e o `ControllerChecks` (vivem o app inteiro)
  e, na primeira abertura da v1.2, regrava na pasta nova os backups do banco
  (`migrateFilesToNewFolderOnce`, ver seção 14).
- **`MainActivity`**: **não pede permissão de armazenamento** (desde a v1.2). Trata arquivos
  `.as`/`.pg` abertos de fora do app (`handleIntent`, ação VER/EDITAR — o texto fica só na
  memória e abre no editor; vira backup se o usuário escolher um robô para salvar). A leitura
  passa por `ExternalAsFile` (seção 14); o pedido é guardado num estado (`incomingIntent`)
  preenchido no `onCreate` e no `onNewIntent`, então um segundo arquivo aberto com o app já
  aberto também é tratado, e girar a tela não reabre o arquivo. Mostra, por cima de qualquer
  tela, as perguntas das checagens do login (`ControllerCheckDialogs`: série diferente e relógio
  errado). Define o mapa de rotas do `NavHost`:

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

**Arquivos:** `ProjectScreen.kt`, `GroupPanels.kt` (ações em grupo, mini terminais, mestre/escravo),
`ProjectViewModel.kt` (com a `ProjectViewModelFactory`). As regras da grade ficam em `LayoutOps`
(`:core:common`, pacote `layout`, testadas na JVM). As ações em grupo rodam no `ProjectOperations`
(`:core:data`) e a troca da base no `AsMasterTransfer` (`:core:common`, testado na JVM).

Abre pelo ícone de grade do projeto na lista de robôs (`project/{projectName}`).

### Visualização
- "N de M conectados", **Conectar todos** e **Desconectar todos**.
- A grade da cabine: cada robô num cartão com o LED de heartbeat (`HeartbeatDot`), o nome e o
  estado. **Tocar conecta ou desconecta; segurar abre o painel do robô.** Linhas sem nenhum robô
  ficam ocultas.
- Equipamentos como faixas entre as linhas, com setas do sentido do fluxo.
- **Fora do layout:** robôs sem vaga (todos, antes de montar a cabine), também com LED.
- **Modo avançado:** cartão (e item do menu) que abre o Terminal Geral do projeto.
- No robô de um par mestre/escravo, a vaga mostra "← R10" (escravo) ou "→ R14" (mestre).

### Ações em grupo
- Cartão com **Backup de todos**, **Comando** e, se o projeto tiver pares, **Mestre → escravo**.
  Cada uma abre a lista dos robôs (todos marcados) para escolher. Uma ação por vez.
- Todas rodam ao mesmo tempo, um robô por conexão, e **sempre conectam antes** (`ControllerChecks.
  connectAndWait`: login e checagens). Um robô que falha não para os outros.
- **Backup de todos:** `SAVE/FULL <robô>_<aaaammdd_hhmm>` em cada robô; espera o arquivo chegar
  inteiro (`KawasakiTerminalManager.getSave`) e o prompt voltar, e registra o arquivo como backup
  (`syncRobotFolder`). Mostra o nome e o tamanho.
- **Comando:** o mesmo comando em cada robô, esperando o prompt voltar.
- O cartão mostra "N de M terminados", falhas e avisos, uma barra de progresso e **Parar**
  (cancela no app; o que o robô já começou, como um SAVE, segue nele) ou **Limpar**.
- A espera do prompt usa um contador que só aumenta (`getPromptCount`), porque o histórico do
  terminal guarda só as últimas 1000 linhas e um SAVE/FULL passa disso.
- As ações vivem no ViewModel da tela: sair do projeto (voltar) cancela a que estiver rodando.

### Terminais
- Um **mini terminal** por robô, dois por linha, na ordem da cabine: LED, nome, as últimas 5
  linhas do terminal e o andamento da ação em grupo (borda e texto: cinza na fila, azul rodando,
  verde pronto, amarelo com aviso, vermelho com falha). Tocar abre o terminal do robô.

### Mestre / escravo
- **Tela "Mestre / Escravo"** (`MasterSlaveScreen` + `MasterSlaveViewModel`, rota `master_slave`):
  aberta pelo ⋮ da lista de robôs, pelo ⋮ da tela de Projeto e pelo "Configurar" embaixo do
  desenho. Todas as configurações de uma vez, um cartão por projeto escravo:
  - **Origem → destino:** o projeto mestre e, para cada robô do destino, o robô de origem
    ("Parear pela posição" usa a mesma vaga na cabine; "Sem par" deixa de fora);
  - **Alterar a base no destino** (somar o offset) e a **variável do offset** (padrão
    `top_offset`, editável);
  - **Enviar a base (.TRANS) junto**;
  - **Frame da base do programa**, com `pgnum` no lugar do número do programa (padrão
    `fr_[pgnum]`: o pg100 usa `fr_[100]`). Só as bases que seguem o padrão recebem o offset e só
    esses frames vão junto; vazio = todas as bases de frame (`AsMasterTransfer.frameFor`,
    `patternRegex`, testados na JVM);
  - um **exemplo com o pg100** (origem, destino e o que vai junto) que muda enquanto se edita;
  - "Salvar" (só com mudança) e "Descartar"; ⋮ do cartão: remover a configuração.
  - **Nova:** escolhe a origem e o destino (projeto que ainda não tem mestre); os pares começam
    pela posição.
  O projeto mestre, o offset e os pares ficam no banco (`ProjectLayout.masterProject/baseOffset`,
  `Robot.masterRobotId`); as opções (alterar a base, enviar a .TRANS, padrão do frame) ficam no
  aparelho (`MasterSlaveOptions`, `:core:data`, SharedPreferences "master_slave").
- **Desenho:** nos dois projetos aparece um cartão com o layout do mestre em cima e o do escravo
  embaixo, e uma seta de cada mestre até o seu escravo, pelos corredores à esquerda das colunas.
  Embaixo, o resumo ("Base no escravo: BASE fr_[N]+top_offset · .TRANS junto") e os botões
  **Transferir** e **Configurar**. Pares com robô fora do layout ficam listados.
- **Transferir** (`TransferDialog.kt`), em duas etapas:
  1. **Escolher:** blocos separados para a **ORIGEM** (mestre, em azul: projeto e robôs de onde os
     programas saem) e o **DESTINO** (escravo, em laranja: cada robô com a sua caixa, "C03 recebe
     de R10"); as opções (alterar a base, enviar a .TRANS, vindas da configuração) e os programas
     (do último backup da origem, com busca). "Analisar".
  2. **Conferir** (`ProjectViewModel.analyzeTransfer`): para cada par e programa, a origem (existe?
     linhas, data e comentário do cabeçalho; "não existe: não vai") e o destino ("não existe: será
     criado"; "existe (...): SERÁ SUBSTITUÍDO"; "sem backup: não dá para saber"), com a data dos
     backups usados (origem: o último com programas; destino: o último qualquer). Com avisos, o
     botão vira "Transferir mesmo assim" (amarelo); "Voltar" muda a escolha. **O LOAD do
     controlador substitui um programa que já existe sem perguntar** (conferido no K-ROSET em
     03/10/2026), por isso o aviso.
  Para cada par: tira os programas do último backup da origem (`AsProgramBlocks`, nome exato), com
  "Alterar a base" troca cada `BASE <frame do padrão>` por `BASE <frame>+<offset>`, junta as
  linhas da `.TRANS` dos frames, conecta no destino e faz o LOAD conferido
  (`RobotCommands.loadFile`). Cada mini terminal mostra o resultado; "Não conectou" vem com o
  motivo (recusado, sem resposta, conexão que abriu e fechou na hora: no K-ROSET, controlador
  desligado).

**Pendências / Próximos passos:** a transferência mestre → escravo só foi testada até a montagem
do arquivo e a janela (os robôs da cabine não estavam ao alcance); falta rodar com um par real.
Buscar programa em todos e verificar erros (Fase 2.2) ainda não existem. Arrastar robôs na grade
pode vir depois, em cima das mesmas funções do `LayoutOps`.
