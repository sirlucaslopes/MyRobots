"""
Ponte entre o MyRobots (celular) e o K-ROSET (simulador Kawasaki no PC).

O K-ROSET só aceita conexões do próprio PC (127.0.0.1). Este script abre portas na rede
Wi-Fi e repassa tudo, byte a byte, para os controladores virtuais. Cada controlador do
K-ROSET ganha uma porta própria no PC; no app, cadastre cada robô com o IP do PC e a porta
dele (a tabela aparece ao iniciar).

Mapa padrão (o K-ROSET usa 9105, 9205, ... 9905 para os controladores 1 a 9):
    controlador 1  -> porta 2301  (K-ROSET 9105)
    controlador 2  -> porta 2302  (K-ROSET 9205)
    controlador 3  -> porta 2303  (K-ROSET 9305)
    ...
    controlador 9  -> porta 2309  (K-ROSET 9905)
Todas as portas abrem mesmo com o controlador desligado: ligue-o no K-ROSET e conecte.

Uso (só biblioteca padrão, Python 3.8+):
    python tools/kroset_ponte.py                        # todos os controladores (mapa acima)
    python tools/kroset_ponte.py --pares 2302:9205      # só os pares escolhidos (celular:K-ROSET)
    python tools/kroset_ponte.py --pares 2301:9105,2302:9205
    python tools/kroset_ponte.py --log                  # grava o tráfego em kroset_trafego.log
    python tools/kroset_ponte.py --firewall             # libera as portas no Firewall (pede admin)

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
import time

# controlador N do K-ROSET escuta em 9N05
CONTROLADORES = {n: 9005 + 100 * n for n in range(1, 10)}
NOME_REGRA = "MyRobots - ponte K-ROSET"

_trava_log = threading.Lock()
_trava_print = threading.Lock()
_arquivo_log = None


def mostrar(texto):
    with _trava_print:
        print(texto, flush=True)


def porta_do_celular(n):
    """Porta no PC para o controlador N: 2301..2309."""
    return 2300 + n


def mapa_padrao():
    return [(porta_do_celular(n), porta) for n, porta in CONTROLADORES.items()]


def ler_pares(texto):
    """"2301:9105,2302:9205" -> [(2301, 9105), (2302, 9205)]."""
    pares = []
    for item in texto.split(","):
        item = item.strip()
        if not item:
            continue
        cel, _, kro = item.partition(":")
        pares.append((int(cel), int(kro)))
    return pares


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


def portas_escutando():
    """
    Portas TCP em LISTEN no PC, lidas do netstat. Não conecta no K-ROSET: um teste de
    conexão gasta a sessão de telnet do controlador virtual, que pode parar de escutar.
    """
    try:
        saida = subprocess.run(["netstat", "-an", "-p", "TCP"], capture_output=True,
                               text=True, errors="replace").stdout
    except OSError:
        return set()
    portas = set()
    for linha in saida.splitlines():
        partes = linha.split()
        if len(partes) >= 4 and partes[0] == "TCP" and partes[3] in ("LISTENING", "ESCUTANDO", "OUVINDO"):
            try:
                portas.add(int(partes[1].rsplit(":", 1)[1]))
            except ValueError:
                pass
    return portas


def eh_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def liberar_firewall(portas):
    """Cria a regra de entrada TCP no Firewall do Windows para todas as portas da ponte."""
    if os.name != "nt":
        return
    lista = ",".join(str(p) for p in portas)
    if not eh_admin():
        mostrar("  [!] --firewall precisa de terminal como Administrador. Rode este comando nele:")
        mostrar(f'      netsh advfirewall firewall add rule name="{NOME_REGRA}" dir=in action=allow '
                f"protocol=TCP localport={lista} profile=private,public")
        return
    subprocess.run(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={NOME_REGRA}"],
                   capture_output=True)
    r = subprocess.run(["netsh", "advfirewall", "firewall", "add", "rule", f"name={NOME_REGRA}",
                        "dir=in", "action=allow", "protocol=TCP", f"localport={lista}",
                        "profile=private,public"], capture_output=True, text=True)
    mostrar(f"  Firewall: regra criada para {lista}." if r.returncode == 0 else f"  Firewall: falhou ({r.stdout.strip()})")


def registrar(origem, dados):
    if _arquivo_log is None:
        return
    hora = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    texto = dados.decode("latin-1").replace("\r", "\\r").replace("\n", "\\n\n" + " " * 26)
    with _trava_log:
        _arquivo_log.write(f"{hora} {origem:<12} {texto}\n")
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


def atender(cliente, endereco, host_kroset, porta_cel, porta_kroset):
    agora = datetime.datetime.now().strftime("%H:%M:%S")
    nome = f"{porta_cel}->{porta_kroset}"
    try:
        kroset = socket.create_connection((host_kroset, porta_kroset), timeout=5)
        kroset.settimeout(None)
    except OSError as e:
        mostrar(f"[{agora}] [{nome}] {endereco[0]} conectou, mas o K-ROSET não respondeu em "
                f"{host_kroset}:{porta_kroset} ({e}). Esse controlador está ligado?")
        cliente.close()
        return
    for s in (cliente, kroset):
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    mostrar(f"[{agora}] [{nome}] Celular {endereco[0]}:{endereco[1]} conectado.")
    fim = threading.Event()
    t = threading.Thread(target=repassar, args=(kroset, cliente, f"ROBO {porta_kroset}", fim), daemon=True)
    t.start()
    repassar(cliente, kroset, f"APP  {porta_kroset}", fim)
    t.join(timeout=2)
    cliente.close()
    kroset.close()
    mostrar(f"[{datetime.datetime.now():%H:%M:%S}] [{nome}] Celular {endereco[0]} desconectou.")


def escutar(servidor, host_kroset, porta_cel, porta_kroset, parar):
    """Aceita conexões numa porta da ponte e repassa para o controlador dela."""
    while not parar.is_set():
        try:
            cliente, endereco = servidor.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        threading.Thread(target=atender, args=(cliente, endereco, host_kroset, porta_cel, porta_kroset),
                         daemon=True).start()


def main():
    global _arquivo_log
    p = argparse.ArgumentParser(description="Ponte Wi-Fi entre o MyRobots e os controladores do K-ROSET.")
    p.add_argument("--host-kroset", default="127.0.0.1")
    p.add_argument("--pares", help='pares "celular:K-ROSET" separados por vírgula (padrão: os 9 controladores)')
    p.add_argument("--log", action="store_true", help="grava o tráfego em kroset_trafego.log")
    p.add_argument("--firewall", action="store_true", help="cria a regra no Firewall do Windows")
    a = p.parse_args()

    pares = ler_pares(a.pares) if a.pares else mapa_padrao()

    mostrar("=" * 66)
    mostrar(" Ponte MyRobots <-> K-ROSET (vários controladores)")
    mostrar("=" * 66)

    # abre as portas da ponte
    abertos = []
    for porta_cel, porta_kroset in pares:
        servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            servidor.bind(("0.0.0.0", porta_cel))
        except OSError as e:
            mostrar(f"[!] Não consegui abrir a porta {porta_cel} ({e}). Outra ponte já está rodando?")
            continue
        servidor.listen(5)
        servidor.settimeout(1.0)  # deixa o Ctrl+C funcionar no Windows
        abertos.append((servidor, porta_cel, porta_kroset))
    if not abertos:
        mostrar("[ERRO] Nenhuma porta abriu. Feche a outra ponte (Ctrl+C na janela dela) e tente de novo.")
        sys.exit(1)

    if a.log:
        _arquivo_log = open("kroset_trafego.log", "a", encoding="utf-8")
        _arquivo_log.write(f"\n===== {datetime.datetime.now():%Y-%m-%d %H:%M:%S} =====\n")

    ips = ips_da_rede()
    escutando = portas_escutando()
    mostrar(f"\nIP do PC para o app: {ips[0] if ips else '(não achei; veja com ipconfig)'}")
    for outro in ips[1:]:
        mostrar(f"                     {outro}  (outra placa de rede, use se o primeiro não funcionar)")
    mostrar("\nNo app, cadastre cada robô com esse IP e a porta da tabela (fabricante KAWASAKI):\n")
    mostrar(f"   {'Controlador':<12} {'Porta no app':<14} {'K-ROSET':<9} Situação")
    mostrar(f"   {'-' * 12} {'-' * 14} {'-' * 9} {'-' * 24}")
    numero = {porta: n for n, porta in CONTROLADORES.items()}
    for _, porta_cel, porta_kroset in abertos:
        n = numero.get(porta_kroset)
        rotulo = f"{n}" if n else "-"
        situacao = "ligado" if porta_kroset in escutando else "desligado (ligue no K-ROSET)"
        mostrar(f"   {rotulo:<12} {porta_cel:<14} {porta_kroset:<9} {situacao}")

    portas = [porta for _, porta, _ in abertos]
    mostrar("\nFirewall do Windows:")
    if a.firewall:
        liberar_firewall(portas)
    else:
        mostrar("   Se o celular não conectar, rode de novo com --firewall (terminal como Admin),")
        mostrar("   ou aceite o aviso do Windows marcando 'Redes privadas'.")
        mostrar("   A rede Wi-Fi do PC também precisa estar como 'Privada' nas configurações.")
    if a.log:
        mostrar(f"\nTráfego sendo gravado em {os.path.abspath('kroset_trafego.log')}")
    mostrar("\nAguardando o celular... (Ctrl+C para sair)\n")

    parar = threading.Event()
    for servidor, porta_cel, porta_kroset in abertos:
        threading.Thread(target=escutar, args=(servidor, a.host_kroset, porta_cel, porta_kroset, parar),
                         daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        mostrar("\nEncerrando.")
    finally:
        parar.set()
        for servidor, _, _ in abertos:
            servidor.close()
        if _arquivo_log:
            _arquivo_log.close()


if __name__ == "__main__":
    main()
