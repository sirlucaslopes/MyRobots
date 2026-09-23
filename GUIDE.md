# Guia de Telas e Implementações - MyRobots

Este documento serve como referência para as funcionalidades e regras de cada tela do aplicativo MyRobots.

## 0. Estrutura de módulos

O app é dividido em módulos Gradle. Cada módulo tem uma responsabilidade só, então dá para
melhorar uma parte sem mexer nas outras. Todo o código tem comentários em português explicando
o que cada classe e função faz.

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

> Nota: os caminhos de arquivo citados nas seções abaixo continuam válidos pelo NOME do arquivo,
> mas a pasta agora é a do módulo correspondente (ex.: `RobotDashboardScreen.kt` está em
> `feature/dashboard`, `AsCodeViewer.kt` em `feature/codeeditor`).

## 1. Robot Dashboard (Painel do Robô)
**Arquivo:** `RobotDashboardScreen.kt`

### Funcionalidades:
- Exibe o status atual do robô (Estado, Programa, Linha).
- Fornece acesso rápido a recursos através de cards.
- **Recursos (Cards):**
    - **Logs:** Visualização de logs históricos do robô (Filtro por gravidade/tipo).
    - **Programas:** Lista de programas (.PG) extraídos do backup atual. Ao clicar, abre no `AsCodeViewer`.
    - **Variáveis:** Lista de variáveis (.TRANS, etc.) extraídas do backup. Ao clicar, abre no `AsCodeViewer` filtrado.
    - **Código AS:** Atalho para o editor completo de código AS do backup usando o `AsCodeViewer`.
- **Regras:**
    - O card de **Terminal** NÃO deve estar no dashboard. O terminal é acessado diretamente da lista de robôs ou via navegação específica.
    - O botão "Código AS" deve abrir a tela de visualização/edição usando o componente `AsCodeViewer` em tela cheia (via navegação).

## 2. Terminal do Robô
**Arquivo:** `RobotDashboardScreen.kt` (Componente `TerminalPanel`)

### Funcionalidades:
- Envio de comandos diretos ao robô.
- Exibição de resposta do terminal com estilo "Console" (fundo preto, texto verde).
- Lista de comandos rápidos (`QuickCommand`) para facilitação.
- **Design:** Deve manter o estilo industrial/terminal clássico.

## 3. Visualizador de Código AS (Leitor Principal)
**Arquivo:** `AsCodeViewer.kt`

### Funcionalidades:
- Editor de texto principal com syntax highlighting para linguagem AS.
- Suporte a:
    - Numeração de linhas.
    - Pesquisa de texto (Highlight amarelo).
    - Edição e salvamento de conteúdo.
- **Integração:** Todas as visualizações de código (Backup total, Programas específicos, Variáveis) devem utilizar este componente.

## 4. Histórico de Backups
**Arquivo:** `BackupHistoryScreen.kt`

### Funcionalidades:
- Lista todos os backups salvos.
- Permite selecionar o backup que alimentará os dados do Dashboard.

## 5. Status de Rede e Wifi
**Arquivo:** `RobotListScreen.kt` (Componente `WifiStatusCard`)

### Funcionalidades:
- Exibe na tela inicial o nome da rede (SSID) e o endereço IP local do celular.
- Status visual (Cores) para conectado/desconectado.
- **Configuração:** O botão "Wifi Settings" no menu da engrenagem redireciona o usuário diretamente para as **Configurações de Wifi do Android**, eliminando a necessidade de um menu interno.
- **Permissões:** Requer `ACCESS_WIFI_STATE` e `ACCESS_NETWORK_STATE`.
