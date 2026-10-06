"""
Captura passiva do tráfego KIDE <-> K-ROSET (sem ficar no meio da conexão).

A escuta (kroset_escuta.py) não serve para o KIDE: ele entra como "khidl" e o K-ROSET fecha essa
sessão quando ela não vem do próprio KIDE. Aqui o KIDE conecta direto no K-ROSET, como sempre, e
o tshark (Wireshark + Npcap com captura de loopback) só copia os pacotes das portas 9105, 9205...

Precisa: Wireshark instalado (tshark.exe) e o Npcap com "Support loopback traffic".

Uso:
    python tools/kroset_captura.py                         # portas 9105..9905
    python tools/kroset_captura.py --rotulo "teste do LOAD"
    python tools/kroset_captura.py --portas 9605           # só um controlador
    python tools/kroset_captura.py --so KawasakiIDE        # só conexões abertas pelo KIDE
    python tools/kroset_captura.py --ler gravacao.pcapng   # analisa uma captura feita no Wireshark

Grava em tools/kroset_escuta/<aaaammdd>/ os mesmos arquivos da escuta (.jsonl, .txt, comandos.md,
sessoes.json); em sessoes.json vai também o programa que abriu a conexão (ex.: KawasakiIDE.exe).

Ctrl+C encerra.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kroset_escuta as escuta  # noqa: E402

TSHARK_PADRAO = r"C:\Program Files\Wireshark\tshark.exe"
CAMPOS = ["tcp.stream", "ip.src", "tcp.srcport", "tcp.dstport", "tcp.seq_raw",
          "tcp.flags.syn", "tcp.flags.fin", "tcp.flags.reset", "tcp.payload"]


def achar_tshark():
    for c in (shutil.which("tshark"), TSHARK_PADRAO):
        if c and Path(c).exists():
            return c
    escuta.mostrar("[ERRO] tshark.exe não encontrado. Instale o Wireshark (winget install WiresharkFoundation.Wireshark).")
    sys.exit(1)


def interface_loopback(tshark):
    saida = subprocess.run([tshark, "-D"], capture_output=True, text=True, errors="replace").stdout
    for linha in saida.splitlines():
        if "NPF_Loopback" in linha or "loopback" in linha.lower():
            return linha.split(". ", 1)[1].split(" (")[0]
    escuta.mostrar("[ERRO] Sem interface de loopback. Reinstale o Npcap marcando \"Support loopback traffic\".")
    sys.exit(1)


def processo_da_porta(porta_local):
    """Programa dono da conexão que saiu de 127.0.0.1:<porta_local> (netstat + tasklist)."""
    try:
        net = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True, errors="replace").stdout
        for linha in net.splitlines():
            partes = linha.split()
            if len(partes) >= 5 and partes[1].endswith(f":{porta_local}"):
                pid = partes[-1]
                lista = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                                       capture_output=True, text=True, errors="replace").stdout
                m = re.match(r'"([^"]+)"', lista.strip())
                return m.group(1) if m else f"pid {pid}"
    except OSError:
        pass
    return "?"


class Fluxo:
    """Uma conexão TCP vista na captura, gravada como uma sessão da escuta."""

    def __init__(self, cliente_ip, cliente_porta, porta_kroset, pasta, rotulo, processo):
        self.processo = processo
        rot = " · ".join(x for x in (processo, rotulo) if x)
        self.sessao = escuta.Sessao(None, (cliente_ip, cliente_porta), porta_kroset, porta_kroset,
                                    "127.0.0.1", pasta, rot)
        self.proximo = {}   # lado -> próximo número de sequência esperado (descarta retransmissões)
        self.fins = 0

    def dados(self, lado, seq, payload):
        fim = seq + len(payload)
        esperado = self.proximo.get(lado)
        if esperado is not None:
            if fim <= esperado:
                return  # retransmissão ou cópia do mesmo pacote
            if seq < esperado:
                payload = payload[esperado - seq:]
        self.proximo[lado] = fim
        self.sessao.grava(lado, payload)

    def fecha(self):
        self.sessao.fecha()
        s = self.sessao
        escuta.mostrar(f"[{escuta.agora():%H:%M:%S}] {s.porta_kroset}: sessão {s.n} fechada ({self.processo}; "
                       f"cliente {s.bytes['KIDE']} bytes, robô {s.bytes['ROBO']} bytes)")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="Captura passiva KIDE <-> K-ROSET pelo tshark.")
    ap.add_argument("--portas", help="portas do K-ROSET separadas por vírgula (padrão: 9105..9905)")
    ap.add_argument("--rotulo", default="", help="texto gravado junto (ex.: o que você está testando)")
    ap.add_argument("--so", default="", help="só grava conexões deste programa (ex.: KawasakiIDE)")
    ap.add_argument("--ler", help="analisa um arquivo .pcap/.pcapng em vez de capturar ao vivo")
    a = ap.parse_args()

    portas = [int(p) for p in a.portas.split(",")] if a.portas else list(escuta.CONTROLADORES.values())
    tshark = achar_tshark()
    filtro = "tcp and (" + " or ".join(f"port {p}" for p in portas) + ")"
    cmd = [tshark, "-l", "-n", "-T", "fields", "-E", "separator=/t", "-E", "occurrence=f"]
    for c in CAMPOS:
        cmd += ["-e", c]
    if a.ler:
        cmd += ["-r", a.ler, "-Y", filtro.replace("port ", "tcp.port == ").replace("tcp and ", "")]
    else:
        cmd += ["-i", interface_loopback(tshark), "-f", filtro]

    pasta = escuta.RAIZ / f"{escuta.agora():%Y%m%d}"
    pasta.mkdir(parents=True, exist_ok=True)
    escuta.mostrar("=" * 64)
    escuta.mostrar(" Captura KIDE <-> K-ROSET (passiva, pelo tshark)")
    escuta.mostrar("=" * 64)
    escuta.mostrar(f"Portas: {', '.join(map(str, portas))}")
    escuta.mostrar("Conecte o KIDE direto no K-ROSET, como sempre (127.0.0.1 e a porta do controlador).")
    escuta.mostrar(f"Gravando em {pasta}")
    if a.so:
        escuta.mostrar(f"Só conexões de: {a.so}")
    escuta.mostrar("Ctrl+C para sair.\n")

    fluxos = {}
    ignorados = set()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                            encoding="latin-1", bufsize=1)
    try:
        for linha in proc.stdout:
            f = linha.rstrip("\r\n").split("\t")
            if len(f) < len(CAMPOS) or not f[0]:
                continue
            stream, ip, sport, dport, seq, syn, fin, rst, payload = f[:9]
            sport, dport = int(sport), int(dport)
            if stream in ignorados:
                continue
            lado = "KIDE" if dport in portas else "ROBO"
            porta_kroset = dport if lado == "KIDE" else sport
            fluxo = fluxos.get(stream)
            if fluxo is None:
                if not payload:
                    continue  # SYN, SYN-ACK, conexão recusada: a sessão só abre com o primeiro dado
                cliente_porta = sport if lado == "KIDE" else dport
                processo = "(arquivo)" if a.ler else processo_da_porta(cliente_porta)
                if a.so and a.so.lower() not in processo.lower():
                    ignorados.add(stream)
                    escuta.mostrar(f"[{escuta.agora():%H:%M:%S}] {porta_kroset}: conexão de {processo} ignorada")
                    continue
                fluxo = Fluxo(ip if lado == "KIDE" else "127.0.0.1", cliente_porta, porta_kroset, pasta, a.rotulo, processo)
                fluxos[stream] = fluxo
                escuta.mostrar(f"[{escuta.agora():%H:%M:%S}] {porta_kroset}: sessão {fluxo.sessao.n} aberta "
                               f"({processo}, porta {cliente_porta})")
            if payload:
                dados = bytes.fromhex(payload.replace(":", ""))
                fluxo.dados(lado, int(seq or 0), dados)
            if rst in ("1", "True"):
                fluxo.fins = 2
            elif fin in ("1", "True"):
                fluxo.fins += 1
            if fluxo.fins >= 2:
                fluxo.fecha()
                del fluxos[stream]
                ignorados.add(stream)
    except KeyboardInterrupt:
        escuta.mostrar("\nEncerrando.")
    finally:
        proc.terminate()
        for fluxo in fluxos.values():
            fluxo.fecha()


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    main()
