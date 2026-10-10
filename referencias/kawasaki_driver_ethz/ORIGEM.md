# Origem

Cópia de https://github.com/ethz-asl/kawasaki (commit 51f6425, 2019-08-19), licença Apache-2.0
(arquivo `LICENSE`). A pasta `doc` (imagens, 3,6 MB) ficou de fora.

Driver ROS em Python que fala com o terminal AS por TCP (porta 23): lê o estado e a pose e manda
o robô para uma pose alvo. Útil como exemplo de leitura e interpretação das respostas do terminal
(`parsers.py`, `kawasaki_commands.py`).
