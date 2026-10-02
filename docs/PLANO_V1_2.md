# Plano MyRobots v1.2

> Branch: `melhorias/v1.2`. **Situação em 26/09/2026:**
> - Fase 0: feita.
> - Fase 0-B: B, C, A e E feitas; D (acentos) espera um arquivo real; F (Play Console) é com
>   você. **Nada disso foi testado num aparelho ainda.**
> - Fase 0-C: passos 3–5 feitos; 1 (rodar o `MigrationTest`) e 2 (arquivo real) pendentes.
> - Fase 1: passos 1–3 feitos (o 3 compila, falta testar no aparelho).
> - Skills Android em `.claude/skills/` (commit `fc4d670`).
> - **Fase 1.5 (nova, 01/10):** pasta autossuficiente com `robo.myrobots`/`projetos.myrobots`
>   para restaurar depois de reinstalar. Ainda não começada.

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

**Situação:** passos 1–11 feitos (commits `8654bd9` a `96f040d`). O `MigrationTest` compila,
mas ainda não rodou num aparelho. O que as auditorias da Play e de segurança encontraram
depois disso está na **Fase 0-B**, e os testes na **Fase 0-C**, logo abaixo.

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

## Fase 0-B: Publicação na Play e segurança (achados das auditorias)

**Objetivo:** o app poder ser publicado na Google Play sem violar política e sem expor os dados
do usuário (arquivos dos robôs e senha de login do controlador).

**Origem:** três auditorias feitas em 25/09/2026 com as skills `play-policy-insights`
(relatório gerado pelo script da skill), `android-permissions-security` e
`android-intent-security`. O `PLANO_MELHORIAS.md` citado no pedido não existe no repositório.
A decisão de armazenamento usada aqui é a que você descreveu: pasta automática
`Documentos/MyRobots` via MediaStore, com opção de escolher outra pasta pelo Storage Access
Framework (SAF).

### Achados, em ordem de gravidade

**Crítico**

1. **`MANAGE_EXTERNAL_STORAGE` sem justificativa na política.** *(play-policy-insights,
   verificado pelo crítico da skill)*
   - `AndroidManifest.xml:10` declara a permissão e `MainActivity.kt:168/172` manda o usuário
     para "acesso a todos os arquivos".
   - Todo acesso a arquivo é só em `<armazenamento>/MyRobots` (`MyRobotsApp`, `RobotRepository`,
     `KawasakiTerminalManager.getRobotDir`, `RobotViewModel.syncRobotBackups`).
   - Um app de robôs não é gerenciador de arquivos, antivírus nem backup do aparelho, então a
     Play recusa. Correção: item A (migração de armazenamento).
2. **O robô (ou qualquer aparelho no IP dele) escolhe o caminho do arquivo no celular.**
   *(achado próprio, na linha da android-intent-security: entrada externa sem validação)*
   - No protocolo de transferência, o nome do arquivo vem do controlador: bloco `B` (SAVE) e
     bloco `A` (LOAD).
   - O nome é usado direto em `File(getRobotDir(...), fileName)` (`startSaveFile` e
     `prepareLoadFile` em `KawasakiTerminalManager`), sem limpeza.
   - Um bloco `A` com `../../../../data/data/my.robots/databases/robot_database` faz o app
     **enviar o próprio banco** (com as senhas dos robôs) pela rede. Um `B` com `../` grava
     fora da pasta do robô.
   - O telnet não autentica o servidor: basta um aparelho responder no IP cadastrado.
   - Correção: item B.

**Importante**

3. **Armazenamento quebrado no Android 10 hoje.** *(android-permissions-security)*
   - No Android 10 (API 29, dentro do `minSdk 28`), `WRITE_EXTERNAL_STORAGE` sozinha não dá
     acesso a `/sdcard/MyRobots` por `java.io.File`, e o manifesto não tem
     `requestLegacyExternalStorage`. O manifesto antigo apagado na Fase 0 tinha.
   - As gravações falham caladas (`saveFileToRobotFolder` engole a exceção).
   - Resolvido pelo item A. Até lá, é um bug real para quem usa Android 10.
4. **Senha do controlador em texto puro e incluída no backup na nuvem.** *(play-policy-insights
   Data Safety e android-permissions-security)*
   - `Robot.loginPassword` é uma coluna comum da tabela `robots`.
   - `android:allowBackup="true"` com `data_extraction_rules.xml` e `backup_rules.xml` só com
     exemplos comentados: o banco inteiro, com as senhas, vai para o backup do Google e para a
     transferência entre aparelhos.
   - Correção: item E.
5. **O filtro de "abrir arquivo" aceita demais.** *(android-intent-security)*
   - A `MainActivity` é exportada (obrigatório, é a de LAUNCHER) e aceita `VIEW`/`EDIT` com:
     - esquema `file://` (além de `content://`);
     - categoria `BROWSABLE`;
     - qualquer `text/plain` ou `application/octet-stream`, sem conferir extensão nem conteúdo
       depois.
   - Com `file://`, outro app pode mandar `file:///data/data/my.robots/databases/...`. O app lê o
     próprio arquivo privado, mostra no editor e, se o usuário salvar num robô, grava a cópia na
     pasta compartilhada. É um caso de "confused deputy".
   - Correção: item C.
6. **Arquivo externo sem limite de tamanho.** *(android-intent-security)*
   - `handleIntent` (`MainActivity.kt:640`) e a importação do histórico
     (`BackupHistoryScreen.kt:93`) fazem `readText()` do arquivo inteiro.
   - Um arquivo enorme (ou um provider que nunca termina) derruba o app por falta de memória.
   - Correção: item C.
7. **Fluxo de pedir permissão incompleto.** *(android-permissions-security)*
   - Falta `shouldShowRequestPermissionRationale` e o tratamento de "negado para sempre" (levar
     para `ACTION_APPLICATION_DETAILS_SETTINGS`).
   - O aviso aparece de novo a cada abertura do app.
   - "Agora não" deixa o app seguir sem pasta, e as gravações falham caladas.
   - Resolvido pelo item A: no Android 10+ não sobra permissão de armazenamento para pedir, e no
     Android 9 fica um fluxo de três estados.

**Médio**

8. **Um segundo arquivo aberto com o app já aberto é ignorado.** *(android-intent-security,
   seção onNewIntent)*
   - `onNewIntent` chama `setIntent`, mas o processamento está em `LaunchedEffect(intent)`
     dentro do `setContent`, que não recompõe com a Activity `singleTask` já aberta.
   - Correção: item C (guardar o intent num estado atualizado em `onNewIntent`, com a mesma
     validação do `onCreate`).
9. **Acentos corrompidos (a confirmar com arquivo real).**
   - O terminal grava os bytes do SAVE como vieram (o controlador usa ISO-8859-1), mas a
     sincronização (`file.readText()`), a importação e o `handleIntent` leem como UTF-8, e
     `writeText` grava UTF-8.
   - Um comentário com "ç/ã" pode virar `�` e voltar assim para o robô no LOAD.
   - Correção: item D.
10. **FileProvider amplo demais.** *(android-permissions-security, "URI grants com escopo")*
    - `file_paths.xml` expõe `cache-path path="."` (o cache inteiro) e
      `external-path path="MyRobots"`, que nenhum código usa: os dois compartilhamentos usam
      `cacheDir/shared_backups`.
    - Correção: item C.
11. **Falhas de arquivo caladas.**
    - `saveFileToRobotFolder` tem `catch (e: Exception) { }` e `handleIntent` só faz
      `printStackTrace`. O usuário acha que salvou.
    - Correção: junto do item A (a camada nova devolve sucesso/erro e a tela avisa).

**Baixo**

12. **`android:usesCleartextTraffic="true"` sobrou da API HTTP removida.** Sockets TCP (telnet)
    não passam por essa regra, então dá para tirar.
13. **Nome do arquivo de compartilhar vem do nome do programa sem limpeza.**
    `shareProgramsContent` (`RobotDashboardScreen`) usa `"${programs[0].name}.as"` em `cacheDir`.
    Um nome com `../` vindo de um backup grava fora de `shared_backups` (só dentro da área
    privada do app).
14. **A extensão não é limpa em `FileUtil.sanitizeFileName`.** Ela é preservada como veio.
    Conferi que não permite sair da pasta (a parte após o último ponto não tem `..`), mas uma `/`
    na extensão faz o arquivo cair numa subpasta ou falhar calado.
15. **SSID do Wi-Fi aparece como `<unknown ssid>` no Android 8.1+.**
    - Ler o SSID exige permissão de localização.
    - **Não** adicione localização: é permissão sensível na Play e não é função central.
    - Mostre só o IP do celular (via `ConnectivityManager`/`LinkProperties`) ou "rede atual".

**Play Console (não é código, mas bloqueia a publicação)**

16. **Política de privacidade:** a Play exige a URL no Play Console. Recomendo também um link no
    app (menu da lista de robôs). O texto deve dizer que:
    - os dados (robôs, IPs, login dos controladores, backups) ficam só no aparelho;
    - o login é enviado ao controlador por telnet, sem criptografia;
    - o app não usa SDK de análise nem de anúncios.
17. **Formulário Data Safety.** O script da skill sugere declarar "Files and docs" e "User IDs"
    como coletados, porque o app envia arquivos e login para fora do aparelho. Minha leitura é
    diferente: esses envios são iniciados pelo usuário e vão para o compartilhamento do Android
    ou para o próprio controlador dele, nunca para o desenvolvedor. Por isso não seriam "coleta".
    **Confirme com o texto da Central de Ajuda antes de enviar.** Nas práticas de segurança,
    responder que os dados **não** são criptografados em trânsito (telnet) e que o usuário pode
    apagá-los (excluir robô ou desinstalar).
18. **Acesso para revisão (App access):** o app não tem login, mas quase tudo precisa de um
    controlador Kawasaki na rede. Explique isso nas instruções e ofereça um `.as` de exemplo
    para abrir no editor e no painel sem robô.

**Pontos de atenção da solução MediaStore + SAF (pedido "c")**

- **MediaStore só enxerga os arquivos que o próprio app criou.**
  - No Android 11+, um `.as` copiado para `Documentos/MyRobots` pelo PC ou por outro app não
    aparece na consulta do MediaStore sem permissão, e a "sincronização da pasta" atual depende
    disso.
  - **Depois de desinstalar e reinstalar, o app perde a posse dos próprios arquivos antigos.**
  - Para ler o que o usuário colocou na pasta, o caminho é o SAF: pedir a árvore
    `Documentos/MyRobots` com `ACTION_OPEN_DOCUMENT_TREE` e guardar a permissão com
    `takePersistableUriPermission`.
  - **Recomendação:** o MediaStore cria a pasta e grava, e um "Conectar pasta" (SAF) no primeiro
    uso libera a leitura completa.
- **Limites do SAF:** no Android 11+ o seletor não deixa escolher a raiz do armazenamento, a raiz
  de `Download` nem `Android/data`. `Documentos/MyRobots` e `/MyRobots` podem ser escolhidas.
  Tratar a permissão perdida (pasta apagada ou permissão revogada) pedindo de novo.
- **Android 9 (`minSdk 28`)** não tem `RELATIVE_PATH` no MediaStore. Ou mantém
  `WRITE_EXTERNAL_STORAGE` com `maxSdkVersion="28"` e `java.io.File` só nessa versão, ou sobe o
  `minSdk` para 29 (ver Perguntas em aberto).
- **O terminal grava e lê com `java.io.File`** (SAVE, LOAD e pasta do robô). Ele passa a receber
  uma interface de acesso a arquivos (`OutputStream`/`InputStream` por nome), definida no
  `:core:network` e implementada no `:core:data`, para não quebrar a regra "network só depende
  de model".
- **Migração dos arquivos que já existem:** a versão nova não declara mais
  `MANAGE_EXTERNAL_STORAGE`, então perde o acesso a `/MyRobots` ao atualizar.
  - O banco já tem o texto completo de cada backup (`Backup.content`), e dá para regravar os
    arquivos em `Documentos/MyRobots` a partir dele no primeiro uso.
  - Arquivos que estavam só na pasta e nunca entraram no banco ficam no disco. O usuário importa
    com "Conectar pasta" apontando para `/MyRobots`.
- **Política:** MediaStore e SAF não exigem declaração nem formulário na Play, e o aviso de
  permissão da `MainActivity` deixa de existir no Android 10+.

### Correções (passos em ordem, um commit cada)

- **B. Nome de arquivo do protocolo (P, primeiro, é segurança).**
  - Função `safeTransferFileName(name)`: aceita só `[A-Za-z0-9_.-]`, sem `..`, sem `/` ou `\`,
    e com tamanho máximo.
  - Usada em `startSaveFile` e `prepareLoadFile`. Nome inválido responde com erro ao robô e
    escreve o motivo no terminal.
  - Conferir também com `canonicalPath` que o arquivo final está dentro da pasta do robô.
  - Testes JVM com `../`, absoluto, vazio e nome normal.
  - Aplicar a mesma limpeza no nome de `shareProgramsContent` (item 13) e na extensão em
    `sanitizeFileName` (item 14).
- **A. Migração de armazenamento (G).**
  1. Interface de arquivos do robô (listar, ler, gravar, apagar por nome) no `:core:network`,
     com implementações no `:core:data`:
     - MediaStore (Android 10+);
     - SAF (pasta escolhida);
     - legado `java.io.File` (Android 9, se o `minSdk` continuar 28).

     Uma função só para o nome da pasta do robô, que já estava no plano da 2.0.
  2. `RobotRepository`, `RobotViewModel.syncRobotBackups`, `KawasakiTerminalManager`
     (SAVE/LOAD) e `MyRobotsApp` passam a usar a interface. Nenhum `getExternalStorageDirectory`
     sobra.
  3. Tela/menu "Pasta dos arquivos": mostra a pasta atual e oferece "Conectar pasta" /
     "Escolher outra pasta" (SAF).
  4. Migração do primeiro uso: regrava os backups do banco na pasta nova e oferece importar
     `/MyRobots` antigo pelo SAF.
  5. Manifesto: remover `MANAGE_EXTERNAL_STORAGE` e `READ_EXTERNAL_STORAGE`, deixar
     `WRITE_EXTERNAL_STORAGE` com `maxSdkVersion="28"` (ou nada, se `minSdk` 29), e remover o
     aviso de permissão da `MainActivity` (ou deixá-lo só para o Android 9, com o fluxo de três
     estados).
  6. Botão "Arquivos" do terminal (`RobotDashboardScreen`): abrir a pasta pelo URI do SAF ou
     do MediaStore em vez do caminho fixo `primary%3AMyRobots`.
  7. As gravações devolvem sucesso ou erro, e as telas mostram o erro (item 11).
- **C. Intents, FileProvider e manifesto (M).**
  - Filtro `VIEW`/`EDIT`: só `content://`, sem `BROWSABLE`.
  - `handleIntent`:
    - consultar `OpenableColumns.SIZE` e recusar acima de um limite (sugestão: 20 MB; um SAVE/FULL
      real é bem menor, confirmar);
    - ler com limite mesmo sem SIZE;
    - aceitar só nome terminado em `.as`/`.pg` e texto sem bytes nulos;
    - avisar o usuário quando recusar.
  - O mesmo limite vale na importação do histórico.
  - `onNewIntent`: o intent vira um estado (ex.: `MutableStateFlow<Intent?>`) observado pelo
    Compose, com a mesma validação.
  - `file_paths.xml`: só `<cache-path name="shared_files" path="shared_backups/" />`.
  - Tirar `usesCleartextTraffic`.
- **D. Codificação dos arquivos AS (P, depois de confirmar com um arquivo real com acento).**
  Ler e gravar sempre em ISO-8859-1 (o mesmo `charset` do terminal), numa função só da camada
  de arquivos. Teste JVM com "ç/ã/°".
- **E. Senha do controlador (M, precisa de decisão, ver Perguntas em aberto).** Recomendação:
  - cifrar `loginPassword` com uma chave AES-GCM do Android Keystore (a chave não sai do
    aparelho nem vai para o backup);
  - excluir do backup na nuvem só o que não faz sentido restaurar;
  - depois de uma restauração, a senha não decifra e o app pede para digitar de novo.

  Como muda o dado gravado, entra na migração 4→5 da Fase 2.1 ou numa 5→6 própria. Se preferir o
  mínimo: `data_extraction_rules.xml`/`backup_rules.xml` excluindo o banco do backup na nuvem.
- **F. Play Console (sem código):** política de privacidade, Data Safety e instruções de acesso
  (itens 16–18).
- **GUIDE.md:** seções 2, 3, 4, 5, 13 e uma seção nova "Arquivos e permissões".

**Riscos e testes:**
- **Migração de armazenamento:**
  - Testar num Android 9, num 10 e num 13+, instalando **por cima** da versão atual.
  - Conferir que os backups continuam visíveis e que um SAVE novo cai em
    `Documentos/MyRobots/<robô>`.
- **SAF:** conectar a pasta, apagar a pasta pelo gerenciador de arquivos, reabrir o app. Ele
  precisa pedir a pasta de novo sem travar.
- **Com os robôs reais:** SAVE e LOAD depois da migração (o arquivo precisa sair idêntico, com
  `fc /b`) e um LOAD de um arquivo com acento no comentário.

**Estimativa:** A = G. B, D = P. C, E = M.

---

## Fase 0-C: Estrutura de testes (skill testing-setup)

**Objetivo:** ter uma rede de testes para o que protege dados e para a lógica da linguagem AS,
antes de mexer nos parsers e no armazenamento.

**Diferenças em relação à skill:** a `testing-setup` manda, por padrão, instalar Hilt, testes de
tela com Robolectric, screenshot e Jacoco. Pelo escopo que você definiu (dados + lógica AS, sem
testes de tela), fica **só JUnit4 local + `MigrationTestHelper` no aparelho**. Os ViewModels já
recebem as dependências por factory, e os testes de lógica pura não precisam de injeção de
dependência. Jacoco fica como opcional para depois.

**Situação atual:** já existem `AsProgramBlocksTest` (11 testes JVM, `:core:common`) e
`MigrationTest` (v4, `:core:database`, ainda não rodou num aparelho). O resto são os exemplos
do template no `:app`.

**Passos (um commit cada):**

1. **Rodar o `MigrationTest` num aparelho** (`:core:database:connectedDebugAndroidTest`) e anotar
   o resultado. Na Fase 2.1, ele ganha o caso 4→5 (e 5→6, se a senha cifrada vier depois).
2. **Arquivo de exemplo real.** Um SAVE/FULL anonimizado (IPs e nomes trocados) em
   `core/common/src/test/resources/`, com programas, `.TRANS`, `.REALS`, `.sprdb`, `.ERRLOG`,
   `.OPELOG` e `.PGM_EDT_LOG`. Serve de base para todos os testes abaixo (e responde a pergunta
   da BASE).
3. **Testes de caracterização primeiro, sem mudar comportamento.** Os parsers saem do
   `RobotDashboardViewModel` e do `RobotRepository` para o `:core:common/ascode`, com o mesmo
   código, e ganham testes que fixam a saída atual para o arquivo de exemplo:
   - `PROGRAM_HEADER_REGEX` → `AsProgramBlocks.parseHeader` (data, hora e comentário, cada parte
     opcional);
   - `parseLogSection` e `parseErrorLog`/`buildErrorLogEntry` → `AsControllerLogs` (entrada de
     várias linhas, fim de seção sem `.END` e formatos que não batem com o parser);
   - `calculateAndApplyMetadata` → `AsBackupStats.count(content)` (`programsCount`,
     `variablesCount` e as seções de diagnóstico ignoradas por `FileUtil.sanitizeAsContent`);
   - `FileUtil.sanitizeAsContent` (seções desconhecidas sem `.END`).
4. **`PointTransform`** (`:feature:codeeditor`, já é Kotlin puro). Adicionar
   `testImplementation(libs.junit)` e testar:
   - `applyPointShift` com LMOVE, JMOVE e ambos;
   - `applyPointMirror` em X, Y e Z;
   - ponto usado por outro programa, que precisa ser ignorado e reportado;
   - formatação dos números preservada.
5. **Nome de arquivo do protocolo e extensão** (item B da 0-B).

**Feito (26/09):** passos 3, 4 e 5, além dos testes de `ExternalAsFile` e `StoredSecret`. Total:
44 testes JVM. Os testes do `PointTransform` acharam dois problemas:
- **Corrigido:** num celular em português, "Deslocar/Espelhar pontos" gravava `101,500` (vírgula),
  que o controlador não entende.
- **A confirmar com o arquivo real:** com o filtro JMOVE, um ponto de junta `#j1` não é alterado.
  A busca da definição compara `#j1` com `J1`, e a lista de seções tem `.JOINT`, enquanto o
  backup real provavelmente usa `.JOINTS`. O teste fixa o comportamento atual até lá.
6. **Na Fase 2**, cada peça nova já nasce com teste:
   - `KawasakiStreamParser` (fluxo partido em todos os pontos);
   - `LayoutOps`;
   - `rewriteHeader`/`normalizedBody`;
   - cada `ProgramRule` com **pelo menos um caso que passa e um que falha** (ex.: INZONE depois
     de LMOVE passa; INZONE sem movimento antes falha);
   - `ProjectDao.renameProject` com banco em memória (teste no aparelho).

**Como rodar:** `.\gradlew.bat testDebugUnitTest` (JVM, todos os módulos) e
`.\gradlew.bat :core:database:connectedDebugAndroidTest` (aparelho).

**Estimativa:** M.

---

## Fase 1: Navegação centrada no robô

**Objetivo:** tocar no robô leva ao painel dele, com o estado e as ações à vista.

**Situação:** passos 1 (`f45811e`), 2 (`7febb29`) e 3 feitos. O passo 3 compila, mas ainda
não foi testado no aparelho.

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
8. **Edge-to-edge (skill `edge-to-edge`), verificar em todas as telas mexidas nesta fase:**
   - campo "Enviar comando" do terminal do painel;
   - Terminal Geral;
   - barra de ações e `LineEditDialog` do `AsCodeViewer`;
   - `RobotDialog`;
   - o estado vazio novo do painel.

   Regras do checklist da skill:
   - todo campo de texto tem um pai que trata o teclado (`imePadding`, `fitInside` ou
     `contentWindowInsets` com IME), **sem padding duplo**;
   - listas usam os insets em `contentPadding`;
   - FAB fica acima da barra de navegação;
   - `Dialog` em tela cheia usa `decorFitsSystemWindows = false`.

   Achado estático: `Theme.kt:99` ainda define `window.statusBarColor`, que o Android 15+ ignora
   com edge-to-edge e é obsoleto. Remover e deixar só `isAppearanceLightStatusBars`. Conferir no
   aparelho, com teclado aberto e com navegação por gestos e por 3 botões.

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

## Fase 1.5: Pasta autossuficiente (restaurar depois de reinstalar)

**Objetivo:** a pasta `MyRobots` passa a guardar **tudo** o que o app sabe (robôs, projetos,
cabines, comandos rápidos e os nomes dos backups), e não só os `.as`. Desinstalar, reinstalar,
trocar de celular ou passar da versão do Android Studio para a da Play deixa de perder dados: o
app lê a pasta e reconstrói o banco sozinho.

**Por que agora (antes da Fase 2):** hoje, depois de uma reinstalação, só os `.as` sobram, e eles
só voltam se a subpasta tiver o mesmo nome do robô (`robotDirName`). Robô renomeado ou excluído,
projetos, comandos rápidos e nomes de backup se perdem. A Fase 2 cria os layouts de cabine e os
equipamentos, que também precisam ir para a pasta. Fazer esta fase antes evita refazer a Fase 2.

**Caso real que motivou:** a versão da Play é assinada com outra chave. Para instalar, é preciso
**desinstalar** a versão do Android Studio, e o banco vai junto.

**Módulos afetados:**
- `:core:model`: `Robot.uuid` e os modelos do arquivo (`RobotMetadataFile`, `ProjectsMetadataFile`).
- `:core:database`: coluna `uuid` nova, migração 4→5 e teste de migração.
- `:core:common`: serialização, checksum e validação dos arquivos (funções puras, testadas na JVM).
- `:core:data`: `MetadataMirror` (mantém os arquivos iguais ao banco) e `RestoreService` (lê a
  pasta e importa). Os dois usam o `RobotFileStore`, que já existe.
- `:feature:robots`: tela de boas-vindas e opção "Restaurar de uma pasta" na janela "Pasta dos arquivos".
- `:app`: decidir na abertura se mostra a tela de boas-vindas.

### Os arquivos

**Em cada pasta de robô: `<pasta>/<robô>/robo.myrobots`**

```json
{
  "format": "myrobots.robot",
  "formatVersion": 1,
  "appVersion": "1.2",
  "savedAt": "2026-10-01T23:10:00-03:00",
  "robot": {
    "uuid": "6f1c…",
    "name": "R12",
    "ip": "172.20.32.47",
    "port": 23,
    "project": "CAT Primer",
    "manufacturer": "KAWASAKI",
    "autoLogin": true,
    "loginUser": "as",
    "loginPassword": "…",
    "layoutRow": 0,
    "layoutCol": 1
  },
  "quickCommands": [ { "label": "Save full", "command": "SAVE [ROBOT]_[DATA]" } ],
  "backups": [
    { "fileName": "r12_20260920_1628.as", "backupName": "Antes da troca de bico", "timestamp": 1790000000000 }
  ],
  "sha256": "…"
}
```

**Na raiz da pasta: `<pasta>/projetos.myrobots`**

```json
{
  "format": "myrobots.projects",
  "formatVersion": 1,
  "appVersion": "1.2",
  "savedAt": "…",
  "projects": [
    {
      "name": "CAT Primer",
      "layout": { "rowCount": 2, "colCount": 2 },
      "equipment": [ { "type": "CONVEYOR", "name": "", "position": 1, "flowDirection": 1, "sortOrder": 0 } ]
    }
  ],
  "manufacturerQuickCommands": [ { "manufacturer": "KAWASAKI", "label": "…", "command": "…" } ],
  "sha256": "…"
}
```

Regras:
- **A senha vai no arquivo, em texto legível** (decidido em 01/10, para facilitar o uso).
  No banco ela continua cifrada com o Keystore (0-B.E), mas essa chave some ao desinstalar,
  então o arquivo precisa da senha aberta para a restauração trazê-la de volta. **Risco
  aceito por enquanto:** quem tiver acesso à pasta lê a senha do controlador. A segurança será
  revista depois; no futuro, essas informações podem passar a ser guardadas por usuário do app.
- **O conteúdo dos backups não é duplicado.** O texto continua nos `.as`. O arquivo só guarda o
  nome que o usuário deu, a data e o nome do arquivo. As contagens (`programsCount` etc.) são
  recalculadas na importação, como já acontece no `insertBackup`.
- **`sha256`** é calculado sobre o JSON sem o próprio campo. Serve para detectar arquivo
  corrompido ou editado à mão. Não é segurança: o arquivo é texto legível, e isso é aceitável.
- **`formatVersion`**: o app lê qualquer versão menor ou igual à dele. Versão maior: recusa com
  "Este arquivo foi criado por uma versão mais nova do MyRobots".
- Gravados como `application/octet-stream`, igual aos `.as`, para o sistema não mexer na extensão.
- O `projetos.myrobots` nasce já com `equipment` e `layout`. Até a Fase 2 existir, esses campos
  vão vazios ou com o padrão (2×2, sem equipamento).

### O UUID do robô

A importação reconhece o robô **pelo `uuid` do arquivo, não pelo nome da pasta**. Com isso,
renomear um robô deixa de quebrar a restauração.

```sql
-- MIGRATION_4_5
ALTER TABLE robots ADD COLUMN uuid TEXT NOT NULL DEFAULT '';
UPDATE robots SET uuid = lower(hex(randomblob(16))) WHERE uuid = '';
CREATE UNIQUE INDEX IF NOT EXISTS index_robots_uuid ON robots(uuid);
```

- Robô novo recebe `UUID.randomUUID().toString()` no `RobotDialog`/repositório.
- Conferir o SQL contra o `5.json` exportado e acrescentar o caso 4→5 no `MigrationTest`.
- **A Fase 2 passa a usar a migração 5→6** (ver "Mudanças de banco" da Fase 2).
- **Ponto a resolver:** ao renomear um robô, a subpasta também muda de nome (`robotDirName`).
  Verificar o que acontece hoje com os `.as` da pasta antiga e mover a pasta junto,
  incluindo o `robo.myrobots`.

### Manter os arquivos iguais ao banco (`MetadataMirror`)

- Um único lugar no `:core:data` observa o banco (robôs, comandos, backups e, na Fase 2,
  layouts e equipamentos) e regrava **só o arquivo afetado**: mudou o R12, regrava
  `r12/robo.myrobots`; mudou um layout, regrava `projetos.myrobots`.
- Agrupar mudanças rápidas (debounce de ~1–2 s) para não regravar a cada tecla.
- **Nunca deixar um arquivo pela metade:** antes de regravar, renomear o atual para
  `robo.myrobots.bak`. Na leitura, se o checksum do principal falhar, usar o `.bak`.
- Robô excluído: apagar o `robo.myrobots` da pasta dele. Os `.as` seguem a regra de exclusão que já existe.
- Rodar uma vez na primeira abertura da v1.2 (junto do `migrateFilesToNewFolderOnce`), para criar
  os arquivos de quem já tem dados.
- **Com o app instalado, o banco manda.** O espelho só escreve, nunca lê. Ler é só na restauração.

### Restaurar (`RestoreService` + telas)

**Primeira abertura com o banco vazio** (instalação nova): tela de boas-vindas com duas opções:
- **"Começar do zero"**: segue para a lista de robôs vazia, usando a pasta padrão.
- **"Já usei o MyRobots: restaurar"**: abre o seletor de pastas (`ACTION_OPEN_DOCUMENT_TREE`) já
  posicionado em `Documentos/MyRobots`, com `EXTRA_INITIAL_URI` (por exemplo
  `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/MyRobots")`).
  O usuário só toca em "Usar esta pasta".

**Por que não dá para buscar sozinho:** depois de desinstalar, o MediaStore deixa de mostrar ao
app os arquivos que ele mesmo tinha criado, e o app não consegue nem saber que eles existem. Um
toque no seletor é o mínimo que o Android permite.

**Depois de escolher a pasta:**
1. Ler `projetos.myrobots` e cada `*/robo.myrobots`, validando formato, versão e checksum.
2. Mostrar um **resumo antes de importar**, por exemplo "3 projetos, 9 robôs, 214 backups", com
   os problemas encontrados:
   - arquivo corrompido (checksum) ou de versão mais nova: listado e ignorado;
   - **subpasta com `.as` mas sem `robo.myrobots`** (pasta da v1.1, por exemplo): oferecer "Criar
     robô a partir desta pasta", com o nome da pasta e o IP em branco para preencher depois;
   - dois arquivos com o mesmo `uuid`: importar o mais recente (`savedAt`) e avisar.
3. Importar numa transação só: robôs (com IDs novos no banco e o mesmo `uuid`), comandos rápidos,
   projetos, layouts e equipamentos. Os backups vêm dos `.as`, com o nome e a data do `robo.myrobots`.
   Um `.as` sem entrada no arquivo entra como "Sinc: <arquivo>", como hoje.
4. A pasta escolhida vira a **pasta ativa (SAF)**. Assim o app continua enxergando tudo nela,
   inclusive o que ele não criou.
5. **Senhas:** voltam do `robo.myrobots` e são cifradas de novo com a chave nova do Keystore
   ao gravar no banco (o `RobotRepository` já faz isso). Arquivo sem `loginPassword` (feito à mão
   ou de outra versão): o robô volta sem senha, e o usuário preenche ao editar o robô.

**Mais tarde, a qualquer momento:** na janela "Pasta dos arquivos", a opção **"Restaurar de uma
pasta"** faz a mesma leitura em modo **mesclar**: importa só os robôs cujo `uuid` ainda não está
no banco e não mexe nos que já existem. Serve também para trazer a pasta copiada de outro celular.

### Passos (um commit cada)

1. Modelos do arquivo, serialização JSON, checksum e validação de versão em `:core:common`, com
   testes JVM: ida e volta, checksum errado, versão maior, campos faltando.
2. `Robot.uuid` + `MIGRATION_4_5` + `5.json` + caso no `MigrationTest`.
3. `MetadataMirror` gravando `robo.myrobots` e `projetos.myrobots`, com `.bak`, e a geração
   inicial para quem já tem dados.
4. `RestoreService`: leitura, resumo, problemas e importação em transação, com testes usando um
   `RobotFileStore` falso em memória.
5. Tela de boas-vindas e o fluxo de restaurar com `EXTRA_INITIAL_URI`.
6. "Restaurar de uma pasta" (mesclar) na janela "Pasta dos arquivos".
7. GUIDE.md (ver "Atualizações do GUIDE.md").

**Riscos e testes no aparelho:**
- **O teste principal:** usar o app com 2 projetos, robôs, backups com nome e comandos rápidos →
  **desinstalar** → instalar de novo → "Restaurar" → conferir que tudo voltou, inclusive os nomes
  dos backups e a senha (conectar com `autoLogin` sem digitar nada).
- Renomear um robô, reinstalar e restaurar: ele precisa voltar com o nome novo e os backups.
- Copiar a pasta `MyRobots` para outro celular (pelo PC) e restaurar lá.
- Editar um `robo.myrobots` à mão: o app precisa recusar esse arquivo e avisar, sem travar.
- Pasta da v1.1 (só `.as`, sem `.myrobots`): a opção "Criar robô a partir desta pasta" precisa aparecer.
- Desligar o celular no meio de uma gravação (ou matar o app): o `.bak` precisa salvar a restauração.

**Estimativa:** M/G.

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
**2.2 (ações)**. Cada parte pode ser entregue e testada sozinha. Antes dela, recomendo o passo
**2.pre** (rotas tipadas; ver "Navegação: migrar ou não" no fim do plano).

Em toda tela nova (Projeto, ações, comparação) e no Terminal Geral com abas, aplicar o checklist
de edge-to-edge da Fase 1 (passo 8). As regras de "Verificar erros" nascem com teste unitário
(um caso que passa e um que falha por regra; ver Fase 0-C).

### Mudanças de banco (5 → 6, todas na 2.1)

> A 4→5 ficou com a Fase 1.5 (`Robot.uuid`). Os campos e tabelas abaixo também entram no
> `robo.myrobots`/`projetos.myrobots` (o `MetadataMirror` da Fase 1.5 passa a gravá-los).

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
-- MIGRATION_5_6
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

O SQL final é conferido contra o `6.json` exportado, e o teste de migração valida com
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
Fase 0 (feita) ──► 0-B.B ──► 0-C ──► 0-B.A/C/D/E ──► Fase 1 ──► Fase 1.5 ──► 2.pre ──► 2.0 ──► 2.1 ──► 2.2
```

- **A Fase 1.5 vem antes da 2:** ela faz a migração 4→5 (`uuid`) e cria o espelho da pasta, e
  a Fase 2 só acrescenta layouts e equipamentos nele. Também precisa estar pronta **antes de
  publicar na Play**, porque trocar a versão do Android Studio pela da loja exige desinstalar.
- **0-B.B vem primeiro:** é pequeno e fecha a falha de segurança mais grave.
- **Os testes de caracterização (0-C) vêm antes** da migração de armazenamento e de mexer nos
  parsers.
- **A migração de armazenamento (0-B.A) precisa estar pronta antes de publicar**, mas não
  bloqueia as Fases 1 e 2 no seu uso interno. Se quiser a navegação nova antes, dá para fazer a
  Fase 1 logo depois da 0-B.B e da 0-C.

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
- **Seção nova "Arquivos e permissões"** (Fase 0-B): onde ficam os arquivos (MediaStore
  `Documentos/MyRobots` ou pasta SAF), o que acontece ao reinstalar, a política de nomes de
  arquivo do protocolo, os limites de tamanho e tipo ao abrir arquivo externo, a codificação
  ISO-8859-1 e como a senha do controlador é guardada.
- **Seção 0:** "Como testar" (os comandos da Fase 0-C e onde fica o arquivo de exemplo).
- **Seção nova "Pasta autossuficiente"** (Fase 1.5): formato do `robo.myrobots` e do
  `projetos.myrobots`, `formatVersion`, checksum e `.bak`, o `MetadataMirror` (o banco manda), o
  fluxo de restaurar (boas-vindas, `EXTRA_INITIAL_URI`, resumo, mesclar) e o aviso de que a
  senha fica legível no arquivo.
- **Seção 1:** `Robot.uuid`. **Seção 2:** migração 4→5 (`uuid`); a da Fase 2 vira 5→6.
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
5. ~~Alterações locais não commitadas~~: respondida (commit separado `ed78a4a`).
6. ~~`minSdk` 28 ou 29?~~ Respondida em 26/09: **`minSdk` 29**. A migração de armazenamento
   (0-B.A) não tem caminho legado e não declara nenhuma permissão de armazenamento.
7. ~~Senha do controlador~~: respondida em 26/09: **cifrar com o Android Keystore** (0-B.E).
   Depois de restaurar o backup ou trocar de celular, o app pede a senha de novo.
   **Atualizado em 01/10:** na Fase 1.5 a senha também vai aberta no `robo.myrobots`, para a
   restauração trazê-la de volta. Rever a segurança depois (talvez com usuários do app).
8. **Arquivo SAVE/FULL de exemplo:** preciso de um real, anonimizado, para os testes da 0-C, para
   confirmar a codificação dos acentos (0-B.D) e para a pergunta da BASE (item 1).

## Navegação: migrar ou não (antes da Fase 2)

Hoje o app usa Navigation Compose 2.8 com rotas em texto (`"robot_dashboard/{robotId}/{backupId}?feature={feature}"`,
`URLEncoder` à mão para o nome do projeto, `DashboardFeature.valueOf(texto)`). A Fase 2
acrescenta pelo menos duas rotas e mexe em outras quatro.

| Opção | Custo | Risco | Ganho |
|---|---|---|---|
| Manter rotas em texto | nenhum | médio: erros de digitação só aparecem rodando (o `?feature=Logs` da Fase 0 é um exemplo), e o R8 pode quebrar `DashboardFeature.valueOf` (ver R8 abaixo) | nenhum |
| **Rotas tipadas do Navigation 2** (`@Serializable` + `composable<Rota>`) | **P/M**: plugin `kotlinx-serialization`, uma classe por rota, trocar os `navigate(...)` da `MainActivity` | baixo: mesma biblioteca, mesmo `NavHost`, dá para migrar rota por rota | argumentos checados na compilação, fim do `URLEncoder` à mão e do enum por texto |
| Navigation 3 | **G**: reescrever o `NavHost` com a pilha controlada pelo app | médio/alto: API e modelo diferentes, e a Fase 2 ficaria em cima de uma migração recente | cenas adaptativas (lista + detalhe), que só fazem sentido em tablet |

**Recomendação:** passo **2.pre** = migrar para **rotas tipadas do Navigation 2** antes da Fase 2,
num commit por rota, sem mudar comportamento. **Não** migrar para o Navigation 3 agora. Por
isso a skill `navigation-3` não precisa ser instalada.

## Programa PC de contadores (pendente, combinado em 02/10)

O controlador não guarda tempo de pistola aberta nem quantos programas rodaram. Para o
gráfico de uso ter esses indicadores, um programa de fundo no robô vai acumulá-los em
variáveis, que entram em todo SAVE/FULL:

- **Tempo com a saída 1 ligada** (gatilho da pistola, `sig_fluid = 1` no R10) e quantas vezes
  ela abriu.
- **Quantidade de programas executados em modo automático** (repeat).

Pontos a decidir antes de escrever: o slot (no R10, `AUTOSTART3.PC` e `AUTOSTART4.PC` estão
desligados), se a saída 1 é a pistola em todos os robôs e testar antes no K-ROSET. O app passa
a ler essas variáveis de cada backup e mostra no gráfico do "Uso do robô" como mais uma medida
(um gráfico por medida, sem dois eixos). O histórico só começa a partir da instalação.

## Antes do APK de release (item do fim)

- **Senha aberta no `robo.myrobots` (Fase 1.5):** decidir se continua assim na versão
  distribuída ou se passa a ser cifrada com uma senha do usuário / conta do app.

- **R8 (skill `r8-analyzer`, a instalar quando for gerar o release):** hoje
  `isMinifyEnabled = false`. Ao ligar:
  - conferir as regras do Room (vêm com a biblioteca);
  - conferir a reflexão em `CursorWindow.sCursorWindowSize` (é classe do sistema, o R8 não
    renomeia, mas testar);
  - **cuidado com rotas em texto e `enum.valueOf`**: `DashboardFeature.valueOf("Terminal")` com o
    texto fixo na `MainActivity` quebra se o R8 renomear o enum. O 2.pre resolve.
  - Gerar o release, instalar e passar pelas telas com um robô real.
- **Adaptive (tablet):** fora do escopo. Só se decidir usar o app em tablet.

## Verificação ao final de cada fase

- `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain --continue` sem erros. Os testes
  JVM novos ficam em `:core:common` e `:core:network`.
- `.\gradlew.bat :core:database:connectedDebugAndroidTest` com o celular ligado (Fases 0 e 2.1).
- Instalar o APK **por cima** da versão anterior no seu celular, sem desinstalar, e conferir que
  robôs, backups e comandos continuam lá.
- Rodar a lista "com os robôs reais" da fase.
- Atualizar o GUIDE.md no mesmo conjunto de commits da fase.
