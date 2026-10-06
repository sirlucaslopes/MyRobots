# O que o KIDE manda ao controlador

O que o KIDE (KawasakiIDE.exe) mandou ao K-ROSET em cada função, gravado em 2026-10-06 com
`tools/kroset_captura.py` (captura passiva pelo tshark). Controlador: K-ROSET 9105, software
ASE_K80000W48 (2019/08/05), robô KJ264-A001. As respostas são as que o controlador deu.

Serve de base para a biblioteca de comandos do app (`RobotCommandLibrary`, `:core:model`).

## Login (toda conexão)

| Ordem | KIDE manda | Controlador responde | Para quê |
|---|---|---|---|
| 1 | `FF FA 18 F0 0A` (fim de subnegociação do telnet) | `login:` | abre o terminal |
| 2 | `as` + CR LF | `This is AS monitor terminal "AUX2"` e `>` | entra |
| 3 | `type ""` | linha em branco | confere o prompt |
| 4 | `MESSAGES ON` | `>` | liga as mensagens do terminal |
| 5 | `TYPE SYSDATA (Language)` | `2` | idioma do controlador |
| 6 | `TYPE DEXT(HERE,7)` | `0` | eixo externo 7 da posição atual |
| 7 | `TYPE DEXT(HERE,8)` | `(P0116)Illegal argument of function.` | eixo 8: este robô não tem |
| 8 | `ID` | dados do robô e versões | modelo, série, versões |
| 9 | `SAVE/ROB using.rcc` | arquivo de ~70 KB (.ROBOTDATA, .NETCONF) | dados do robô |
| 10 | `TIME` e Enter vazio | data e hora; "Change?" | lê o relógio sem mudar |
| 11 | `SAVE using.rcc` | arquivo de ~270 KB (programas, .TRANS, .REALS, .SYSDATA, .SPRDB…) | cópia de tudo |

Depois do login o KIDE fica com o backup inteiro na mão. "Download" repete o `SAVE using.rcc`.
O arquivo usa o mesmo protocolo do app (blocos `05 02 B/D/E … 17`); o KIDE responde
`02 B "    0" 17` e `02 E "    0" 17`.

## Funções

| Função no KIDE | Comandos | Respostas / observações |
|---|---|---|
| Error Reset | `ERESET` | `>` |
| Hold program | `HOLD` | `>` |
| Continue program | `CONTINUE` | com motor desligado: `(P1000)Cannot execute program because motor power is OFF.` |
| Motor power ON | `ZPOW ON` | `>`. `ZPOW` não está no manual AS; é o que o KIDE usa |
| Motor power OFF | `HOLD`, depois `ZPOW OFF` | pausa o programa antes de desligar |
| Set Speed | `TYPE mspeed`, depois `SPEED 50` | `mspeed` = velocidade de monitor atual (100) |
| Abort and Kill foreground | `ABORT`, `KILL` (+ `1`), `TYPE TASK (1)` | KILL pergunta `Are you sure ? (Yes:1, No:0)`; TASK `0` = parado |
| Abort and Kill background | `PCABORT 1:` … `PCABORT 5:`, `PCKILL 1:` … `PCKILL 5:` (+ `1` cada), `TYPE TASK (1001)` … `(1005)` | cada PCKILL pergunta e espera o `1` |
| Inspection Activate | `TYPE flowrate`, `TYPE flush_flag` a cada ~0,25 s | lê as variáveis da janela de inspeção (as escolhidas no KIDE) |
| Apagar programas e variáveis | ver abaixo | |

### Apagar programas e variáveis

Sequência do KIDE (programas `outzone` e `pg101`, frames `fr_295` e `fr_296`):

1. `HOLD`, `ZPOW OFF`
2. `ABORT`, `KILL` (+ `1`), `NUMABORT`, `NUMKILL` (+ `1`)
3. `PCABORT 1:` … `5:`, `PCKILL 1:` … `5:` (+ `1` cada)
4. `TYPE TASK (1)`, `(1001)` … `(1005)`, `(6002)`: todos `0`
5. `LOAD using.rcc` com os programas **vazios** (`.PROGRAM outzone ()` / `.END`, …): esvazia os programas
6. `DELETE/P/D outzone`, `DELETE/P/D pg101`, `DELETE/L/D fr_295`, `DELETE/L/D fr_296`

**Neste controlador os `DELETE …/D` falharam**: `(P0117)Invalid variable (or program) name.`, com a
seta no `/D`. Ou seja, a versão ASE_K80000W48 não aceita o "/D" (apagar forçado), e os programas
ficaram no robô, só que vazios. O manual AS documenta `DELETE/P/D`; a forma sem /D (`DELETE/P nome`)
não foi testada aqui.

`NUMABORT`/`NUMKILL` e a tarefa `6002` não estão no manual AS (provavelmente tarefas do sistema de
pintura); só o KIDE os usa.

## TYPE TASK (n)

Estado de um programa (manual AS, função TASK): `1` robô, `1001`…`1005` programas PC 1 a 5.
Valores: 0 parado, 1 rodando, 2 em pausa (HOLD), 3 passo concluído esperando (stepper).

## O que entrou na biblioteca do app

Consultas: `TYPE TASK (1)`, `TYPE TASK (1001)`. Controle: `ERESET`, `HOLD`, `CONTINUE`, `ZPOW ON`,
`ZPOW OFF`, `SPEED 50`, `ABORT`, `KILL`, `PCABORT 1:`, `PCKILL 1:`.

Ficaram de fora: `DELETE …/D` (falhou aqui), `NUMABORT`/`NUMKILL` (sem documentação), `TIME`
(deixa o controlador esperando a resposta do "Change?") e as variáveis da inspeção (são do usuário).
