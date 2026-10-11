# Plano mestre do MyRobots

> Documento vivo. **Marque `[x]` quando terminar** e escreva a data e o commit ao lado.
> Atualizado em 09/10/2026 (fluxogramas de Clientes, Cliente e Linha, como ficaram na v1.3) na branch
> `melhorias/v1.3-clientes` (último commit `93beca9`).
>
> Detalhes técnicos que já existem **não são repetidos aqui**, só referenciados:
> - `docs/PLANO_V1_2.md`: fases 0, 0-B, 0-C, 1, 1.5 e 2, com passos, migrações e testes.
> - `docs/FLUXO_APP.md`: cada tela atual (T1…T12), fotos em `docs/telas/` e as revisões de
>   usabilidade (O1…O20, decisões D1…D8, fases R1…R6).
> - `docs/KIDE_COMANDOS.md`: comandos que o KIDE usa (base da biblioteca da Kawasaki).
>
> Legenda: `[x]` feito · `[~]` em andamento ou feito sem teste no aparelho · `[ ]` a fazer ·
> 🆕 = decidido nas conversas de 06–07/10 (ainda não está em nenhum outro documento).

---

## 1. Visão geral: a espinha do app

```mermaid
flowchart TD
    C["Cliente / Projeto<br/>dono das linhas · filtro"] --> L["Linha<br/>uma cabine · layout e tipos de trabalho próprios<br/>estações na ordem do processo"]
    C --> L2["Outra linha do mesmo cliente"]
    L --> E["Estação<br/>local de trabalho · 1+ robôs · tipo de trabalho<br/>3D, status, funções, alarmes"]
    E --> R["Robô<br/>Resumo · Arquivos · Sincronizar · Terminal"]
    E -. "Enviar programas" .-> E2["Outra estação"]
    CT[["Contrato da marca<br/>(o que o robô é e sabe fazer)"]] -.-> E & R
    PG[["Pack and Go<br/>(empacotar / desempacotar)"]] -.-> E
```

- **Quatro níveis para navegar:** Cliente → Linha → Estação → Robô.
- **Um cliente pode ter várias linhas.** Exemplo real: a Honda tem mais de uma cabine, cada uma com
  um layout diferente e tipos de robô e de trabalho diferentes (uma de pintura, outra de manipulação).
  Cada linha tem as estações dela.
- **Atalho:** se o cliente tem uma linha só, o app abre direto nas estações dela, sem a tela
  intermediária. O mesmo vale quando existe um cliente só.
- **Processo não é um nível:** ele vive dentro de **cada Linha** e define:
  - a **ordem** em que as estações aparecem na Linha;
  - os **filtros**;
  - as **ligações de reaproveitamento** entre estações (também entre linhas diferentes do mesmo cliente, com aviso). É o mestre/escravo de hoje. Na tela ficou
    "**Primer CAT → Top Coat CAT**" com o botão **Enviar programas** (v1.3, 09/10).
- **As ligações são só trabalho de arquivo OFFLINE pelo app:** copiar, duplicar e transferir
  programas, base e frames. O app **não** entra no online do equipamento nem na comunicação entre
  robôs durante a produção. Isso está **fora de escopo**.
- **Dois serviços atravessam tudo:**
  - **Contrato da marca** (`RobotCapabilities`): o que cada robô é e o que sabe fazer. As telas só
    perguntam ao contrato, para Fanuc, ABB e Universal entrarem depois **sem mexer nas telas**.
  - **Pack and Go**: leva uma estação inteira num arquivo só.

## 2. Regras gerais (valem para todas as telas)

- [ ] 🆕 **Tipo de trabalho da estação é uma lista aberta.** Começa com Pintura, Solda, Manipulação
      e Selagem, e dá para criar tipos novos nas Configurações.
- [ ] 🆕 **Ocultar sem apagar.** Um cliente, linha ou estação antigo some da tela inicial, mas os backups
      e as configurações continuam guardados. Liga e desliga quando quiser.
- [ ] 🆕 **Filtros** numa seção própria: cliente, **linha**, marca do robô, tipo de trabalho, data
      (último backup, criação), status e o que mais for útil.
- [ ] **Paleta fixa** (D8): desligar as cores dinâmicas do Android 12+. Verde, amarelo e vermelho
      ficam só para status.
- [ ] **Nomes em português** (D7): sem "Sinc:", "No app / No robô", "Backup em uso".
- [ ] **Um só caminho de envio** (D3): Escolher → Conferência → Enviar → Resultado ✓/✗ por robô.
- [ ] **Um só jeito de excluir** (D4): "Apagar também no robô" em Programas, Variáveis e Data Bank.
- [ ] **Itens do sistema recolhidos e por último** (D6): `!…`, `autostart*`, `comment___`.
- [ ] **Uma só peça de conexão** (D2): LED, estado e toque para conectar, igual em todo lugar.
- [ ] **Formulários longos em tela cheia** e botões acima da barra do Android (D5).

## 3. Pack and Go 🆕

- Exporta uma **estação**, uma **linha** inteira ou um **cliente** num **arquivo único**: backups de cada robô,
  layout, equipamentos, ligações, comandos e configurações.
- Importar é o caminho contrário: o arquivo é desempacotado e a estação aparece **pronta e
  funcionando**, como uma mala fechada.
- **Tela de opções antes de empacotar** (caixas de marcar):
  - [ ] incluir senhas de login (sim/não);
  - [ ] incluir arquivos 3D e fotos (sim/não);
  - [ ] backups: histórico completo **ou** só o último de cada robô.
- **Regra de ouro:** o que foi marcado para ir vai **completo**. Quem recebe roda igual, sem
  configurar mais nada.
- **Compatibilidade para trás (regra firme):** o pacote carrega a versão do formato. **Toda versão
  futura do app abre qualquer pacote antigo**, limitada ao que existia na época. Toda mudança no
  formato vem com teste de um pacote antigo.
- **Base técnica:** usa o mesmo formato dos arquivos `robo.myrobots` e `projetos.myrobots` da
  Fase 1.5. O Pack and Go é a pasta da estação, com esses arquivos, compactada com um manifesto.
  Por isso a **Fase 1.5 vem antes**.

---

## 4. Status atual

### Feito

- [x] **Fase 0**, segurança: migrações reais, schema exportado, sem backup falso, rename `Logs → Terminal`.
- [~] **Fase 0-B**, Play e segurança: armazenamento (MediaStore + pasta escolhida), nomes de
      arquivo seguros, "abrir com" validado, senha cifrada com Keystore. **Falta testar no aparelho.**
- [~] **Fase 0-C**, testes: parsers AS, `PointTransform`, `ExternalAsFile`, `StoredSecret`.
      **Falta rodar o `MigrationTest`.**
- [~] **Fase 1**, navegação: o toque no robô abre o painel; LED compartilhado. Passos 1 a 3 feitos,
      **falta testar o 3 no aparelho**.
- [x] **Fase 2.1**, cabine visual: tela de Projeto, grade, editor de layout e equipamentos (banco v5).
- [x] Série do robô e checagens do login (ID, TIME, FREE), banco v6.
- [x] Pares mestre/escravo de robôs e projetos (banco v7), tela Mestre/Escravo com offset e frame.
- [x] Transferir mestre → escravo com conferência; Duplicar programa em grupo; Backup de todos;
      Comando para todos; mini terminais.
- [x] Painel do robô: cartão do controlador, status geral, por eixo, uso (gráfico + Excel),
      memória, "Atualizar" (SAVE/FULL conferido), Histórico.
- [x] Comparar com o robô; variáveis sem uso; apagar no robô conferido.
- [x] Editor: Alterar por instrução, Inserir, Substituir, pesquisa rápida, Deslocar/Espelhar.
- [x] Variáveis e Data Bank em cartões, com envio em lote; Data Bank com edição em lote.
- [x] Ponte Wi-Fi do K-ROSET (portas 2301–2309); escuta KIDE ↔ K-ROSET; biblioteca de comandos.
- [x] LOAD que não trava o controlador + protocolo de testes; barra do topo padronizada.
- [x] `docs/FLUXO_APP.md` com as 87 fotos.

### Pendente (já conhecido)

- [ ] Testes no aparelho da Fase 0-B (pasta, "abrir com", senha, migração da pasta antiga).
- [ ] Rodar o `MigrationTest` (v4 → v7) no aparelho.
- [ ] **Fase 1.5**, pasta autossuficiente. Só o plano existe, nada de código.
- [ ] Fase 2.0 (infraestrutura do terminal) e o que resta da 2.2: confirmar no `PLANO_V1_2.md` o que
      já foi coberto pelos commits de 02 a 05/10.
- [ ] 0-B.D: acentos nos arquivos AS (precisa de um SAVE/FULL real).
- [ ] 0-B.F: Play Console (política de privacidade, Data Safety). É com você.
- [x] Colocar no repositório o `FLUXO_APP.md` revisado (com as propostas de 06/10). `ffc6f36`, 07/10/2026.
- [ ] Revisão de segurança da senha aberta no `robo.myrobots` (aceita em 01/10; rever antes da release).

### Perguntas em aberto

- [ ] Regra do INZONE: quais movimentos contam como "movimento antes"?
- [ ] "Copiar a base": formato da BASE no SAVE/FULL e se robôs de lados opostos têm base diferente.
- [ ] LOAD sobre programa existente: o controlador pede confirmação?
- [ ] Mais de um equipamento por cabine (o banco já aceita; confirmar o uso).
- [x] Limite de "backup antigo": **configurável** em Tempos e status (G4), padrão 7 dias (09/10).
- [x] 🆕 Pode existir mais de uma Linha por Cliente? **Sim** (respondido em 07/10, exemplo da Honda).

---

## 5. Plano por fases (na ordem de fazer)

> Cada fase deixa o app funcionando. Uma não desmancha o que a anterior fez.
> Ao terminar uma fase: testar no aparelho, atualizar o `GUIDE.md` e marcar aqui.

### Fase A: fechar a base atual (antes de qualquer reestruturação)

- [ ] A1. Testes no aparelho da 0-B e da Fase 1 passo 3.
- [ ] A2. `MigrationTest` v4 → v7.
- [ ] A3. **Ajustes rápidos (R1 do FLUXO_APP):**
  - [ ] tirar o "Sinc:" dos nomes (O13);
  - [ ] esconder `dup_*.as` no Histórico (O15);
  - [ ] tirar `comment___` das listas (O16);
  - [ ] itens de sistema por último (O19, D6);
  - [ ] botões acima da barra do Android (O20);
  - [ ] comandos rápidos em português e sem "Load File" (O9);
  - [ ] apagar a rota `variable_viewer` (O5);
  - [ ] tirar o "olho" da lista de programas;
  - [ ] "Salvar / ✓ Salvo" no editor;
  - [ ] "Não perguntar de novo" no relógio (G2);
  - [ ] regra nova do "Sem sinal": amarelo só se o robô não responder a algo enviado (O17).

### Fase B: isolar o Contrato da marca

> Vem antes da hierarquia nova porque quase todas as telas dependem dele. Não muda nada na tela.

- [ ] B0. **Levantamento:** gerar `docs/ACOPLAMENTO_KAWASAKI.md` com cada lugar, **fora** da camada
      da marca, onde a Kawasaki está grudada. Procurar: `SAVE/`, `LOAD`, `DELETE/`, `.PROGRAM`/`.END`,
      `.sprdb`, `.TRANS`/`.REALS`/`.STRINGS`, `FRATE`/`PATTERN`/`ATOMIZE`/`HVOLT`,
      `.ERRLOG`/`.OPELOG`/`.PGM_EDT_LOG`, porta `23`, textos em inglês e `when (manufacturer)`.
      Para cada achado: arquivo, linha, o que é e para onde vai.
- [ ] B1. **Definir o contrato** (`RobotCapabilities`, em `:core:model`):
  - **O que o robô é:** porta padrão, como faz login, **tipos de arquivo que tem** (Programas,
    Variáveis por tipo, Data Bank, Logs…), tipos de variável, grupos de programa.
  - **O que o robô sabe fazer:** backup completo, enviar arquivo, apagar programa/variável, ler
    status, ler memória, ler série, achar início/fim de programa no backup, ler cada log.
  - **Biblioteca:** comandos rápidos padrão e termos da pesquisa do editor.
  - **Regra:** se a resposta muda de uma marca para outra, é contrato. Se é igual para todas, fica na tela.
- [ ] B2. Criar `KawasakiCapabilities` movendo para ele o que o B0 achou. Nenhuma tela muda de aparência.
- [ ] B3. A aba Arquivos monta os quadrados a partir da lista do contrato, e não de uma lista fixa.
- [ ] B4. **Critério de pronto:** para uma marca nova, os arquivos a criar são **só a biblioteca
      nova**. Registrar a resposta no `ACOPLAMENTO_KAWASAKI.md`.
- Fora de escopo: implementar Fanuc, ABB e Universal. Elas entram quando houver um robô real.

### Fase C: hierarquia Cliente → Linha → Estação 🆕

- [~] C1. Banco v8:
  - tabela `clients` (nome, `hidden`, ordem);
  - tabela `lines` (`clientId`, nome, `hidden`, ordem). O **processo** é desta tabela: a ordem das
    estações e as ligações valem dentro da linha;
  - `projects` vira **estação** (`stations`), com `lineId`, `workTypeId`, `sortOrder` (a ordem
    do processo) e `hidden`;
  - tabela `work_types`, já com Pintura, Solda, Manipulação e Selagem;
  - as ligações mestre/escravo continuam, renomeadas para "reaproveita os programas de". Valem
    **dentro da mesma linha** e também **entre linhas diferentes do mesmo cliente** (decidido em
    07/10). A tabela de ligações guarda as duas estações, sem exigir que estejam na mesma linha.
  - Migração 7 → 8: cada projeto atual vira uma estação, dentro de uma linha "Linha 1" de um
    cliente "Meu cliente". Os pares mestre/escravo atuais viram ligações dessa linha.
      **08/10/2026, `8d45090`:** feito; a estação continua sendo `project_layouts` (sem tabela `stations`) e as ligações continuam em `masterProject`. Falta rodar o `MigrationTest` no aparelho.
- [~] C2. Ocultar e mostrar cliente, linha ou estação (nada é apagado). Os ocultos ficam numa lista
      própria nas Configurações.
      **08/10/2026, `cb1fe7f`:** feito; os ocultos ficam em "Mostrar ocultos" na própria tela (cliente, linha e estação), não nas Configurações.
- [~] C3. Seção de **filtros**: cliente, linha, marca, tipo de trabalho, data, status.
      **08/10/2026, `cb1fe7f`:** tipo de trabalho, linha, status e marca. Filtro por cliente e por data ainda não.
- [~] C4. Tipos de trabalho editáveis nas Configurações.
      **08/10/2026, `cb1fe7f`:** só criar ("Novo tipo…" no ⋮ da estação). Renomear e apagar tipos nas Configurações ainda não.
- [ ] C5. Atualizar o `GUIDE.md` e o `FLUXO_APP.md` (Projeto → Estação, com Cliente e Linha acima).

### Fase D: pasta autossuficiente (Fase 1.5 do PLANO_V1_2)

> Depois da C, para os arquivos já nascerem com cliente, estação e tipo de trabalho.

- [ ] D1. `Robot.uuid` + migração.
- [ ] D2. `robo.myrobots` e `projetos.myrobots` (agora com cliente, linha, estação, tipo, ordem,
      oculto e ligações), com `formatVersion` e checksum.
- [ ] D3. `MetadataMirror`: o banco manda, a pasta acompanha; cópia `.bak`.
- [ ] D4. Restaurar: tela de boas-vindas, seletor já em `Documentos/MyRobots`, resumo e importação.
- [ ] D5. Teste: usar → desinstalar → reinstalar → restaurar → tudo volta.

### Fase E: Robô em 4 abas + envio e exclusão iguais (R2 e R3)

- [ ] E1. Robô com abas fixas **no rodapé** (perto do polegar): **Resumo**, **Arquivos**,
      **Sincronizar** e **Terminal**, com a linha de conexão fixa acima das abas.
- [ ] E1b. 🆕 Programas em ordem do **número do pg** por padrão, com opção de ordenar por nome e
      por data de alteração (decidido em 09/10).
- [ ] E1d. 🆕 **Uso do robô clicável** (09/10): tocar numa barra troca os números de cima (Em
      operação, Ligado, Motor ligado) pelos valores **daquele dia**, com a data; tocar de novo volta
      à média do período. Hoje o toque só mostra uma linha de texto e os números ficam na média
      (`RobotUsageCard`).
- [ ] E1c. 🆕 **Carregar arquivo no Sincronizar** (09/10): o mesmo Carregar da Estação (robôs →
      arquivo → o que vai → conferir), aberto com **só este robô marcado** (dá para marcar outros
      da estação). Analisa e deixa escolher qual parte mandar.
      Técnico: o `LoadDialog` está em `:feature:project` e o painel em `:feature:dashboard`, e
      uma feature não depende de outra. Virar uma tela própria (rota `load/{estação}?robo={id}`
      ligada no `NavHost`) ou ir para um módulo comum.
- [ ] E2. "Backup em uso" com data e Trocar; **o backup novo (Fazer backup agora) vira o em uso sozinho**; Histórico novo (data como título, ⋮, agrupado por mês).
- [ ] E3. **Conferência única** (D3) também no Enviar do painel (hoje ele substitui sem avisar).
- [ ] E4. Comparar com Enviar, Trazer e Apagar em todos os grupos.
- [ ] E5. Exclusão igual em todas as listas (D4).

### Fase F: Linha e Estação 🆕

- [~] F0. **Tela inicial = Clientes:** cada cliente com as linhas dele e um resumo (ativos e
      alarmes). Tocar abre o cliente. Com um cliente só, abre direto nele.
      **08/10/2026, `cb1fe7f`:** feito, com atalhos de um cliente e de uma linha. Sem alarme ao vivo: mostra só os conectados.
- [~] F0b. **Cliente:** as linhas (cabines) dele, cada uma com uma mini-planta, o tipo de trabalho
      e o resumo. Com uma linha só, abre direto nela.
      **08/10/2026, `cb1fe7f`:** feito.
- [~] F1. **Linha:** estações na ordem do processo, mini-cabine com uma cor por
      robô, ativos e alarmes, setas de "reaproveita os programas de". Filtros no topo.
      **08/10/2026, `cb1fe7f`:** feito sem 3D: mini grade com um quadrado por robô na cor do status, ligações e Transferir. Filtros só na tela inicial.
- [ ] F2. **Estação:** resumo no topo (conectados, sem sinal, backup mais antigo), cabine com a data
      do último backup em cada robô, **Funções** num bloco (Backup de todos, Enviar backups,
      Carregar, Comando, Duplicar programa, Enviar programas), **Ligações** dentro da estação (no
      lugar da tela Mestre / Escravo) e **Atividade** (mini terminais). Ver 6.3.
      **Sem seção de alarmes** (decidido em 09/10): alarme aparece só no painel do robô, como hoje.
- [~] F3. **3D:** carregar um `.glb` de braço robótico genérico com SceneView/Filament. Começa como
      efeito visual: anel de status no chão, movimento quando ativo, pisca em alarme. No futuro, os
      ângulos dos eixos podem vir do robô. Se o aparelho não suportar 3D, mostrar a grade 2D atual.
      🆕 **10/10:** o 3D é um **modo do bloco da cabine** (seletor Cabine 2D | 3D), não uma tela
      separada; tela cheia com o cartão do robô tocado. Desenho: artifact "Estação com modo 3D".
      Plano do motor 3D e do montador de robô: `docs/GEMEO_DIGITAL.md` e `referencias/README.md`.
      **10/10/2026, `61d6717`/`c7cdc6d`:** fase 1 feita (o Filament dentro do app): módulo
      `:feature:robot3d` com Filament 1.75.1 (a 1.76+ exige Kotlin 2.4), tela "Visualizador 3D
      (teste)" pelo ⋮ da Estação com o robô de teste do `:core:kinematics` (um controle por eixo),
      abrir .glb pelo seletor, câmera por gestos, vistas Iso/Topo/Frente, grade e eixos com Z para
      cima. Testado no celular (Galaxy S25 Ultra) em 10/10/2026: 120 quadros/s, com o robô de
      teste e com o KJ264 convertido do STEP (167 mil triângulos). Falta: o modo 3D do bloco da
      cabine, anel de status e a volta para a grade 2D em aparelho sem 3D.
- [ ] F3b. 🆕 **Estação montada em 3D (futuro, opcional):** montar a estação inteira no 3D (robôs,
      trilhos, suportes, pistolas, equipamentos) e, numa opção **Usar a vista aérea no layout**,
      gerar a cabine 2D olhando de cima. A vista vira posições e contornos de cada item (não uma
      foto), para o layout continuar nítido e o toque em cada robô funcionar. Estação sem nada
      montado em 3D continua com o layout 2D de hoje.
- [ ] F3c. 🆕 **Programas em 3D (objetivo final do 3D):** escolher um programa e ver a trajetória
      sobre a linha (pontos na ordem, setas de direção, trecho com a pistola ligada destacado,
      ponto fora do alcance em vermelho), no estilo do DXQ da Dürr; tocar num ponto seleciona a
      linha no editor e vice-versa; mover pontos no 3D; simular com barra de tempo. O montador de
      robô é o primeiro passo. Exige já: base e ferramenta (BASE/TOOL) de cada robô na cinemática
      e um leitor de trajetória em `:core`, porque a busca de pontos LMOVE/JMOVE de hoje está no
      `PointTransform` de `:feature:codeeditor` e uma feature não pode usar outra.
- [~] F3d. 🆕 **Montador de robô (ferramenta de montagem):** o primeiro passo do F3c. Trabalha peça
      por peça e não exige o robô na posição zero. Desenho: fileira "Montador de robô · KJ264" do
      artifact "Estação com modo 3D".
      **10/10/2026, `3d6a376`/`eaeb9df`/`8ca423d`:** primeira versão (rota `robot3d_assembler`, pelo
      ⋮ do Visualizador 3D): passos 1, 2, 3 (posição e giro em Z), 4 com **Círculo** e **2 pontos**,
      5, 6 e 7 (flange), Isolar, salvar no aparelho. No PC, o Círculo acha os 6 eixos do KJ264 com
      erro 0,00 mm. Também: toque e realce de peça e a vista Lado no Visualizador (fase 2 do 3D).
      Falta: testar no aparelho; Aresta e Vértice; Mover, Girar e Fixar (peças fora da posição de
      montagem); zero do eixo diferente da pose do arquivo; TOOL do controlador no TCP.
  1. **Carregar o robô 3D** (.glb com as peças ou um arquivo por peça), com o aviso de que o robô
     precisa vir dividido: base, uma peça por eixo, ferramenta. Peça única não monta.
  2. **Dizer o que é cada peça:** tocar e escolher Base, Eixo (numerado: Eixo 1, 2, 3…),
     Ferramenta ou Outro. Peças sem tipo ficam marcadas.
  3. **Base:** fixar e posicionar em relação ao zero do espaço 3D (X, Y, Z, giro) ou pôr no zero.
  4. **Criar o eixo com a peça isolada:** rastreio de **Círculo** (face redonda → centro e
     direção), **Aresta**, **Vértice** ou **2 pontos** (frente e trás da junta). O app sugere, o
     usuário confirma ou corrige.
  5. **Limites e teste:** sentido positivo, mínimo e máximo, controle que gira a peça na hora.
  6. **Eixos na ordem** (base ao eixo 6), com Testar em cada um e Testar todos.
  7. **Flange e ferramenta (TCP)**, com os valores do TOOL do controlador.
  - Ferramentas de peça em qualquer passo: Isolar, Mover (setas), Girar (anéis) e Fixar.
- [ ] F4. Remover o Terminal Geral (o "Comando para todos" já mostra a resposta de cada robô) (O8, O18).
- [ ] F5. Ligações em linhas compactas; tirar o texto "→ C01 → R16" dos cartões.
- [~] F6. 🆕 **Reaproveitar entre linhas diferentes** (mesmo cliente):
  - dentro da mesma linha: ligação natural, sem aviso extra;
  - entre linhas diferentes: ao **criar a ligação** e ao **executar** (Transferir, Duplicar para
    outra linha), mostrar o alerta "Atenção: esta linha é diferente e não está no mesmo processo.
    Tem certeza que deseja reaproveitar/transferir?" com **Cancelar** e **Continuar mesmo assim**;
  - na Conferência, uma faixa amarela fixa "Origem e destino em linhas diferentes" acima da lista;
  - na tela da Linha, a ligação para outra linha aparece com a etiqueta da linha de destino
    (ex.: "→ Honda · Cabine 2"), com o traço diferente da ligação interna.
      **08/10/2026, `cb1fe7f`:** aviso ao transferir e ao criar ou trocar o mestre em Mestre / Escravo; faixa amarela na conferência; ⇄ amarelo e seção tracejada na Linha. O Duplicar é dentro de uma estação, então não cruza linhas.
- [ ] F7. 🆕 **Ajustes da revisão da v1.3** (09/10):
  - [ ] **LED da estação e da linha:** hoje "Desligado ganha de tudo", e um robô desligado deixa a
        estação cinza mesmo com os outros conectados. Regra nova: **amarelo** se algum robô está
        sem sinal; senão **verde** se algum está conectado; **cinza só se todos** estão desligados.
        Mudar o `ClientTree.worstState` (e o nome, porque deixa de ser "o pior") e o `ClientTreeTest`.
  - [ ] **Robô novo com estação nova:** hoje cai na linha usada por último. Pela tela inicial,
        perguntar a linha quando o nome da estação for novo; pela Linha, continua nela.
  - [ ] **Segurar um robô na Linha** conecta ou desconecta só ele, igual à Estação (D2). Hoje a
        Linha só tem o toque.
  - [ ] Tirar **Mestre / Escravo** do ⚙ da tela inicial (a configuração vai para a Estação, ver 6.3).
  - [ ] Rodar o `MigrationTest` 7 → 8 no aparelho.

### Fase G: Nova estação + Perfil da marca + Configurações 🆕

- [ ] G1. **Assistente de Nova estação** (nunca se cadastra um robô solto):
      1) Cliente, linha e estação (nome, tipo de trabalho), escolhendo os que existem ou criando novos → 2) Marca (sem suporte = "em breve") →
      3) Layout (tamanho, transportador) → 4) Robôs (nome, IP e **Testar**, que conecta, faz login e lê a série).
- [ ] G2. **Perfil da marca** (configurações avançadas), lendo o contrato: tipos de variável, tipos
      e grupos de programa, logs disponíveis, comandos padrão e pesquisa do editor. Substitui a
      tela "Fabricantes".
- [ ] G3. **Configurações** pela engrenagem: Perfil da marca, Tipos de trabalho, **Tempos e status**,
      Pasta dos arquivos, Wi-Fi, Ocultos, Tema, Restaurar.
- [ ] G4. 🆕 **Tempos e status** (pedido em 09/10): uma tela nas Configurações para ajustar os tempos
      que mudam o status no app. Cada valor muda de verdade o comportamento do app (nada é só
      enfeite), tem um padrão e um botão "Voltar ao padrão". Hoje esses tempos estão fixos no código:
  - [ ] **Backup antigo:** a partir de quantos dias o backup do robô fica vermelho (padrão 7 dias).
        Vale na Estação, na cabine e no Histórico.
  - [ ] **Sem resposta:** quanto tempo sem receber nada do robô para ele virar "Sem sinal" (hoje
        `HEARTBEAT_STALE_AFTER_MS` = 8 s, no `KawasakiTerminalManager`).
  - [ ] **Intervalo da sondagem:** de quanto em quanto tempo o app pergunta se o robô está vivo
        (hoje `HEARTBEAT_PING_INTERVAL_MS` = 3 s).
  - [ ] **Espera ao conectar:** quanto tempo o app mostra "Conectando…" antes de dar como falha
        (hoje 7 s na lista de robôs e 10 s na Linha; unificar num valor só).
  - [ ] **Transferência parada:** tempo sem bloco do robô para encerrar um SAVE/LOAD (hoje
        `TRANSFER_STALL_MS` = 30 s).
  - [ ] **Limites das operações:** Comando (30 s), LOAD (5 min) e SAVE/backup (15 min), hoje no
        `ProjectOperations` e no `RobotCommands`.
  - [ ] Guardar os valores num lugar só (`AppSettings`, em DataStore ou numa tabela), lido pelo
        `KawasakiTerminalManager` e pelo `:core:data`, com limites mínimo e máximo para ninguém
        travar um controlador com um tempo curto demais.
  - [x] Os tempos valem **para o aparelho todo** (decidido em 09/10); não entram no Pack and Go.

### Fase H: Pack and Go 🆕

- [ ] H1. Formato do pacote: manifesto com `formatVersion`, a versão do app e a lista do que vai
      dentro, mais as pastas da estação no formato da Fase D.
- [ ] H2. Tela de opções: senhas, 3D/fotos, histórico completo ou último backup.
- [ ] H3. Exportar: compartilhar pelo Android ou salvar numa pasta.
- [ ] H4. Importar: abrir o arquivo → resumo → criar o cliente e a estação prontos. Se já existir,
      perguntar se substitui ou cria uma cópia.
- [ ] H5. Testes de compatibilidade: guardar pacotes de exemplo de cada versão do formato em
      `core/.../test/resources/packs/` e garantir que todos continuam abrindo.

### Fase I: listas e logs (R6)

- [ ] I1. Variáveis compactas e com chips de filtro (Todas, Sem uso, Posições, Juntas, Reais, Textos).
- [ ] I2. Data Bank em formato de planilha.
- [ ] I3. Log de Erros agrupado (×N) e com filtro por gravidade.
- [ ] I4. Log de Edição em campos; tocar abre o editor no programa e no step.
- [ ] I5. Comandos agrupados por categoria.
- [ ] I6. Editor: barras recolhíveis; ações de edição só com linha marcada; rolagem lateral
      sincronizada. **Combinar com o agente do Android Studio** (`.agent/plan.md`), que já está mexendo nisso.

### Antes da release

- [ ] 0-B.D (acentos), 0-B.F (Play Console), R8/minify, revisão da senha aberta nos arquivos e
      no Pack and Go, paleta fixa (D8).

---

## 6. Telas: fluxograma de decisões de cada uma

> Para cada tela: o que ela mostra, cada ação e **para onde cada decisão leva o usuário**.
> Vamos preencher uma por uma. Ao detalhar, troque `(a fazer)` pelo diagrama Mermaid.

### 6.0 Clientes (tela inicial)
- [x] Fluxograma (como ficou na v1.3, `cb1fe7f` a `d8aedf0`):

```mermaid
flowchart TD
    A([Abrir o app]) --> Q1{Quantos clientes visíveis?}
    Q1 -- "1" --> Q2{Quantas linhas?}
    Q2 -- "1" --> L[[6.1 Linha]]
    Q2 -- "2+" --> C[[6.0b Cliente]]
    Q1 -- "2+ ou 0" --> T["Clientes<br/>cartões pelo último uso<br/>mini planta de cada linha"]
    T -- "tocar no cartão" --> C
    T -- "tocar num bloquinho de estação" --> E[[6.3 Estação]]
    T -- "⋮ do cliente" --> M1["Renomear · Ocultar"]
    M1 -- "Ocultar" --> H["some da lista<br/>fica em Mostrar ocultos (N)"]
    T -- "Filtro" --> F[[6.2 Filtros]]
    F -- "etiquetas no alto · tocar tira" --> T
    T -- "Novo" --> N{O quê?}
    N -- "Novo cliente / Nova linha" --> NM["pede só o nome<br/>(a linha pergunta o cliente)"] --> T
    N -- "Novo robô" --> RD["janela de cadastro<br/>estação existente ou nova"]
    RD -- "estação nova" --> RL["F7: perguntar a linha"] --> T
    T -- "⚙" --> S["Lista de robôs (antiga) · Fabricantes<br/>(Mestre / Escravo sai, F7)"]
```

- **Diferenças em relação ao protótipo de 07/10:** sem selo de alarme (o app ainda não lê alarme;
  mostra só os conectados) e sem a tela de boas-vindas (fica para a Fase D, 6.14).

### 6.0b Cliente (as linhas dele)
- [x] Fluxograma (v1.3):

```mermaid
flowchart TD
    A([Tocar num cliente]) --> Q{Quantas linhas visíveis?}
    Q -- "1" --> L[[6.1 Linha]]
    Q -- "2+" --> T["Cliente<br/>um cartão por linha: LED, nome, tipo,<br/>N estações · X de Y conectados, mini planta"]
    T -- "tocar na linha" --> L
    T -- "Nova linha" --> NL["nome"] --> T
    T -- "⋮ da linha" --> ML["Renomear · Ocultar"]
    T -- "⋮ do topo" --> MC["Renomear · Ocultar o cliente"]
    T -- "Mostrar ocultas" --> T
```

### 6.1 Linha (estações na ordem do processo)
- [x] Fluxograma (v1.3):

```mermaid
flowchart TD
    A([Abrir uma linha]) --> T["Linha<br/>estações na ordem do processo<br/>cabine com um quadrado por robô"]
    T -- "tocar num robô" --> R[[6.4 Painel do robô]]
    T -- "segurar um robô (F7)" --> X["conecta / desconecta só ele"]
    T -- "Abrir estação / tocar no cartão" --> E[[6.3 Estação]]
    T -- "Conectar (estação)" --> CN["liga os desligados<br/>vira Desconectar com todos ligados"]
    T -- "Conectar todos (barra)" --> CN
    T -- "Novo robô / ⋮ Adicionar robô" --> RD["cadastro já com a estação<br/>robô entra fora do layout"]
    T -- "entre duas estações: Enviar programas" --> Q{Mesma linha?}
    Q -- "sim" --> TR[[Estação destino com a transferência aberta]]
    Q -- "não" --> W["Linha diferente: Cancelar ·<br/>Continuar mesmo assim"] --> TR
    T -- "⋮ da estação" --> ME["Tipo de trabalho · Mover para outra linha ·<br/>Subir / Descer · Ocultar · Renomear"]
```

### 6.2 Filtros
- [ ] Fluxograma: (a fazer)

### 6.3 Estação
- [x] Proposta aprovada (09/10): resumo, cabine 2D com o backup de cada robô, Funções, Ligações e
      **Atividade recolhida** (abre sozinha quando uma função roda). **Sem alarmes** (ficam no
      painel do robô). O limite do backup antigo vem de Tempos e status (G4).
- [x] Fluxograma:

```mermaid
flowchart TD
    A([Abrir uma estação]) --> T["Estação<br/>resumo: conectados · sem sinal · backup mais antigo<br/>cabine 2D (3D na F3)"]
    T -- "tocar num robô" --> R[[6.4 Painel do robô]]
    T -- "segurar um robô" --> CN["conecta / desconecta só ele"]
    T -- "Conectar todos / Desconectar" --> CN2["todos os robôs da estação"]
    T -- "Editar layout" --> EL["editor de layout de hoje"]
    T -- "Backups" --> HB["Histórico de backups da estação"]
    T -- "Funções" --> F{Qual?}
    F -- "Backup de todos · Comando" --> P["escolher os robôs (todos marcados)"] --> AT["Atividade abre sozinha<br/>mini terminais: fila, rodando, ok, falha"]
    F -- "Enviar backups" --> ZB[".zip com o último backup de cada robô<br/>Compartilhar ou Salvar"]
    F -- "Carregar" --> LD["4 passos: robôs, arquivo, o que vai, conferir"] --> AT
    F -- "Duplicar programa" --> DP["escolher, conferir"] --> AT
    F -- "Enviar programas" --> Q{Destino na mesma linha?}
    T -- "Ligações: Enviar programas" --> Q
    Q -- "sim" --> CF[[6.9 Conferência]]
    Q -- "não" --> W["Linha diferente: Cancelar ·<br/>Continuar mesmo assim"] --> CF
    CF --> AT
    T -- "Ligações: Configurar" --> LG["destino, par de cada robô, offset e frame<br/>(a tela Mestre / Escravo de hoje)"]
    LG -- "destino em outra linha" --> W2["aviso de linha diferente"]
    T -- "⋮" --> M["Renomear · Tipo de trabalho · Mover para outra linha ·<br/>Ocultar · Pack and Go (em breve)"]
```

### 6.4 Robô: Resumo
- [x] Proposta das 4 abas aprovada (09/10): abas no rodapé, linha de conexão fixa acima delas.
- [x] Fluxograma (o robô inteiro; 6.5 a 6.7 são as outras abas):

```mermaid
flowchart TD
    A([Tocar num robô]) --> C["linha de conexão fixa<br/>LED · estado · Conectar / Desconectar"]
    C --> TB{Aba no rodapé}
    TB -- "Resumo" --> R["cartão do controlador · memória · Uso do robô"]
    R -- "ATENÇÃO · N" --> AL["alarmes dos 7 dias antes do backup"]
    R -- "Por eixo" --> PE["horas, deslocamento, temperatura, alarmes do eixo"]
    R -- "Ler agora / Conectar e ler" --> FR["FREE"]
    R -- "Exportar (Excel)" --> XL[".csv"]
    R -- "tocar numa barra do gráfico" --> DY["números de cima mostram aquele dia<br/>tocar de novo: média do período"]
    TB -- "Arquivos" --> AR[[6.5 Arquivos]]
    TB -- "Sincronizar" --> SN[[6.6 Sincronizar]]
    TB -- "Terminal" --> TM[[6.7 Terminal]]
```

### 6.5 Robô: Arquivos (Programas, Variáveis, Data Bank, Logs, Código)
- [x] Fluxograma:

```mermaid
flowchart TD
    A([Aba Arquivos]) --> U["faixa Backup em uso: data · FULL · há N dias<br/>vermelha depois do limite de G4"]
    U -- "Trocar" --> H[[6.6 Histórico: Usar]]
    A --> T{Qual?}
    T -- "Programas" --> P["ordem: nº do pg (padrão) · nome · data<br/>Sistema recolhido no fim"]
    P -- "tocar" --> ED[[6.8 Editor só do programa]]
    P -- "⋮" --> DU["Duplicar"]
    P -- "marcados: Enviar" --> CF[[6.9 Conferência]]
    P -- "marcados: Compartilhar" --> SH[".as no compartilhar do Android"]
    P -- "marcados: Excluir" --> X{"Apagar também no robô?"}
    X -- "não" --> X1["só no backup do app"]
    X -- "sim" --> X2["app + DELETE/P conferido"]
    T -- "Variáveis · Data Bank" --> V["como hoje, com o mesmo Enviar / Excluir"]
    T -- "Logs" --> L["Erros · Operação · Edição"]
    T -- "Código completo" --> ED2[[6.8 Editor com o backup inteiro]]
```

### 6.6 Robô: Sincronizar (Fazer backup, Carregar, Comparar, Histórico)
- [x] Fluxograma:

```mermaid
flowchart TD
    A([Aba Sincronizar]) --> B["Fazer backup agora"]
    B --> B1["conecta se preciso · SAVE/FULL conferido"] --> B2["backup novo vira o em uso"]
    A --> LD["Carregar arquivo"]
    LD --> L1["mesma tela do Carregar da Estação<br/>só este robô marcado (pode mudar)"]
    L1 --> L2["arquivo do aparelho ou backup de um robô"] --> L3["o que vai: escolher as partes"] --> L4["conferir por robô"] --> L5["LOAD conferido"]
    A --> CP["Comparar com o robô"] --> CP1["Só no robô · Diferentes · Só no offline · Iguais<br/>Apagar no robô (N)"]
    A --> HI["Histórico por mês, data como título"]
    HI -- "Usar" --> U["vira o backup em uso"]
    HI -- "Importar" --> IM[".as de fora para este robô"]
```

### 6.7 Robô: Terminal
- [x] Fluxograma:

```mermaid
flowchart TD
    A([Aba Terminal]) --> Q{Conectado?}
    Q -- "não" --> C["campo desligado · Conectar na linha fixa"]
    Q -- "sim" --> T["conversa com o robô · comandos rápidos em português no alto"]
    T -- "digitar / Enviar" --> R["resposta do robô"]
    T -- "SAVE / LOAD digitados" --> F["arquivo na pasta do robô"]
    T -- "pergunta do controlador" --> P["janela que precisa de resposta"]
```

### 6.8 Editor de programa
- [ ] Fluxograma: (a fazer). Hoje está em `FLUXO_APP.md` T8.

### 6.9 Conferência (envio único)
- [ ] Fluxograma: (a fazer)

### 6.10 Nova estação (assistente)
- [ ] Fluxograma: (a fazer)

### 6.11 Perfil da marca
- [ ] Fluxograma: (a fazer)

### 6.12 Pack and Go (exportar e importar)
- [ ] Fluxograma: (a fazer)

### 6.13 Configurações
- [ ] Fluxograma: (a fazer)

### 6.14 Restaurar (primeira abertura)
- [ ] Fluxograma: (a fazer)
