# Plano MyRobots v1.2

> Entrega: ao aprovar, este texto é gravado como `docs/PLANO_V1_2.md` (primeiro commit da branch
> nova, antes de qualquer código). Nenhum arquivo de código é alterado antes disso.

## Contexto

O app hoje é usado por uma pessoa só, mas vai ser distribuído. Três problemas motivam a v1.2:

1. **Risco de perder dados.** Qualquer mudança de schema apaga o banco, e o app ainda gera
   backup falso quando a API de teste falha. Há também um bug de perda de programa, descrito
   na Fase 0.
2. **Navegação difícil para quem é novo.** Tocar no robô abre o histórico de backups, não o
   robô.
3. **O projeto (cabine) não tem tela própria.** Só existe o Terminal Geral, que não mostra a
   resposta de cada robô.

Decisões tomadas com você antes deste plano:

| Pergunta | Resposta |
|---|---|
| "Copiar base" | Frame **BASE** do robô |
| Regra INZONE | Erro se **não houver LMOVE nem JMOVE** antes do INZONE no mesmo programa |
| Equipamentos por cabine | **Vários** (tabela própria) |
| Backup de todos | Sempre **SAVE/FULL** |

---

## Onde o código não bate com o GUIDE.md ou com o pedido

Nestes pontos, vale o código. Cada um é tratado na fase indicada.

1. **As telas não falam só com o repositório.** `RobotDashboardViewModel`,
   `MultiRobotTerminalViewModel`, `QuickCommandViewModel` e `ConnectedRobotsViewModel` recebem o
   `KawasakiTerminalManager` direto e chamam `connect`, `sendCommand`, `getHistory` etc. O
   `RobotRepository` não conhece o terminal. A regra real é "telas falam com o repositório **e**
   com o `KawasakiTerminalManager`". Proposta: atualizar o GUIDE para refletir isso e, na Fase 2,
   pôr a lógica nova de várias etapas (backup em lote, cópia) em serviços do `:core:data`, e não
   nos ViewModels. Fases 0 e 2.
2. **Bug de perda de programa (grave).** Em `MainActivity` (rota `program_viewer`), extrair e
   salvar um programa usa `startsWith(".PROGRAM $programName")`. Com `pg1` e `pg10` no mesmo
   backup e `pg10` antes:
   - abrir `pg1` mostra o `pg10`;
   - salvar apaga os **dois** blocos e grava um só.

   `duplicateProgram` (`RobotDashboardViewModel.kt:1041`) tem o mesmo erro de prefixo. Isso é
   comum em Kawasaki (nomes numerados). Fase 0.
3. **"Duplicar" não reescreve o comentário.** O pedido diz que `duplicateProgram` usa
   `PROGRAM_HEADER_REGEX` para reescrever `.PROGRAM nome()...;comentário`. No código, a regex só
   é usada para **ler** o cabeçalho. `duplicateProgram` faz apenas `replaceFirst` do nome, não
   muda o comentário e não trata a data. A reescrita do cabeçalho com comentário novo precisa ser
   escrita. Fases 0 e 2.2.
4. **O status e os logs simulados não aparecem na tela.** `RobotDashboardViewModel.status` e
   `.logs` existem, mas a tela não coleta `status` e `logs` só alimenta o `LogsPanel`, marcado
   como "NÃO É USADA". Dá para remover sem mudar nada visível. Fase 0.
5. **Um SAVE feito pelo terminal não vira backup na hora.** O `KawasakiTerminalManager` grava o
   arquivo na pasta, mas o registro no banco só acontece no `RobotViewModel.init` (ao abrir a
   lista) ou no ícone de sincronizar do histórico. Fase 2.0.
6. **O heartbeat se engana com o próprio eco.** `sendCommand` chama `appendLog("\n> cmd")`, que
   atualiza `lastActivityAt`. Mandar um comando deixa o robô ALIVE mesmo que ele não responda.
   Fase 2.0.
7. **O `KawasakiTerminalManager` não é seguro com vários robôs ao mesmo tempo.**
   - `connections` e `pendingTransfers` são `mutableMapOf` comuns, acessados de várias threads.
   - Cada escrita (`sendRaw`, `sendRawDirect`, `sendCommand`) abre uma coroutine nova, então a
     ordem dos bytes não é garantida.
   - `connect()` chamado duas vezes durante os 5 s de conexão abre dois sockets.

   Fase 2.0.
8. **O bloco de protocolo partido perde mais que o GUIDE diz.** Além do 0x05…0x17 partido, um
   `0xFF` (IAC) ou `ESC [` que chega no fim do pacote também se perde, e o `0x05` solto faz o
   resto do bloco aparecer como texto. Os blocos `D` (conteúdo do SAVE) também vão inteiros para
   o histórico: a cada bloco a lista de 1000 linhas é copiada. Fase 2.0.
9. **`MultiRobotTerminalViewModel.loadRobots` duplica coletores.** A cada emissão de `allRobots`
   ele lança um coletor novo por robô, sem controle. Fase 2.2.
10. **`HeartbeatDot` está em `:feature:robots`**, e a tela de Projeto não pode importar de outra
    feature. Fase 1.
11. **A pasta do robô é montada de dois jeitos.** O repositório usa
    `replace(Regex("[^a-zA-Z0-9_]"), "_")` e o terminal usa `replace(" ", "_")`. Hoje dá no mesmo
    porque o `RobotDialog` só aceita `[A-Za-z0-9_]`, mas um robô antigo com outro caractere
    ficaria com os arquivos em pastas diferentes. Fase 2.0 (uma função só).
12. **Arquivos soltos.** Além de `data/repository/RobotRepository.kt`, existe
    `app/src/main/xml/AndroidManifest.xml`, que também não pertence a nenhum módulo. Fase 0.
13. **Dependências sem uso.** `:app` declara coil, camera, play-services-location, accompanist,
    datastore e logging-interceptor, todos marcados "ainda não usadas". Para distribuir, vale
    remover (APK menor, menos permissões implícitas). Fase 0, passo opcional.

---

## Fase 0: Segurança antes de distribuir

**Objetivo:** nenhuma atualização do app pode apagar ou falsificar dados do usuário.

**Módulos afetados:**
- `:core:database`: `AppDatabase`, novo `DatabaseMigrations.kt`, `schemas/`, testes de migração.
- `:core:common`: novo `AsProgramBlocks.kt` com testes JVM.
- `:core:data`: `RobotRepository`.
- `:core:network`: `RobotApiService` removido.
- `:feature:backup`: `BackupViewModel`, `BackupType` removido.
- `:feature:dashboard`: ViewModel e tela.
- `:app`: `MyRobotsApp` e `MainActivity`.
- `:feature:settings`: removido.

**Mudanças de banco:** a versão continua **4**. Só liga a exportação do schema e remove o modo
destrutivo. A primeira migração de verdade (4→5) é na Fase 2.

**Passos (um commit cada):**

1. **Exportar o schema v4.**
   - Aplicar o plugin Gradle `androidx.room` (2.7.0) no `:core:database` com
     `room { schemaDirectory("$projectDir/schemas") }`.
   - Pôr `exportSchema = true` em `AppDatabase`.
   - Versionar o `schemas/my.robots.core.database.AppDatabase/4.json` gerado.

   As entidades não mudam, então o hash de identidade do Room continua igual e o banco já
   instalado no seu celular abre normalmente.
2. **Tirar o modo destrutivo.**
   - Criar `DatabaseMigrations.kt` com `val ALL_MIGRATIONS: Array<Migration> = emptyArray()`.
   - Em `MyRobotsApp`, trocar `.fallbackToDestructiveMigration()` por
     `.addMigrations(*ALL_MIGRATIONS)`.
   - Atualizar o comentário do `AppDatabase`, que hoje diz "é apagado e recriado".

   A partir daqui, subir a versão sem migração faz o app **falhar ao abrir**, em vez de apagar
   os dados. É o comportamento que queremos durante o desenvolvimento.
3. **Preparar o teste de migração.**
   - Adicionar `androidx.room:room-testing` ao catálogo e ao `:core:database` como
     `androidTestImplementation`.
   - Usar `schemas/` como assets do `androidTest`.
   - Criar `MigrationTest` com `MigrationTestHelper`: cria o banco v4, insere um robô, um backup e
     um comando rápido, e confirma que o banco abre com `ALL_MIGRATIONS` e os dados continuam lá.

   Na Fase 2 esse teste ganha o caso 4→5. Roda com o celular ligado:
   `.\gradlew.bat :core:database:connectedDebugAndroidTest`.
4. **Corrigir o bug de perda de programa.**
   - Criar `my.robots.core.common.as.AsProgramBlocks`, em Kotlin puro. Todas as funções comparam
     o **nome exato** (texto entre `.PROGRAM` e `(`, sem diferenciar maiúsculas):
     - `programName(line)`
     - `extract(content, name)`
     - `replace(content, name, newBlock)`
     - `remove(content, names)`
     - `list(content)`
   - Criar testes JVM com `pg1`/`pg10`, cabeçalho com data e comentário, backup sem `.END` final e
     `\r\n`.
   - Usar essas funções nas rotas `program_viewer` (extrair e salvar) e em `duplicateProgram`,
     `packProgramsContent` e `deletePrograms` do dashboard. Assim a lógica fica num lugar só, e a
     Fase 2 reaproveita.
5. **Remover os caminhos de dado falso.**
   - `BackupViewModel.createBackup()` e `BackupType.kt`: só o `createBackup` usa `BackupType`.
   - No `RobotRepository`: `performBackup`, `generateMockRobotContent`, `uploadBackupToRobot`,
     `getRobotLogs` e `getRobotStatus`.
   - No dashboard: `_status`/`status`, `refreshStatus`, `logs`, `LogsPanel` e o parâmetro
     `mockStatus`.
6. **Remover a API HTTP.**
   - Apagar `RobotApiService` e `RobotStatusResponse`.
   - Tirar o Retrofit do `MyRobotsApp` e do construtor do `RobotRepository`.
   - Tirar do `:core:network` retrofit, converter-moshi, okhttp, moshi e o ksp do moshi.

   Recomendação: **sai de vez**. O backup real é pelo terminal, e não existe servidor HTTP no
   controlador.
7. **Remover os arquivos soltos:** `data/repository/RobotRepository.kt` (e a pasta `data/`) e
   `app/src/main/xml/AndroidManifest.xml`.
8. **Remover o `:feature:settings`.**
   - Apagar o módulo e tirar de `settings.gradle.kts` e `app/build.gradle.kts`.
   - Apagar também `WifiConfig` do `:core:model`, que só essa feature usa.
9. **Renomear `DashboardFeature.Logs` para `DashboardFeature.Terminal`.**
   - Trocar em `RobotDashboardScreen` (6 usos) e nas duas rotas da `MainActivity`
     (`?feature=Logs` para `?feature=Terminal`).
   - Não há rota persistida, então não precisa manter o nome antigo por compatibilidade.
10. *(Opcional)* **Remover dependências sem uso** do `:app` (item 13 da lista acima).
11. **GUIDE.md:** atualizar as seções 0, 2, 3, 4, 8, 10, 12 (removida) e 13.

**Riscos e testes:**
- **Risco principal: o 4.json não bater com o banco instalado.** Teste: instalar o APK da
  Fase 0 **por cima** da versão atual, sem desinstalar, e confirmar que robôs, backups e comandos
  rápidos continuam lá. Faça isso antes de apagar qualquer coisa: exporte `/MyRobots` para o PC e,
  se possível, copie o banco com `adb` ou o Device Explorer.
- **Bug do prefixo:** coberto pelos testes JVM. Teste manual: num backup com `pg1` e `pg10`,
  editar e salvar `pg1` e conferir que `pg10` continua intacto.
- **Com os robôs reais:** nada muda no protocolo nesta fase. Basta conectar num robô e confirmar
  que o terminal abre em `?feature=Terminal`.

**Estimativa:** M.

---

## Fase 1: Navegação centrada no robô

**Objetivo:** tocar no robô leva ao painel dele, com o estado e as ações à vista.

**Módulos afetados:**
- `:core:model`: recebe o `HeartbeatState`, movido do `:core:network`.
- `:core:designsystem`: recebe o `HeartbeatDot` e passa a depender de `:core:model`, que é folha,
  então não cria ciclo.
- `:feature:robots`: `RobotListScreen` e `ConnectedRobotsSheet`.
- `:feature:dashboard`: `RobotDashboardScreen`.
- `:app`: `MainActivity`.

Nenhuma feature passa a depender de outra.

**Mudanças de banco:** nenhuma.

**Passos:**

1. **Mover o indicador de heartbeat para um lugar compartilhado.**
   - `HeartbeatState` vai para `:core:model`. O `:core:network` já depende do model via `api`,
     então só mudam os imports.
   - `HeartbeatDot` e o texto do estado ("Ativo"/"Sem resposta"/"Desconectado") vão para
     `:core:designsystem`.
   - O `ConnectedRobotsSheet` passa a usar a versão compartilhada.
2. **Tocar no robô abre o painel.** Na `MainActivity`, `onRobotClick` navega para
   `robot_dashboard/{id}/-1`. O botão de terminal do card continua indo para
   `?feature=Terminal`.
3. **Atalho para o histórico no painel.**
   - Na home do painel (`DashboardHome`), o cartão "Informações do Backup" ganha o botão
     "Histórico de backups", que chama o `onViewBackups` que já existe.
   - Na rota `backup_list`, tocar num backup continua abrindo `robot_dashboard/{id}/{backupId}`.
     Isso deixa um painel empilhado sobre o outro, então a navegação passa a usar
     `popUpTo("robot_dashboard/{robotId}/{backupId}?feature={feature}") { inclusive = true }`
     para não empilhar painéis.
   - O cartão passa a indicar quando o backup mostrado **não é o mais recente**, porque o
     usuário escolheu outro no histórico.
4. **LED de heartbeat no card da lista.**
   - A `RobotListScreen` já recebe o `ConnectedRobotsViewModel`, que observa todos os robôs com
     um coletor por id (`watchedIds`).
   - Basta ler `connectedRobotsViewModel.heartbeats` uma vez na tela e passar
     `heartbeats[robot.id]` para o `RobotItem`. Não cria coletor novo.
   - Resultado: o mesmo ViewModel alimenta o popup e a lista.
5. **Menu "⋮" no card.** Editar e Excluir saem dos ícones expostos e vão para um
   `DropdownMenu`. A confirmação de exclusão continua como está. O botão de terminal continua
   visível.
6. **Painel de robô sem backup.**
   - O `RobotDashboardViewModel` ganha `hasNoBackup: StateFlow<Boolean>`, que fica verdadeiro
     quando `observeBackup` recebe uma lista vazia. Hoje ele simplesmente não faz nada nesse caso.
   - A `DashboardHome` mostra um estado vazio: "Este robô ainda não tem backup", com os passos
     (1. Conectar, 2. Enviar SAVE/FULL) e dois botões:
     - "Abrir terminal": vai para `DashboardFeature.Terminal`.
     - "Importar arquivo": vai para `onViewBackups`, onde já existe a importação.
   - Os atalhos de Programas, Variáveis etc. ficam desabilitados.
   - Na Fase 2.0, esse estado ganha o botão "Fazer backup agora".
7. **GUIDE.md:** seções 7, 10 e 13 (a observação da rota `robot_list`).

**Riscos e testes:**
- **Empilhamento de painéis** ao voltar do histórico. Testar: lista → R12 → histórico → backup
  antigo → voltar. O esperado é voltar para a lista, sem painel duplicado.
- **Com os robôs reais:** conectar dois robôs pelo popup e conferir se o LED do card acompanha o
  do popup. Desligar o cabo de um deles e ver o LED ir para cinza. Deixar um conectado e parado
  e ver o LED ir para amarelo. O LED amarelo só funciona direito depois da correção do eco na
  Fase 2.0 (item 6 da lista de divergências). Até lá, mandar um comando deixa o LED verde mesmo
  sem resposta do robô.

**Estimativa:** M.

---

## Fase 2: Tela de Projeto

**Objetivo:** o projeto (cabine) vira uma tela própria, com a cabine desenhada, o estado de
cada robô e ações em lote que mostram o resultado robô por robô.

**Módulo novo ou dentro do `:feature:terminal`?** Módulo novo, **`:feature:project`**.
- O Terminal Geral continua no `:feature:terminal` como "modo avançado". A tela de Projeto chega
  até ele por um callback que o `:app` liga à rota `multi_terminal/{projectName}`.
- A tela de Projeto é maior que o terminal (grade, cinco ações, comparação). Juntar os dois
  deixaria o `:feature:terminal` com duas responsabilidades.

A Fase 2 é grande. Por isso está dividida em **2.0 (infraestrutura)**, **2.1 (cabine)** e
**2.2 (ações)**. Cada parte pode ser entregue e testada sozinha.

### Mudanças de banco (4 → 5, todas na 2.1)

```kotlin
// Robot: dois campos novos. null = fora do layout.
val layoutRow: Int? = null,
val layoutCol: Int? = null

@Entity(tableName = "project_layouts")
data class ProjectLayout(
    @PrimaryKey val projectName: String,
    val rowCount: Int = 2,   // "rows" evitado: ROWS é palavra-chave do SQLite (window functions)
    val colCount: Int = 2
)

enum class EquipmentType { CONVEYOR, OTHER }   // "Nenhum" = não ter linha na tabela

@Entity(tableName = "project_equipment", indices = [Index("projectName")])
data class ProjectEquipment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val projectName: String,
    val type: EquipmentType,
    val name: String = "",          // obrigatório quando type = OTHER
    val position: Int,              // 0 = acima da linha 1 ... rowCount = abaixo da última
    val flowDirection: Int = 0,     // 1 direita, -1 esquerda, 0 sem sentido
    val sortOrder: Int = 0          // ordem entre equipamentos na mesma faixa
)
```

Como a cabine aceita vários equipamentos, eles saem da `ProjectLayout` e vão para uma tabela
própria. Vários equipamentos na mesma faixa aparecem empilhados, na ordem de `sortOrder`. Os
enums são gravados como TEXT pelo suporte nativo do Room, igual ao `Manufacturer` hoje.

```sql
-- MIGRATION_4_5
ALTER TABLE robots ADD COLUMN layoutRow INTEGER DEFAULT NULL;
ALTER TABLE robots ADD COLUMN layoutCol INTEGER DEFAULT NULL;
CREATE TABLE IF NOT EXISTS project_layouts (
  projectName TEXT NOT NULL, rowCount INTEGER NOT NULL, colCount INTEGER NOT NULL,
  PRIMARY KEY(projectName));
CREATE TABLE IF NOT EXISTS project_equipment (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectName TEXT NOT NULL, type TEXT NOT NULL,
  name TEXT NOT NULL, position INTEGER NOT NULL, flowDirection INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS index_project_equipment_projectName ON project_equipment(projectName);
```

O SQL final é conferido contra o `5.json` exportado, e o teste de migração valida com
`runMigrationsAndValidate`. Os robôs antigos chegam com `layoutRow` e `layoutCol` nulos, ou seja,
todos "fora do layout". É o esperado. Um projeto sem linha em `project_layouts` usa o padrão 2×2,
e a linha só é criada na primeira edição.

### 2.0 Infraestrutura (terminal, parser e serviços)

**Objetivo:** o terminal aguenta vários robôs transferindo ao mesmo tempo e avisa o início, o
andamento e o fim de cada transferência.

**Módulos afetados:** `:core:network` (`KawasakiTerminalManager`), `:core:common` (parser AS),
`:core:data` (serviços novos) e `:app` (montagem das peças).

**Passos:**

1. **Deixar o terminal seguro com várias threads.**
   - Trocar `connections` e `pendingTransfers` por `ConcurrentHashMap`.
   - Dar a cada conexão **um escritor só**: um `Channel<ByteArray>` consumido por uma coroutine
     da conexão. `sendRaw`, `sendRawDirect`, `sendChar` e `sendCommand` só enfileiram, então a
     ordem dos bytes fica garantida.
   - Adicionar o estado `isConnecting`, para que dois `connect()` seguidos não abram dois
     sockets.
2. **Corrigir o bloco partido.**
   - Cada `ConnectionState` ganha um buffer de sobra (`carry`).
   - `processBytes` passa a trabalhar sobre `carry + novos bytes`. Se o pacote terminar no meio
     de um bloco `0x05 0x02 … 0x17`, de um IAC (`0xFF` com menos de 3 bytes) ou de uma sequência
     `ESC [`, o trecho incompleto fica no `carry` para o próximo pacote.
   - Proteção: se o `carry` passar de 64 KB sem achar o `0x17`, ele é descartado como texto e
     aparece um aviso no terminal. Isso evita crescer sem limite se chegar um `0x05` perdido.
   - Extrair a análise para uma classe pura (`KawasakiStreamParser`) com testes JVM que partem o
     mesmo fluxo em todos os pontos possíveis e comparam com o resultado sem partir.
3. **Avisar o andamento das transferências.**
   - Novo `getTransfer(robotId): StateFlow<TransferState>`, com os estados:
     - `Idle`
     - `Receiving(fileName, bytes, lines)`
     - `Received(fileName, file, bytes, lines)`
     - `Sending(fileName, sent, total)`
     - `Sent(fileName)`
     - `Failed(fileName, reason)`
   - **Fim do SAVE:** o bloco `E`, que já fecha o arquivo hoje, passa a emitir `Received`.
   - **Andamento do SAVE:** o controlador não informa o tamanho total. A tela mostra bytes e
     linhas recebidos e um "~X%" estimado pelo tamanho (`memoryUsage`) do último backup FULL
     daquele robô. Sem backup anterior, mostra só as linhas.
   - **Andamento do LOAD:** exato, `loadOffset / loadData.size`.
   - **Falha:** 30 s sem bloco `D` durante um `Receiving` viram `Failed("sem resposta")`. Queda de
     conexão no meio também vira `Failed`.
4. **Parar de despejar o SAVE no terminal.**
   - O conteúdo dos blocos `D` deixa de ir para o histórico. Em vez disso, uma linha de
     andamento ("Recebendo X… N linhas") é atualizada no lugar.
   - Isso muda o que você vê no terminal durante um SAVE. Sem essa mudança, quatro robôs com
     SAVE/FULL copiam a lista de 1000 linhas a cada pacote.
5. **Corrigir o eco no heartbeat.** O eco `> comando` é gravado por um `appendLocal`, que não
   mexe em `lastActivityAt`.
6. **Unificar o nome da pasta do robô.** Nova função `robotDirName(name)` em `:core:common`, usada
   pelo repositório, pelo terminal e pelo `RobotViewModel`.
7. **Registrar o backup automaticamente após o SAVE.**
   - Mover a lógica de `RobotViewModel.syncRobotBackups` para `RobotRepository.syncRobotFolder` e
     criar `RobotRepository.importBackupFile(robotId, file)`.
   - Novo `BackupAutoImporter` no `:core:data`, criado no `MyRobotsApp`: observa os
     `TransferState.Received` de todos os robôs e registra o arquivo como backup.
   - Isso vale para qualquer SAVE, inclusive o do painel, e corrige o item 5 da lista de
     divergências.
8. **Completar o parser AS.** Em `AsProgramBlocks` (`:core:common`), adicionar:
   - `parseHeader`: move a `PROGRAM_HEADER_REGEX` do dashboard para cá.
   - `rewriteHeader(block, newName, newComment)`: mantém os parâmetros e remove `@data#N`, que o
     controlador regrava no LOAD. **A confirmar com um robô real** (ver Riscos).
   - `normalizedBody(block)`: corpo sem o cabeçalho, com espaços do fim da linha removidos e
     linhas em branco do fim ignoradas. Serve para comparar programas.
   - O "Duplicar" do dashboard passa a usar `rewriteHeader`.
9. **Serviço de ações em lote.** Novo `ProjectOperations` no `:core:data`, que recebe o
   repositório e o terminal. Ele implementa as ações de 2.2 e devolve
   `Flow<List<RobotStepResult>>` (robô, estado ✓ / ⚠ / ✗ / ignorado, mensagem, andamento). Os
   ViewModels só exibem esse resultado, o que mantém a regra "a lógica fica fora da tela".
10. **Botão "Fazer backup agora"** no estado vazio do painel da Fase 1. Ele manda
    `SAVE/FULL <robô>_<aaaammdd_hhmm>` e mostra o `TransferState`.

### 2.1 Cabine visual

**Objetivo:** abrir o projeto mostra os robôs dispostos como na cabine real, e o layout pode ser
editado no próprio celular.

**Módulos afetados:**
- `:core:model`: `Robot`, `ProjectLayout`, `ProjectEquipment` e `EquipmentType`.
- `:core:database`: `ProjectDao`, versão 5, `MIGRATION_4_5` e teste.
- `:core:data`: métodos de projeto no repositório.
- Novo `:feature:project`: `ProjectScreen`, `ProjectViewModel`, `CabinGrid`, `CabinEditor`, a
  factory e o `LayoutOps` puro.
- `:app`: rota e dependência.
- `:feature:robots`: o ícone do projeto passa a abrir a tela de Projeto.

**Passos:**

1. **Banco:** entidades, `ProjectDao`, versão 5, `MIGRATION_4_5` em `ALL_MIGRATIONS` e o caso
   4→5 no `MigrationTest`.
2. **Repositório:**
   - `getProjectLayout(name)`: Flow, com o padrão 2×2 quando não existir linha.
   - `getEquipment(name)`
   - `saveLayout(layout, robotPositions, equipment)`: `@Transaction`, grava tudo de uma vez.
   - `renameProject(old, new)`: `@Transaction`, atualiza `robots.project`, `project_layouts` e
     `project_equipment`.
   - Regra em `updateRobot`: **se o `project` mudou, zera `layoutRow` e `layoutCol`**, e o robô
     cai em "Fora do layout" no projeto novo.
   - Limpeza: ao atualizar ou excluir um robô, se o projeto antigo ficou sem robôs, apagar o
     layout e os equipamentos dele.
3. **`LayoutOps`, em Kotlin puro com testes JVM.** Contém as regras da grade:
   - adicionar linha ou coluna no fim;
   - remover a linha ou coluna vazia `i`, deslocando os robôs de índice maior e os equipamentos
     com `position > i`;
   - mover robô para uma vaga;
   - trocar dois robôs;
   - tirar robô do layout;
   - "Posicionar todos", que preenche por linha, da esquerda para a direita;
   - saneamento: robô fora dos limites, ou dois robôs na mesma vaga, vai para "Fora do layout".

   Limites: 1 a 5 linhas e 1 a 6 colunas. O "−" só aparece se a linha ou coluna estiver vazia
   e sobrar pelo menos uma.
4. **Módulo e navegação.**
   - Criar o `:feature:project`, copiando o `build.gradle.kts` do `:feature:terminal`.
   - Nova rota `project/{projectName}`.
   - No `ProjectHeader` da `RobotListScreen`, o ícone vira "Abrir projeto".
5. **Cabine em modo visualização.**
   - A grade mostra cada robô com `HeartbeatDot`, nome e status. Tocar conecta ou desconecta.
   - "Conectar todos" / "Desconectar todos" no topo.
   - Linhas totalmente vazias ficam ocultas.
   - As faixas de equipamento mostram o sentido com setas.
   - Os robôs "Fora do layout" aparecem embaixo, com LED.
   - O heartbeat é observado com o mesmo padrão `watchedIds` do `ConnectedRobotsViewModel`.
6. **Modo "Editar layout".**
   - "+" à direita da grade adiciona coluna, e "+" embaixo adiciona linha.
   - "−" nas linhas e colunas vazias.
   - Tocar num robô o seleciona. Com um robô selecionado:
     - tocar numa vaga move o robô para ela;
     - tocar em outro robô troca os dois de lugar;
     - "Tirar do layout" ou tocar na área de baixo tira o robô da grade.
   - "Posicionar todos".
   - A edição acontece numa cópia local, e o banco só é gravado em "Salvar". "Cancelar" descarta
     tudo.
   - **Tocar em vez de arrastar:** recomendo manter o toque, que você já validou no protótipo.
     Ele convive com a rolagem da tela, funciona em telas pequenas, é mais fácil de acertar com
     luva e é bem mais simples no Compose. Arrastar pode vir depois, por cima das mesmas funções
     do `LayoutOps`.
7. **Equipamentos.**
   - "Adicionar equipamento" com tipo Transportador ou Outro. "Outro" pede um nome.
   - Na própria faixa: setas ↑↓ mudam a posição, e um toque alterna o sentido (→ ← sem sentido).
     "Outro" começa sem sentido.
   - Também na faixa: excluir.
8. **Renomear projeto.** Ação no menu da tela de Projeto, usando `renameProject`.
9. **GUIDE.md:** seções 1, 2, 7, 13 e a nova seção do `:feature:project`.

### 2.2 Ações do projeto

**Objetivo:** substituir o terminal vazio por ações guiadas que seguem sempre o mesmo padrão:
escolher → revisar → executar → resultado por robô.

**Módulos afetados:** `:feature:project` (telas das ações, `ResultList`, `CompareScreen`),
`:core:common` (regras), `:core:data` (`ProjectOperations`), `:feature:codeeditor` (abrir numa
linha), `:feature:terminal` (Terminal Geral) e `:app` (rotas).

**Componente comum:** `ActionResultList` mostra uma linha por robô com ✓ / ⚠ / ✗ / "ignorado",
a mensagem e, quando houver, a barra de andamento. Os robôs desconectados aparecem sempre como
"ignorado", nunca somem da lista.

**Passos (um commit por ação):**

1. **Backup de todos.**
   - Para cada robô conectado: `SAVE/FULL <robô>_<aaaammdd_hhmm>`, todos ao mesmo tempo.
   - O andamento vem de `TransferState`. O resultado mostra "salvo com N linhas", ⚠ se o arquivo
     vier sem `.END`/programas, ou ✗ "falhou: motivo".
   - O `BackupAutoImporter` (2.0) registra cada arquivo no banco.
   - Um botão "Tentar de novo" roda a ação só nos robôs que falharam.
2. **Buscar programa (sem mexer no robô).**
   - Para cada robô do projeto, carrega o **último backup, um por vez** (backups FULL são
     grandes) e extrai o bloco com `AsProgramBlocks`.
   - Resultado por robô:
     - ✓ encontrado, com linhas e data do cabeçalho;
     - ✗ ausente;
     - ⚠ "sem backup".
   - Os robôs são agrupados pelo `normalizedBody`, que ignora a data do cabeçalho. Com um grupo
     só, aparece "Todos iguais". Com mais, cada grupo é marcado A, B… ("R10 e R12 iguais; R11
     diferente").
   - A idade do backup aparece em cada linha. Se passar de 7 dias, aparece ⚠ e o atalho "Fazer
     backup antes", que roda o Backup de todos só naquele robô.
   - A partir do resultado:
     - "Comparar" abre a nova rota `project_compare/{backupA}/{backupB}/{programName}`, uma tela de
       diferenças linha a linha com duas colunas no celular deitado e uma lista unificada no
       celular em pé. O cálculo usa a biblioteca `java-diff-utils`, Kotlin/JVM e pequena.
     - "Copiar para cá" abre o Copiar programa já preenchido, com a origem num robô que tem o
       programa, o mesmo nome e comentário, e os robôs onde ele falta como destino.
3. **Verificar erros (regras).**
   - Novo `interface ProgramRule { val id; val title; fun check(program: AsProgram): List<RuleViolation> }`
     em `:core:common`, com uma lista registrada `ProgramRules.all`. Adicionar uma regra é criar
     uma classe e incluí-la na lista.
   - Primeira regra, `InzoneNeedsMoveRule`: dá erro se **não houver nenhum LMOVE nem JMOVE em
     linha anterior** ao INZONE dentro do mesmo programa. Comentários, linhas em branco e texto
     depois de `;` são ignorados.
   - Testes JVM cobrem: INZONE na primeira linha, depois de LMOVE, depois de JMOVE, depois só de
     LAPPRO, dentro de comentário e em maiúsculas/minúsculas.
   - O resultado mostra robô, programa, linha e regra. Tocar no resultado abre o editor na linha.
   - Para isso, o `AsCodeViewer` ganha o parâmetro `initialLine: Int? = null` (rolar até a linha e
     destacá-la), e a rota muda para `program_viewer/{backupId}/{programName}?line={line}`.
4. **Copiar programa.**
   - Passo 1: escolher o robô e o programa de origem, o nome novo (validado com as regras do
     `validateProgramName`) e o comentário novo.
   - Passo 2: escolher os destinos (todos os conectados ou alguns).
   - Passo 3: **revisão obrigatória.** Mostra o resumo por robô e marca ⚠ "já existe, será
     sobrescrito" com base no último backup de cada destino, informando a data desse backup.
   - Passo 4: para cada destino, grava `transfer_<nome>.as` na pasta do robô e manda
     `LOAD transfer_<nome>.as`. O andamento vem de `TransferState.Sending`.
   - Resultado: ✓ se o LOAD terminou sem erro no terminal, ✗ caso contrário.
   - A lógica de gravar e mandar LOAD sai do `RobotDashboardViewModel.sendProgramsToRobot` e vai
     para `ProjectOperations.loadFile`, usada pelos dois.
5. **Copiar base (frame BASE).**
   - Passo 1: escolher o robô de origem. O app lê a BASE do último backup FULL dele e mostra
     X, Y, Z, O, A, T.
   - Passo 2: escolher os destinos.
   - Passo 3: revisão com **valor atual → valor novo** por robô, também lido do último backup.
   - Passo 4: enviar o comando AS de BASE para cada robô.
   - Passo 5: sugerir um backup novo para confirmar o valor gravado.

   **Esta ação depende de duas respostas** (ver Perguntas em aberto). Fica por último e só é
   implementada depois delas.
6. **Terminal Geral com resposta por robô.**
   - O `MultiRobotTerminalScreen` ganha abas: "Enviados" (o histórico atual) e uma aba por robô,
     mostrando `terminalManager.getHistory(robotId)`, que já tem a resposta completa.
   - Corrigir os coletores duplicados do `loadRobots` com o padrão `watchedIds`.
   - Na tela de Projeto, o Terminal Geral fica como último card, chamado "Modo avançado".
7. **GUIDE.md:** seção nova do `:feature:project` com as ações; seções 9, 10, 11 e 13.

### Riscos e testes da Fase 2 (em especial com os robôs reais)

**Automatizados (JVM):**
- `KawasakiStreamParser` com o fluxo partido em todos os pontos;
- `LayoutOps`;
- `AsProgramBlocks` e `rewriteHeader`;
- `InzoneNeedsMoveRule`;
- `MigrationTest` 4→5, no celular.

**Com os robôs reais, na ordem:**
1. **SAVE de um robô só**, pelo terminal do painel. O arquivo precisa ficar **idêntico** ao de
   antes da mudança: compare com um SAVE feito na versão 1.1, com `fc /b` no PC. O backup precisa
   aparecer no histórico sem reabrir o app.
2. **Backup de todos com 2 robôs, depois com os 4** (R10–R13). Nenhum arquivo pode sair truncado
   (conferir o `.END` final e a contagem de programas contra o SAVE individual). Repetir com o
   Wi-Fi fraco, longe do AP, para forçar pacotes partidos.
3. **Queda no meio de um SAVE** (desligar o Wi-Fi do celular). O robô tem que ficar ✗ com
   motivo, e não pode ficar arquivo meio gravado registrado como backup.
4. **LOAD de programa novo** em um robô, e depois **LOAD de um programa que já existe**. Anotar
   exatamente o que o controlador responde: se pede confirmação, se sobrescreve calado ou se dá
   erro. **Isso define se o Copiar programa precisa responder a um prompt ou mandar DELETE
   antes.** Hoje não sei o comportamento, e é o maior risco da 2.2.
5. **Cabeçalho depois do LOAD.** Carregar um programa sem `@data#N` e ver se o controlador aceita
   e regrava a data. Isso valida o `rewriteHeader`.
6. **Heartbeat.** Com um robô conectado e parado, mandar um comando que não gera resposta. O LED
   tem que ir para amarelo depois de ~8 s.
7. **Copiar base**, só depois das respostas: primeiro num robô de teste, com um backup antes, e
   com o robô em modo que permita alterar a BASE.

**Outros riscos:**
- **Memória no "Buscar programa" e no "Verificar erros"** com quatro backups FULL. Mitigação:
  carregar e processar um backup por vez, guardando só os blocos de programa.
- **Nome de projeto igual em fabricantes diferentes.** A tela de Projeto agrupa só por nome,
  como já fazem o popup e o Terminal Geral. Se isso for um problema, o layout teria que usar
  fabricante + nome como chave.

**Estimativas:** 2.0 = G, 2.1 = G, 2.2 = G (Copiar base = P depois das respostas).

---

## Ordem de execução e dependências

```
Fase 0 ──► Fase 1 ──► 2.0 ──► 2.1 ──► 2.2 (Backup de todos → Terminal Geral → Buscar → Regras → Copiar programa → Copiar base)
```

- **A Fase 0 vem antes de tudo.** A 2.1 muda o schema, e sem migrações isso apagaria os dados.
  O `AsProgramBlocks` da Fase 0 é a base da 2.0 e da 2.2.
- **A Fase 1 vem antes da 2.** Ela move `HeartbeatState` e `HeartbeatDot` para o
  compartilhado, e a cabine usa os dois.
- **A 2.0 vem antes da 2.2.** O Backup de todos, o Copiar programa e o Copiar base dependem do
  `TransferState`, do escritor único e do bloco partido corrigido.
- **A 2.1 e a 2.2 podem ser trocadas de ordem se você quiser as ações antes da cabine.** Nesse
  caso, a tela de Projeto nasce só com a lista de robôs, sem grade.
- **Branch:** `melhorias/v1.2` a partir de `melhorias/modularizacao-v1.1`. Hoje há alterações
  locais não commitadas em `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`
  e `.idea/*`. Preciso saber se entram num commit antes ou se ficam de fora.
- Cada passo vira um commit com o app compilando
  (`.\gradlew.bat assembleDebug testDebugUnitTest`).

## Atualizações do GUIDE.md

- **Seção 0:** remover o `:feature:settings`; adicionar o `:feature:project`; corrigir a regra
  "as telas só falam com o repositório" para incluir o `KawasakiTerminalManager` e os serviços do
  `:core:data`; dizer que `:core:designsystem` depende de `:core:model`.
- **Seção 1:** tirar o `WifiConfig`; incluir `layoutRow`/`layoutCol` no `Robot`; adicionar
  `ProjectLayout`, `ProjectEquipment`, `EquipmentType` e `HeartbeatState` (movido).
- **Seção 2:** schema exportado, migrações escritas à mão (`DatabaseMigrations.kt`), versão 5,
  teste de migração e como rodá-lo. Remover a pendência do modo destrutivo.
- **Seção 3:** remover o `RobotApiService`; documentar o escritor único, o buffer de bloco
  partido (remover a pendência), `TransferState`, o SAVE que não vai mais para o histórico e o eco
  que não conta no heartbeat.
- **Seção 4:** remover `performBackup`, `uploadBackupToRobot`, `getRobotLogs` e
  `getRobotStatus`; adicionar `syncRobotFolder`, `importBackupFile`, os métodos de projeto,
  `BackupAutoImporter` e `ProjectOperations`.
- **Seção 5:** `AsProgramBlocks`, `robotDirName` e `ProgramRule`/`ProgramRules`.
- **Seção 7:** tocar no robô abre o painel; LED no card; menu ⋮; o ícone do projeto abre a tela de
  Projeto.
- **Seção 8:** remover `BackupType` e o "criar backup"; o backup é registrado sozinho após o SAVE.
- **Seção 9:** parâmetro `initialLine`.
- **Seção 10:** `Logs` → `Terminal`; atalho "Histórico de backups"; aviso de "backup não é o mais
  recente"; estado vazio com "Fazer backup agora"; o Duplicar reescreve o cabeçalho.
- **Seção 11:** abas por robô no Terminal Geral; ele é aberto pela tela de Projeto.
- **Seção 12:** removida (`:feature:settings`).
- **Seção nova:** `:feature:project` (cabine, modo de edição, equipamentos, as cinco ações e a
  comparação).
- **Tabela de rotas da seção 13:**

| Rota | Mudança |
|---|---|
| `robot_list` | tocar no robô → `robot_dashboard/{id}/-1` |
| `robot_dashboard/{robotId}/{backupId}?feature={feature}` | `feature=Terminal` (antes `Logs`) |
| `backup_list/{robotId}` | tocar num backup substitui o painel atual (`popUpTo`) |
| `program_viewer/{backupId}/{programName}?line={line}` | novo parâmetro `line` opcional |
| `project/{projectName}` | **nova**: tela de Projeto (cabine + ações) |
| `project_compare/{backupA}/{backupB}/{programName}` | **nova**: diferenças de um programa entre dois robôs |
| `multi_terminal/{projectName}` | aberta pelo card "Modo avançado" da tela de Projeto |

## Perguntas em aberto

Estas ainda bloqueiam alguma parte:

1. **Copiar base (bloqueia o passo 5 da 2.2):**
   - **a)** Mande um trecho de um SAVE/FULL real onde a BASE aparece (acho que fica na seção de
     dados auxiliares, mas não tenho um arquivo real aqui para confirmar o formato).
   - **b)** Qual comando você usa hoje para gravar a BASE no controlador (ex.: `BASE` + valores,
     ou por uma variável de pose) e em que modo o robô precisa estar?
   - **c)** Os robôs da mesma cabine costumam ter a **mesma** BASE? Em pintura, robôs em lados
     opostos normalmente têm BASE diferente. Se for o caso, a ação faz mais sentido como "copiar
     com ajuste" ou só entre robôs do mesmo lado, e a revisão precisa deixar isso bem visível.
2. **Regra do INZONE:** além de LMOVE e JMOVE, outros movimentos contam como "movimento antes"?
   Por exemplo LAPPRO/JAPPRO, LDEPART/JDEPART, HOME, C1MOVE/C2MOVE. Minha proposta é que só LMOVE
   e JMOVE contem, e que a lista fique fácil de mudar na própria regra.
3. **LOAD sobre programa existente:** o controlador pede confirmação? Se não souber, é o teste 4
   com os robôs reais, que precisa ser feito antes do Copiar programa.
4. **Idade de backup "antigo" no Buscar programa:** proponho 7 dias. Serve?
5. **Alterações locais não commitadas** (`libs.versions.toml` e wrapper do Gradle): entram na
   branch nova ou ficam de fora?

## Verificação ao final de cada fase

- `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain --continue` sem erros. Os testes
  JVM novos ficam em `:core:common` e `:core:network`.
- `.\gradlew.bat :core:database:connectedDebugAndroidTest` com o celular ligado (Fases 0 e 2.1).
- Instalar o APK **por cima** da versão anterior no seu celular, sem desinstalar, e conferir que
  robôs, backups e comandos continuam lá.
- Rodar a lista "com os robôs reais" da fase.
- Atualizar o GUIDE.md no mesmo conjunto de commits da fase.
