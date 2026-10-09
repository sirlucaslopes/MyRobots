# Tarefa: nova entrada do app (Clientes → Linha → Estação) para testar no celular

> Para o Claude Code. Em português. Escrito em 08/10/2026.
> **Antes de começar, leia:** `CLAUDE.md`, `docs/PLANO_MESTRE.md` (seções 1, 2 e as fases C e F),
> `docs/FLUXO_APP.md` (T2, T10 e as decisões D1–D8) e o `GUIDE.md`.
> Se algo aqui não bater com o código, o código vale. Registre a diferença e siga.

## Objetivo

Entregar uma **fatia vertical testável no celular** da nova organização:

**Clientes → Cliente (linhas) → Linha (estações na ordem do processo) → Estação → Robô**

- **Estação** = o "projeto" de hoje. Tocar numa estação abre a **tela de Projeto atual**
  (`project/{projectName}`), sem redesenhá-la por enquanto.
- **Robô** abre o **painel atual**.
- O visual segue o protótipo aprovado em 07/10. As telas estão descritas abaixo.

É a parte mínima das fases **C** (C1–C4) e **F** (F0, F0b, F1 sem 3D, F6) do `PLANO_MESTRE.md`.
Esta tarefa adianta a C e a F antes da B (contrato da marca); isso é aceitável porque estas telas
quase não tocam em coisas da Kawasaki.

**Fora desta tarefa:** 3D, assistente de nova estação, Pack and Go, tela de boas-vindas, robô em
4 abas, contrato da marca.
**Não mexer** no `AsCodeViewer`: o agente do Android Studio (`.agent/plan.md`) está trabalhando nele.

## Branch e jeito de trabalhar

- Criar a branch `melhorias/v1.3-clientes` a partir de `melhorias/v1.2` (puxe antes).
- Commits pequenos. Cada um compila com `.\gradlew.bat assembleDebug testDebugUnitTest`.
- No fim: `.\gradlew.bat installDebug` com o celular conectado, ou me avise para instalar.
- **Os dados atuais não podem se perder.** O teste de migração é obrigatório.

## 1. Banco: versão 7 → 8

Tabelas novas:

```kotlin
@Entity(tableName = "clients")
data class Client(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
                  val hidden: Boolean = false, val lastUsedAt: Long = 0)

@Entity(tableName = "lines", indices = [Index("clientId")])
data class ProductionLine(@PrimaryKey(autoGenerate = true) val id: Long = 0, val clientId: Long,
                          val name: String, val hidden: Boolean = false,
                          val sortOrder: Int = 0, val lastUsedAt: Long = 0)

@Entity(tableName = "work_types")
data class WorkType(@PrimaryKey val name: String, val sortOrder: Int = 0)
```

Na estação, hoje `project_layouts`, chave `projectName`, entram os campos `lineId`, `workType` (texto
ou nulo), `sortOrder` (a ordem do processo) e `hidden`.
Se for mais limpo criar uma tabela `stations`, pode; justifique no commit.

Migração 7 → 8:
- criar o cliente **"Meu cliente"** e a linha **"Linha 1"** dentro dele;
- **todo** projeto existente vira estação dessa linha, inclusive os projetos que só existem como
  texto em `robots.project` e ainda não têm linha em `project_layouts`. Criar a linha nesses casos;
- `sortOrder` das estações segue a ordem alfabética atual;
- `work_types` começa com Pintura, Solda, Manipulação e Selagem;
- os pares mestre/escravo atuais (banco v7) continuam funcionando. Eles passam a ser as
  **ligações de reaproveitamento** entre estações;
- teste no `MigrationTest`: 7 → 8 com projetos, robôs e pares mestre/escravo, conferindo que nada
  se perdeu.

O espelho na pasta (`robo.myrobots`/`projetos.myrobots`, Fase D) ainda não existe. Não precisa
mexer nele agora.

## 2. Telas

Tema atual do app, com botões acima da barra do Android (D5).
**Status de cada robô** = o heartbeat que já existe: verde Conectado, amarelo Sem sinal,
cinza Desligado. **Não inventar "alarme"**: o app ainda não lê alarme ao vivo. Onde o protótipo
mostrava alarme, mostrar só os conectados.

### 2.1 Clientes (nova tela inicial, no lugar da `robot_list`)
- Topo: título "MyRobots", subtítulo "N clientes · X de Y robôs conectados", ícones de
  **Filtro** (com um número quando há filtro ativo), **+** e **engrenagem**.
- **Um cartão por cliente**:
  - nome, "N linhas · X de Y conectados" e "último usado" no primeiro;
  - **⋮** com Ocultar/Mostrar e Renomear;
  - **a mini planta de cada linha**: o nome da linha, a etiqueta do tipo de trabalho e as
    **estações na ordem do processo**. Cada estação é um bloquinho com **um quadrado por robô na
    cor do status, no formato da grade da cabine**, com o nome da estação embaixo. Entre as
    estações vai uma seta `→`. Quando há ligação de reaproveitamento entre elas, a seta vira um `⇄`
    azul. Se a linha tem ligação com **outra linha**, aparece um `⇄` amarelo no fim.
- **Ordem:** último usado primeiro (`lastUsedAt`).
- **"Mostrar ocultos (N)"** no fim da lista. Os ocultos aparecem apagados, com a etiqueta "oculto".
  Ocultar **nunca apaga** nada.
- **Atalhos:** com um cliente visível só, abrir direto nele. Com uma linha só, abrir direto nas estações.
- **+**: por enquanto, um menu com "Novo cliente" e "Nova linha" (escolhe o cliente). Cada um é uma
  janela simples de nome.
- Manter, no ⋮ da engrenagem, um item **"Lista de robôs (antiga)"** que abre a `robot_list` atual.
  Ele serve de rede de segurança durante os testes e sai quando a nova entrada estiver aprovada.

### 2.2 Cliente (as linhas dele)
- Um cartão por linha: nome, tipo de trabalho (o mais comum entre as estações), "N estações ·
  X de Y conectados", a mesma mini planta e o ⋮ (Ocultar, Renomear).

### 2.3 Linha (estações na ordem do processo)
- Lista vertical das estações, com LED do pior estado, nome, "N robôs · X conectados" e a mini grade.
- **Entre as estações:** "próxima estação", ou a ligação ("Top Coat reaproveita os programas do
  Primer") com o botão **Transferir**, que abre o fluxo de transferência que já existe.
- **Seção "Ligação com outra linha"**, quando houver, com borda tracejada amarela.
- **⋮ de cada estação:** Tipo de trabalho, Mover para outra linha, Subir/Descer (a ordem do
  processo), Ocultar e Renomear.
- **Tocar na estação** abre a tela de Projeto atual.

### 2.4 Filtros (bottom sheet)
- Tipo de trabalho, Linha, Status (Com robô conectado / Com robô desligado) e Marca (por enquanto
  só Kawasaki).
- Botões "Limpar" e "Ver resultados". Os filtros ativos aparecem como etiquetas acima da lista.
- Um cliente aparece se pelo menos uma linha dele passa no filtro. Dentro dele, só as linhas que passam.

### 2.5 Aviso de linha diferente (F6)
- Ao **criar** uma ligação mestre/escravo ou **transferir/duplicar** entre estações de **linhas
  diferentes**, mostrar antes:
  "**Linha diferente.** Atenção: [destino] é de outra linha e não está no mesmo processo. Tem
  certeza que deseja reaproveitar/transferir?" com **Cancelar** e **Continuar mesmo assim**.
- Na conferência da transferência, quando as linhas forem diferentes, mostrar uma faixa amarela
  fixa: "Origem e destino em linhas diferentes".
- Dentro da mesma linha, nada muda.

## 3. Testes

- `MigrationTest` 7 → 8.
- Testes JVM das regras puras: ordem por último uso, os atalhos (1 cliente / 1 linha), o filtro e
  a detecção de "linhas diferentes". Coloque essas regras em funções puras para dar para testar.

## 4. Ao terminar

1. Instalar no celular **por cima** da versão atual, **sem desinstalar**, e conferir que todos os
   robôs e estações aparecem dentro de "Meu cliente › Linha 1".
2. Roteiro rápido para eu testar:
   - criar o cliente "Honda" com duas linhas;
   - mover estações para elas;
   - mudar o tipo de trabalho;
   - reordenar estações;
   - ocultar e mostrar;
   - filtrar;
   - transferir entre linhas, para ver o aviso.
3. Marcar no `docs/PLANO_MESTRE.md` o que ficou pronto (C1–C4, F0, F0b, F1 sem 3D, F6) com a data e
   o commit, e atualizar o `GUIDE.md` (seção nova da tela inicial e a tabela de rotas).
4. `git push -u origin melhorias/v1.3-clientes`.
5. Me mandar um resumo: o que ficou pronto, o que mudou em relação a este texto e o que falta.
