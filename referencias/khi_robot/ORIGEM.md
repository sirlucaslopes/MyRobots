# Origem

Cópia parcial de https://github.com/Kawasaki-Robotics/khi_robot (commit 57200db, 2023-10-20),
licença BSD-3-Clause (arquivo `LICENSE`, Kawasaki Heavy Industries).

O que foi copiado:
- `khi_rs_description/urdf` e `config`: URDF/xacro e limites de eixo da linha RS, feitos pela
  própria Kawasaki. Modelo de como ela descreve os eixos (parâmetros DH no xacro).
- `khi_robot_control/src` e `include`: driver KRNX (`krnx.h` lista as funções da API).
- `docs`: como ligar o ROS a um robô real.

O que ficou de fora: malhas 3D (`meshes`), as bibliotecas binárias `libkrnx.so` e os pacotes
MoveIt/Gazebo. Para ter o repositório inteiro: `python tools/referencias/baixar_referencias.py`.
