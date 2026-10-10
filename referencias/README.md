# Referências Kawasaki

Levantamento do que já existe para robôs Kawasaki (código aberto, APIs e ferramentas), feito em
2026-10-10 para o ambiente 3D, o montador de robô e o gêmeo digital do MyRobots. Esta pasta não faz
parte do build do Gradle.

## O que está aqui

| Pasta / arquivo | O que é | Licença |
|---|---|---|
| `khi_robot/` | Pacote ROS oficial da Kawasaki (partes): URDF da linha RS, limites de eixo, driver KRNX | BSD-3-Clause |
| `kawasaki_driver_ethz/` | Driver ROS em Python pelo terminal AS (porta 23), ETH Zürich | Apache-2.0 |
| `khi2cpp_hw/` | Driver ROS 2 Humble pelo KRNX para a linha CX, Iowa State | MIT |
| `KJ264.md` | Dados do KJ264 tirados das fichas da Kawasaki, com links para PDFs e CAD | — |
| `../docs/GEMEO_DIGITAL.md` | Ideia do controlador virtual dentro do app | — |

Cada pasta copiada tem um `ORIGEM.md` com o commit e o que ficou de fora.

Projetos sem licença declarada, os PDFs da Kawasaki e os repositórios completos (com malhas 3D)
**não** vão para o git. Para baixá-los no PC, na raiz do projeto:

```
python tools/referencias/baixar_referencias.py
```

Eles vão para `Arquivos_Kawasaki/referencias/`, que o `.gitignore` já ignora.

## Levantamento

### Oficial da Kawasaki

- **khi_robot** — https://github.com/Kawasaki-Robotics/khi_robot. ROS 1 (Kinetic, Melodic,
  Noetic), linhas RS e duAro, última atualização em 2023-10. Não tem robôs de pintura. Serve de
  referência para descrever eixos (DH no xacro), limites e o sistema de coordenadas: o README avisa
  que as coordenadas da Kawasaki e do ROS são diferentes.
- **khi_robot_app** — https://github.com/Kawasaki-Robotics/khi_robot_app. Exemplos de aplicação
  em Python (2020). Sem licença declarada: só no script de download.
- **KRNX** — API da Kawasaki para controle em tempo real (atualiza ângulos de eixo continuamente
  a partir de um PC). É opcional e pago: o controlador precisa ter o recurso KRNX habilitado. A
  lista de funções está em `khi_robot/khi_robot_control/include/krnx.h`. Não serve ao app agora.
- **K-ROSET** — controlador virtual oficial (pago, Windows). Já é a referência dos testes do
  protocolo (`tools/protocolo`).

### Comunicação pelo terminal AS (comunidade)

- **khi_robot_py** — https://github.com/IgorMIV/khi_robot_py. Python, protocolo do KIDE feito por
  engenharia reversa: envia programas grandes, roda e para programas, lê estado. Usa a porta 23 no
  robô real e 9105 no K-ROSET (a mesma porta que usamos). Comparar com `docs/KIDE_COMANDOS.md`.
  Sem arquivo de licença no repositório: só no script de download.
- **kawasaki-robot-tcp-client** — https://github.com/marcinmajkowski/kawasaki-robot-tcp-client.
  Classe Java para o terminal por TCP (2015). Sem licença: só no script de download.
- **kawasaki_driver (ETH)** — copiado em `kawasaki_driver_ethz/`.

### Tempo real (KRNX)

- **khi2cpp_hw** — copiado em `khi2cpp_hw/`. Driver ROS 2 recente para a linha CX.

### Programação offline

- **RoboDK** — pago. Tem pós-processador para Kawasaki AS e o KJ264J na biblioteca
  (https://robodk.com/robot/Kawasaki/KJ264J). Bom para comparar a saída do nosso pós-processador.
- Documentação dos pós-processadores: https://robodk.com/doc/en/Post-Processors.html

### Lacunas (o que não existe aberto)

- Parser, editor ou extensão de editor para a linguagem AS.
- Cinemática ou modelo 3D aberto dos robôs de pintura (série K / KJ).
- Controlador virtual aberto que imite o terminal AS e mova um robô 3D.

## O que aproveitar

| Para quê | Referência |
|---|---|
| Formato do modelo do robô (eixos, limites) | `khi_robot/khi_rs_description` |
| Dados reais do KJ264 | `KJ264.md` + CAD oficial |
| Protocolo do terminal e do KIDE | `khi_robot_py` (download) + `docs/KIDE_COMANDOS.md` |
| Saída do pós-processador AS | RoboDK |
| Controle em tempo real no futuro | `krnx.h`, `khi2cpp_hw/` |
