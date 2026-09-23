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

:core:common             FileUtil (nomes de arquivo)
:core:model              Robot, Backup, QuickCommand, Manufacturer, WifiConfig, RobotCommandLibrary
:core:database           Room: AppDatabase e os DAOs
:core:network            KawasakiTerminalManager (terminal TCP) e RobotApiService (HTTP)
:core:data               RobotRepository (junta banco + rede + arquivos)
:core:designsystem       Tema (cores, fontes, formas) + bibliotecas de Compose compartilhadas

:feature:splash          Tela de abertura
:feature:robots          Lista e cadastro de robôs, status do Wifi
:feature:backup          Histórico de backups (criar, importar, duplicar, exportar)
:feature:codeeditor      AsCodeViewer: ver/editar código AS
:feature:dashboard       Painel do robô: terminal, programas, variáveis, Data Bank
:feature:terminal        Terminal Geral (vários robôs) e comandos rápidos
:feature:settings        Telas de Settings/Wifi (ainda não ligadas à navegação)
```

### Regras de dependência (para manter tudo organizado)
- `:feature:*` pode usar `:core:*`, mas **uma feature nunca usa outra feature**. Quem liga uma
  tela na outra é o `:app` (na `MainActivity`).
- `:core:model` não depende de ninguém. `:core:database` e `:core:network` dependem só de `:core:model`.
- `:core:data` (repositório) junta database + network + common. As telas só falam com o repositório.
- Para uma parte nova e independente, crie um novo módulo `:feature:nome` (copie o `build.gradle.kts`
  de outra feature) e inclua em `settings.gradle.kts` e em `app/build.gradle.kts`.

### Como compilar
- No Android Studio: sincronize o Gradle e rode o app normalmente.
- Pelo terminal: `./gradlew assembleDebug` (precisa de internet na primeira vez).

---

## 1. `:core:model` — os dados que o app entende

- **`Robot`**: um robô cadastrado (nome, IP, porta, projeto/célula, fabricante, dados de
  login automático). `name` também vira o nome da pasta do robô em `/MyRobots`.
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
- **`WifiConfig`**: SSID, senha e, opcionalmente, IP estático/gateway/máscara. Usado pela tela
  `WifiSettingsScreen`, que ainda não está ligada a nada de verdade (ver seção 13).
- **`RobotCommandLibrary`**: biblioteca de comandos por fabricante (existe no módulo, ver o
  arquivo para o conteúdo atual).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 2. `:core:database` — persistência local (Room)

`AppDatabase` + os DAOs `RobotDao`, `BackupDao`, `QuickCommandDao`. Guarda robôs, backups
e comandos rápidos. Em `MyRobotsApp`, o banco usa `fallbackToDestructiveMigration()`: se a
versão mudar, o banco é **apagado e recriado**, sem migração dos dados.

**Pendências / Próximos passos:** trocar `fallbackToDestructiveMigration` por migrações de
verdade antes de mudar o schema em produção (hoje qualquer mudança de versão apaga os dados
do usuário).

---

## 3. `:core:network` — conversa com o robô pela rede

- **`KawasakiTerminalManager`**: fala com os controladores Kawasaki por telnet/TCP. Uma única
  instância vive o app inteiro (criada em `MyRobotsApp`), então a conexão de um robô continua
  aberta mesmo trocando de tela. Faz:
  - Conectar/desconectar por robô (uma conexão cada), com histórico de até 1000 linhas por robô.
  - Login automático: observa o texto do robô por "login:"/"user:" e "password:" e digita
    sozinho, letra por letra (o controlador perde caractere se receber tudo de uma vez).
  - Entende o protocolo de transferência de arquivo do controlador: quando o robô manda um
    `SAVE`, grava o arquivo em `/MyRobots/<robô>/`; quando pede um `LOAD`, envia o arquivo do
    celular em blocos de 512 bytes. **Atenção:** um bloco do protocolo só é interpretado se
    chegar inteiro no mesmo pacote de rede — se vier partido em dois pacotes, é descartado.
  - `sendChar`/`sendCommand`: enviam tecla a tecla (usado enquanto o usuário digita no terminal
    real-time) ou um comando inteiro com Enter.
  - `deleteProgram`/`deleteVariable`: montam o comando `DELETE` certo (com `/P`, `/D`, `/L`,
    `/R`, `/S`, `/INT` conforme o caso).
- **`RobotApiService`**: interface Retrofit para uma API HTTP do robô (`downloadConfig`,
  `uploadConfig`). Hoje aponta para `http://localhost/`, um endereço de teste — **não existe
  servidor HTTP de verdade**; por isso `RobotRepository.performBackup` sempre cai no
  conteúdo simulado (`generateMockRobotContent`) quando a chamada falha.

**Pendências / Próximos passos:** apontar `RobotApiService` para o endereço real do robô (ou
remover essa via se o backup só for feito pelo terminal/telnet); tratar blocos de handshake
partidos entre pacotes de rede.

---

## 4. `:core:data` — `RobotRepository`

Junta banco (Room) + rede (API/terminal) + arquivos. É a única porta de entrada de dados
para as telas — nenhuma feature fala direto com o DAO ou com a API.

Principais responsabilidades:
- CRUD de robôs, comandos rápidos e backups.
- `insertBackup`: recalcula `programsCount`/`variablesCount`/`memoryUsage` a partir do texto
  (`calculateAndApplyMetadata`) antes de gravar.
- `performBackup`: baixa da API (ou gera conteúdo simulado se falhar), salva como arquivo
  `<robô>_<data>.as` em `/MyRobots/<robô>/` e cria o registro do backup.
- `uploadBackupToRobot`: envia o texto de um backup para a API do robô.
- `getRobotLogs`: gera logs de teste simulados a cada 3 segundos (**não lê logs reais do robô ainda**).
- `getRobotStatus`: devolve status fixo/simulado (Online, memória, contagens) — **não vem do
  robô real ainda**.

**Pendências / Próximos passos:** `getRobotLogs` e `getRobotStatus` são simulados; trocar por
dados reais quando o protocolo Kawasaki tiver como fornecer status/log ao vivo.

---

## 5. `:core:designsystem` e `:core:common`

- **`:core:designsystem`**: `Theme.kt`, `Color.kt`, `Shape.kt`, `Type.kt` — o tema visual
  (`MyRobotsTheme`) usado em todo o app.
- **`:core:common`**: `FileUtil` — hoje só resolve o nome de um arquivo a partir de uma `Uri`
  do Android (usado ao importar/abrir arquivos externos).

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

**Arquivos:** `RobotListScreen.kt`, `RobotDialog.kt`, `RobotViewModel.kt`

### Lista de robôs (`RobotListScreen`)
- Tela inicial de verdade do app (depois da splash). Agrupa os robôs em
  **Fabricante > Projeto > Robô**, com cada nível podendo ser expandido/recolhido.
- Barra do topo: ordenar A-Z (liga/desliga ordenação alfabética nos três níveis), ícone de
  Wifi (mostra SSID e IP do celular, atualizado a cada 3 segundos, para conferir se está na
  mesma rede do robô) e engrenagem (abre um menu com o status do Wifi e atalho para
  "Configurar Wifi", que leva para as configurações de Wifi **do próprio Android**).
- Botão "+" abre `RobotDialog` para cadastrar um robô novo.
- Cada robô mostra nome e `ip:porta`, com botões de terminal (abre o dashboard direto na
  seção Terminal), editar e excluir (com confirmação).
- Cada projeto tem um ícone de terminal próprio que abre o **Terminal Geral** (fala com
  todos os robôs do projeto de uma vez — ver seção 12).
- Tocar num robô abre o histórico de backups dele.

### Cadastro/edição (`RobotDialog`)
- Campos: fabricante (dropdown), projeto (texto livre com sugestões dos projetos já
  existentes), nome (só letras/números/`_`, pois vira nome de pasta), IP, porta (padrão 23,
  a porta padrão do telnet) e bloco de login automático (usuário/senha, opcional).
- Botão "Confirmar" só liga com nome e IP preenchidos. Porta inválida vira 23; projeto vazio
  vira "Padrão".

### ViewModel (`RobotViewModel`)
- Expõe a lista de robôs (`StateFlow`) direto do `RobotRepository`.
- **Sincronização automática ao abrir o app:** para cada robô cadastrado, compara o banco
  com a pasta `/MyRobots/<robô>/` — arquivo `.as` que está na pasta mas não no banco vira um
  backup novo ("Sinc: <arquivo>"); backup do banco cujo arquivo sumiu da pasta é removido do banco.

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 8. `:feature:backup` — Histórico de Backups

**Arquivos:** `BackupHistoryScreen.kt`, `BackupType.kt`, `BackupViewModel.kt`, `BackupViewModelFactory.kt`

- Lista os backups de um robô, com busca por texto e ordenação por data (crescente/decrescente).
- Cada item tem: ver código (abre no `AsCodeViewer`), duplicar (pede um novo nome), compartilhar
  e excluir (com confirmação; também apaga o arquivo físico).
- **Compartilhar** abre um menu com duas opções: exportar para uma pasta escolhida pelo usuário
  (`ActivityResultContracts.CreateDocument`) ou compartilhar via outro app (WhatsApp, e-mail
  etc.), usando `FileProvider` para copiar o arquivo para uma pasta de cache temporária antes
  de enviar.
- Botão "+" abre um menu com duas formas de criar backup: **baixar do robô conectado** (navega
  para o dashboard/terminal) ou **importar** um arquivo `.as` já existente no celular
  (`ActivityResultContracts.OpenDocument`).
- Ícone do robô (`SmartToy`) força a sincronização da lista com a pasta `/MyRobots` na hora.
- `BackupType`: os tipos de backup que o robô Kawasaki sabe salvar (`FULL`, `PROGRAMS`,
  `POSE_VARS`, `REAL_VARS`, `STRINGS`, `AUX`, `SYSTEM`, `ROBOT`, `ERROR_LOG`, `OP_LOG`,
  `ALL_LOG`), cada um com o comando `SAVE/...` correspondente.

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 9. `:feature:codeeditor` — `AsCodeViewer`

**Arquivo:** `AsCodeViewer.kt`

- Editor de texto completo com numeração de linha e destaque de sintaxe da linguagem AS
  (comentários em verde, textos entre aspas em laranja, seções `.PROGRAM`/`.END`/`.TRANS`
  em amarelo, comandos de movimento em azul, sinais/esperas em verde-água, outras
  palavras-chave em roxo, números em verde-claro).
- Barra do topo: lupa (busca com destaque amarelo no texto), lápis (liga/desliga o modo de
  edição) e disquete (chama `onSave` com o texto atual — o padrão não faz nada, então uma
  tela somente-leitura simplesmente não grava).
- **Cuidado com performance:** acima de 100 mil caracteres, só a busca é destacada (sem
  colorir palavras); acima de 2 MB, nenhuma cor é aplicada, para não travar o celular. Acima
  de 50 mil linhas, a coluna de números vira um aviso único em vez de listar linha por linha.
- É usado em quatro rotas diferentes no `:app` (ver seção 14): código completo, um programa
  só, só as variáveis, e arquivo aberto de fora do app (somente leitura).

**Pendências / Próximos passos:** nenhuma pendência conhecida.

---

## 10. `:feature:dashboard` — Painel do Robô

**Arquivos:** `RobotDashboardScreen.kt`, `RobotDashboardViewModel.kt`, `RobotDashboardViewModelFactory.kt`

Tela principal de UM robô, organizada em uma "home" (`DashboardHome`) e seções alternadas por
`DashboardFeature`:

- **Home:** cartão com informações do backup atual (nome, robô de origem, data, total de
  linhas) e quatro atalhos em grade: Programas, Variáveis, Código AS (abre o `AsCodeViewer`
  em tela cheia, fora do dashboard) e Data Bank.
- **Terminal (`Logs`)**: terminal de verdade — caixa preta com texto verde (o que o usuário
  digitou aparece em azul-claro). Cada tecla digitada é enviada ao robô na hora (como um
  terminal real); apagar manda backspace; setas ⬆⬇ mandam histórico de comando do robô; o
  raio abre a biblioteca de comandos rápidos (`:feature:terminal`); um botão abre o gerenciador
  de arquivos do Android direto na pasta `/MyRobots` (se não conseguir abrir, cai no histórico
  de backups); botão Conectar/Desconectar muda de cor conforme o estado.
- **Programas**: lista os programas do backup atual; tocar abre o programa isolado no editor
  (`program_viewer`); ações de enviar para outro robô, duplicar e excluir (compartilhar ainda
  não foi implementado).
- **Variáveis**: tabela com nome fixo à esquerda e valores `X, Y, Z, O, A, T, JT7, JT8`
  rolando para o lado — variáveis do tipo `FRAME` (posição) mostram um valor por coluna, as
  outras mostram o valor inteiro numa célula só. Nomes que começam com `!` aparecem em
  amarelo-escuro (indicando alguma marcação especial do robô). Toolbar: criar, ordenar,
  editar, duplicar, enviar, excluir — todas exigem uma variável selecionada, exceto criar.
- **Data Bank**: tabela parecida (linhas da seção `.sprdb`), mas com checkbox por linha para
  selecionar várias de uma vez e enviar em lote (`onUploadSelected`). Colunas fixas: número e
  comentário; roláveis: `FRATE, PATTERN, ATOMIZE, HVOLT, SPEED, JSPEED`.
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

## 12. `:feature:settings` — Configurações (não ligadas ainda)

**Arquivos:** `SettingsScreen.kt`, `WifiSettingsScreen.kt`

> **Atenção: nenhuma das duas telas está ligada à navegação do `:app` hoje** — não aparecem
> em nenhum lugar do app rodando. Ficam prontas no módulo à espera de serem conectadas.

- **`SettingsScreen`**: escolher IP automático (DHCP) ou fixo (Static), com campos de IP,
  gateway e máscara quando fixo. O botão "Save" **só fecha a tela, não salva nada de verdade**.
- **`WifiSettingsScreen`**: lista de redes Wifi configuradas (`WifiConfig`), com botão "+"
  para cadastrar SSID/senha/IP fixo opcional. **Não está ligada ao banco de dados** — os dados
  vêm só dos parâmetros passados de fora (hoje, ninguém passa nada).

**Pendências / Próximos passos:**
1. Decidir se essas telas ainda fazem sentido (o Wifi real hoje é resolvido abrindo direto as
   configurações do Android, a partir de `RobotListScreen`).
2. Se forem mantidas: ligar `SettingsScreen`/`WifiSettingsScreen` a rotas do `NavHost` em
   `MainActivity`, e ligar `WifiSettingsScreen` a um DAO/repositório para persistir de verdade.

---

## 13. `:app` — Navegação e ligação das peças

**Arquivos:** `MainActivity.kt`, `MyRobotsApp.kt`

- **`MyRobotsApp`** (roda uma vez, antes de qualquer tela): cria a pasta `/MyRobots`, abre o
  banco Room, monta o `Retrofit`/`RobotApiService`, cria o `RobotRepository` e o
  `KawasakiTerminalManager` (esses dois vivem o app inteiro) e confere a pasta de cada robô
  cadastrado.
- **`MainActivity`**: pede a permissão de armazenamento (no Android 11+, "acesso a todos os
  arquivos"; antes disso, a permissão comum) explicando por que precisa da pasta `/MyRobots`,
  trata arquivos `.as`/`.pg` abertos de fora do app (`handleIntent`, ação VER/EDITAR — importa
  como backup temporário com `robotId = -1` e abre no editor somente-leitura) e define o mapa
  de rotas do `NavHost`:

| Rota | Tela | Observação |
|---|---|---|
| `splash` | `SplashScreen` | início; some do histórico ao terminar |
| `robot_list` | `RobotListScreen` | lista de robôs |
| `multi_terminal/{projectName}` | `MultiRobotTerminalScreen` | terminal de todos os robôs do projeto |
| `quick_commands/{manufacturer}/{robotId}` | `QuickCommandScreen` | biblioteca de comandos |
| `backup_list/{robotId}` | `BackupHistoryScreen` | histórico de backups do robô |
| `robot_dashboard/{robotId}/{backupId}?feature={feature}` | `RobotDashboardScreen` | `backupId = -1` usa o backup mais recente; `feature` abre direto uma seção |
| `program_viewer/{backupId}/{programName}` | `AsCodeViewer` | extrai só o trecho `.PROGRAM ... .END` do backup, e ao salvar substitui esse trecho de volta |
| `variable_viewer/{backupId}` | `AsCodeViewer` | junta as seções `.TRANS`/`.REALS`/`.STRINGS` do backup, somente leitura |
| `code_viewer/{backupId}` | `AsCodeViewer` | backup inteiro, com salvar |
| `external_viewer/{backupId}` | `AsCodeViewer` | arquivo importado de fora do app, somente leitura |

**Pendências / Próximos passos:** nenhuma pendência conhecida (além das já listadas em
`:feature:settings`, que dependem de rotas novas aqui quando forem ligadas).
