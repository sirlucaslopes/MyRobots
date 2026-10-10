# Gêmeo digital: controlador Kawasaki virtual no app

Ideia registrada em 2026-10-10. Ainda não é plano aprovado; serve de rumo para o ambiente 3D e o
montador de robô.

## A ideia

Um controlador Kawasaki virtual roda dentro do próprio app e fala o mesmo terminal AS que um
robô de verdade. O resto do app conecta nele como conecta num robô real ou no K-ROSET, e o robô
montado no ambiente 3D mostra os movimentos.

Com isso o celular vira um "K-ROSET de bolso": testar programas, ver trajetórias e treinar sem
robô e sem PC.

## Por que é viável

- O protocolo do terminal já está mapeado: `docs/KIDE_COMANDOS.md` e o
  `tools/protocolo/controlador_falso.py`, que já imita o K-ROSET byte a byte (login, `ID`, `TIME`,
  SAVE/LOAD). O controlador virtual é a evolução desse falso, em Kotlin e com movimento.
- `KawasakiTerminalManager` não precisa mudar: o virtual escuta numa porta local (por exemplo
  `127.0.0.1:9105`) e o robô virtual é cadastrado como qualquer outro.
- A cinemática e o 3D vêm do montador de robô (plano do ambiente 3D).

## Camadas

| Camada | Faz | Base |
|---|---|---|
| Servidor de terminal | Telnet local, login, prompt `>`, blocos de SAVE/LOAD | `controlador_falso.py` |
| Memória do controlador | Programas, `.TRANS`, `.JOINTS`, `.REALS`, `.STRINGS`, sinais | `AsProgramBlocks` e parsers em `:core:common` |
| Interpretador AS | Executa um subconjunto: `JMOVE`, `LMOVE`, `HOME`, `SPEED`, `ACCURACY`, `SIGNAL`, `TWAIT`, `CALL`, variáveis, `IF`/`WHILE` | novo |
| Planejador de movimento | Interpola em eixo ou em linha reta com a velocidade pedida | novo |
| Comandos de monitor | `WHERE`, `HERE`, `ZPOW ON/OFF`, `EXECUTE`, `HOLD`, `CONTINUE`, `ABORT`, `ERESET` | `KIDE_COMANDOS.md` |
| Cinemática | Eixos ↔ X, Y, Z, O, A, T | montador de robô |
| Vista 3D | Desenha o robô e a trajetória | Filament |

## Modos

1. **Simulação** — tudo dentro do app, sem robô. É o primeiro objetivo.
2. **Espelho** — o 3D acompanha um robô real lendo `WHERE` de tempos em tempos. Só leitura: o app
   nunca manda movimento para um robô real por este caminho.
3. **Comparação** — rodar o mesmo programa no virtual e no K-ROSET e comparar respostas, como os
   testes de protocolo já fazem.

## Limites que precisam ficar claros

- O virtual não substitui o K-ROSET nem o robô na validação final. Tempo de ciclo, suavização de
  `ACCURACY` e comportamento de erro serão aproximados.
- O interpretador cobre um subconjunto do AS. Comando desconhecido responde com erro, não é
  ignorado em silêncio.
- KRNX (tempo real) fica fora: é opção paga do controlador e não roda no celular.

## Ordem sugerida

1. Montador de robô e cinemática do KJ264 (plano do ambiente 3D).
2. Portar o controlador falso para Kotlin como servidor local (login, `ID`, SAVE/LOAD).
3. `WHERE`, `HERE`, `JMOVE` e `LMOVE` com o robô se mexendo no 3D.
4. Executar programas inteiros de um backup real.
5. Modo espelho com robô real (só leitura).

Referências usadas: `referencias/README.md`.
