"""
Ponte entre o MyRobots (celular) e o K-ROSET (simulador Kawasaki no PC).

O K-ROSET só aceita conexões do próprio PC (127.0.0.1). Este script abre uma porta na
rede Wi-Fi e repassa tudo, byte a byte, para o K-ROSET. No app, cadastre o robô com o IP
do PC e a porta de escuta mostrados ao iniciar.

Uso (só biblioteca padrão, Python 3.8+):
    python tools/kroset_ponte.py                     # escuta na 23 e repassa para 127.0.0.1:9105
    python tools/kroset_ponte.py --porta-kroset 9205 # outro controlador do K-ROSET
    python tools/kroset_ponte.py --log               # grava o tráfego em kroset_trafego.log
    python tools/kroset_ponte.py --firewall          # libera a porta no Firewall (pede admin)

Ctrl+C encerra.
"""

import argparse
import ctypes
import datetime
import os
import socket
import subprocess
import sys
import threading

PORTAS_CONHECIDAS = [9105, 9205, 9305, 9405, 23]
NOME_REGRA = "MyRobots - ponte K-ROSET"

_trava_log = threading.Lock()
_arquivo_log = None


def ips_da_rede():
    """IPs do PC na rede local (o primeiro é o da rota padrão, normalmente o Wi-Fi)."""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # não envia nada, só escolhe a interface de saída
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips


def porta_aberta(host, porta, tempo=0.5):
    try:
        with socket.create_connection((host, porta), timeout=tempo):
            return True
    except OSError:
        return False


def eh_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def liberar_firewall(porta):
    """Cria a regra de entrada TCP no Firewall do Windows (perfil privado e público)."""
    if os.name != "nt":
        return
    if not eh_admin():
        print("  [!] --firewall precisa de terminal como Administrador. Rode este comando nele:")
        print(f'      netsh advfirewall firewall add rule name="{NOME_REGRA}" dir=in action=allow '
              f"protocol=TCP localport={porta} profile=private,public")
        return
    subprocess.run(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={NOME_REGRA}"],
                   capture_output=True)
    r = subprocess.run(["netsh", "advfirewall", "firewall", "add", "rule", f"name={NOME_REGRA}",
                        "dir=in", "action=allow", "protocol=TCP", f"localport={porta}",
                        "profile=private,public"], capture_output=True, text=True)
    print("  Firewall: regra criada." if r.returncode == 0 else f"  Firewall: falhou ({r.stdout.strip()})")


def registrar(origem, dados):
    if _arquivo_log is None:
        return
    hora = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    texto = dados.decode("latin-1").replace("\r", "\\r").replace("\n", "\\n\n" + " " * 20)
    with _trava_log:
        _arquivo_log.write(f"{hora} {origem:<6} {texto}\n")
        _arquivo_log.flush()


def repassar(de, para, origem, fim):
    try:
        while not fim.is_set():
            dados = de.recv(4096)
            if not dados:
                break
            registrar(origem, dados)
            para.sendall(dados)
    except OSError:
        pass
    finally:
        fim.set()
        for s in (de, para):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def atender(cliente, endereco, host_kroset, porta_kroset):
    agora = datetime.datetime.now().strftime("%H:%M:%S")
    try:
        kroset = socket.create_connection((host_kroset, porta_kroset), timeout=5)
        kroset.settimeout(None)
    except OSError as e:
        print(f"[{agora}] {endereco[0]} conectou, mas o K-ROSET não respondeu em "
              f"{host_kroset}:{porta_kroset} ({e}). O controlador virtual está ligado?")
        cliente.close()
        return
    for s in (cliente, kroset):
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"[{agora}] Celular {endereco[0]}:{endereco[1]} conectado ao K-ROSET.")
    fim = threading.Event()
    t = threading.Thread(target=repassar, args=(kroset, cliente, "ROBO", fim), daemon=True)
    t.start()
    repassar(cliente, kroset, "APP", fim)
    t.join(timeout=2)
    cliente.close()
    kroset.close()
    print(f"[{datetime.datetime.now():%H:%M:%S}] Celular {endereco[0]} desconectou.")


def main():
    global _arquivo_log
    p = argparse.ArgumentParser(description="Ponte Wi-Fi entre o MyRobots e o K-ROSET.")
    p.add_argument("--host-kroset", default="127.0.0.1")
    p.add_argument("--porta-kroset", type=int, default=9105,
                   help="porta do controlador virtual (padrão 9105)")
    p.add_argument("--porta", type=int, default=23,
                   help="porta que o celular usa (padrão 23, a mesma do robô real)")
    p.add_argument("--log", action="store_true", help="grava o tráfego em kroset_trafego.log")
    p.add_argument("--firewall", action="store_true", help="cria a regra no Firewall do Windows")
    a = p.parse_args()

    print("=" * 62)
    print(" Ponte MyRobots <-> K-ROSET")
    print("=" * 62)

    print(f"\n1) K-ROSET em {a.host_kroset}:")
    abertas = [porta for porta in PORTAS_CONHECIDAS if porta_aberta(a.host_kroset, porta)]
    if a.porta_kroset in abertas:
        print(f"   OK, porta {a.porta_kroset} respondendo.")
    elif abertas:
        print(f"   [!] Porta {a.porta_kroset} fechada, mas há algo em: {abertas}.")
        print(f"       Se for o K-ROSET, rode de novo com --porta-kroset {abertas[0]}.")
    else:
        print(f"   [!] Nada respondendo em {PORTAS_CONHECIDAS}.")
        print("       Abra o K-ROSET e ligue o controlador virtual. A ponte sobe mesmo")
        print("       assim e tenta de novo a cada conexão do celular.")

    print("\n2) Firewall do Windows:")
    if a.firewall:
        liberar_firewall(a.porta)
    else:
        print("   Se o celular não conectar, rode de novo com --firewall (terminal como Admin),")
        print("   ou aceite o aviso do Windows marcando 'Redes privadas'.")
        print("   A rede Wi-Fi do PC também precisa estar como 'Privada' nas configurações.")

    servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        servidor.bind(("0.0.0.0", a.porta))
    except OSError as e:
        print(f"\n[ERRO] Não consegui abrir a porta {a.porta}: {e}")
        print("       Outra coisa usa essa porta? Tente --porta 2323 e use 2323 no app.")
        sys.exit(1)
    servidor.listen(5)
    servidor.settimeout(1.0)  # deixa o Ctrl+C funcionar no Windows

    if a.log:
        _arquivo_log = open("kroset_trafego.log", "a", encoding="utf-8")
        _arquivo_log.write(f"\n===== {datetime.datetime.now():%Y-%m-%d %H:%M:%S} =====\n")

    ips = ips_da_rede()
    print("\n3) No app MyRobots, cadastre o robô com:")
    print(f"   IP:    {ips[0] if ips else '(não achei o IP; veja com ipconfig)'}")
    for outro in ips[1:]:
        print(f"          {outro}  (outra placa de rede, use se o primeiro não funcionar)")
    print(f"   Porta: {a.porta}")
    print("   Fabricante: KAWASAKI. Celular e PC na mesma rede Wi-Fi.")
    if a.log:
        print(f"\n   Tráfego sendo gravado em {os.path.abspath('kroset_trafego.log')}")
    print(f"\nAguardando o celular na porta {a.porta}... (Ctrl+C para sair)\n")

    try:
        while True:
            try:
                cliente, endereco = servidor.accept()
            except socket.timeout:
                continue
            threading.Thread(target=atender, args=(cliente, endereco, a.host_kroset, a.porta_kroset),
                             daemon=True).start()
    except KeyboardInterrupt:
        print("\nEncerrando.")
    finally:
        servidor.close()
        if _arquivo_log:
            _arquivo_log.close()


if __name__ == "__main__":
    main()
