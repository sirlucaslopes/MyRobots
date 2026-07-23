# Guia de Telas e Implementações - MyRobots

Este documento serve como referência para as funcionalidades e regras de cada tela do aplicativo MyRobots.

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
