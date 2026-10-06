"""
Escuta entre o KIDE (ou qualquer terminal) e o K-ROSET: repassa tudo, byte a byte, e grava os dois
lados para montar a biblioteca de comandos do app.

ATENÇÃO: com o KIDE não funciona. Ele entra como "khidl" e o K-ROSET fecha essa sessão quando ela
não vem do próprio KIDE. Para o KIDE use a captura passiva (kroset_captura.py, pelo tshark).

O K-ROSET já ocupa as portas 9105, 9205... Por isso a escuta abre outras portas no PC e o KIDE
conecta nelas (em vez de conectar direto no K-ROSET):

    controlador 1  -> KIDE em 127.0.0.1:2401  -> K-ROSET 9105
    controlador 2  -> KIDE em 127.0.0.1:2402  -> K-ROSET 9205
    ...
    controlador 9  -> KIDE em 127.0.0.1:2409  -> K-ROSET 9905

Ela roda junto com a ponte do celular (kroset_ponte.py, portas 2301 a 2309).

Uso (só biblioteca padrão, Python 3.8+):
    python tools/kroset_escuta.py                          # os 9 controladores (mapa acima)
    python tools/kroset_escuta.py --pares 2401:9105        # só os pares escolhidos (escuta:K-ROSET)
    python tools/kroset_escuta.py --rotulo "teste do LOAD" # marca as gravações desta sessão

No KIDE: na configuração da conexão do robô, use o endereço 127.0.0.1 e a porta da tabela
(ex.: 2401). Depois use o KIDE normalmente; cada função usada fica gravada.

O que fica gravado em tools/kroset_escuta/<aaaammdd>/:
  <hora>_<porta>_<n>.jsonl  tudo, na ordem: hora, lado (KIDE/ROBO), bytes em hexa e o texto
  <hora>_<porta>_<n>.txt    o mesmo, para ler: o que o KIDE mandou e o que o robô respondeu
  comandos.md               cada linha que o KIDE mandou e a resposta do robô até o próximo ">",
                            com os blocos do protocolo de arquivo (05 02 <tipo> ... 17) decodificados
  sessoes.json              lista das sessões (porta, início, fim, bytes de cada lado, rótulo)

Ctrl+C encerra. Mandar a pasta do dia (ou dizer qual é) basta para eu analisar.
"""

import argparse
import datetime as dt
import json
import socket
import sys
import threading
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parent / "kroset_escuta"
CONTROLADORES = {n: 9005 + 100 * n for n in range(1, 10)}

_trava = threading.Lock()
_contador = [0]


def agora():
    return dt.datetime.now()


def mostrar(texto):
    with _trava:
        print(texto, flush=True)


def ler_pares(texto):
    pares = []
    for item in texto.split(","):
        item = item.strip()
        if item:
            a, _, b = item.partition(":")
            pares.append((int(a), int(b)))
    return pares


def legivel(dados: bytes) -> str:
    """Texto com os bytes de controle à mostra: <ESC>, <05>, <IAC>..."""
    nomes = {0x05: "<ENQ>", 0x02: "<STX>", 0x17: "<ETB>", 0x1A: "<EOF>", 0x1B: "<ESC>", 0x08: "<BS>",
             0x0D: "\\r", 0x0A: "\\n\n", 0x09: "\\t", 0xFF: "<IAC>", 0x00: "<NUL>"}
    out = []
    for b in dados:
        if b in nomes:
            out.append(nomes[b])
        elif 32 <= b < 127 or b >= 160:
            out.append(bytes([b]).decode("latin-1"))
        else:
            out.append(f"<{b:02X}>")
    return "".join(out)


def blocos(dados: bytes):
    """Blocos do protocolo de arquivo no trecho: (tipo, conteúdo)."""
    achados = []
    i = 0
    while True:
        i = dados.find(b"\x05\x02", i)
        if i < 0 or i + 2 >= len(dados):
            break
        j = dados.find(b"\x17", i)
        if j < 0:
            break
        achados.append((chr(dados[i + 2]), dados[i + 3:j]))
        i = j + 1
    # respostas do terminal: 02 <tipo> "    0" [dados] 17
    i = 0
    while True:
        i = dados.find(b"\x02", i)
        if i < 0 or i + 1 >= len(dados):
            break
        if i > 0 and dados[i - 1] == 0x05:
            i += 1
            continue
        j = dados.find(b"\x17", i)
        if j < 0:
            break
        achados.append(("resp " + chr(dados[i + 1]), dados[i + 2:j]))
        i = j + 1
    return achados


class Sessao:
    """Uma conexão do KIDE: repassa e grava."""

    def __init__(self, cliente, endereco, porta_escuta, porta_kroset, host, pasta, rotulo):
        with _trava:
            _contador[0] += 1
            self.n = _contador[0]
        self.cliente = cliente
        self.endereco = endereco
        self.porta = porta_escuta
        self.porta_kroset = porta_kroset
        self.host = host
        self.inicio = agora()
        self.rotulo = rotulo
        base = pasta / f"{self.inicio:%H%M%S}_{porta_escuta}_{self.n}"
        self.jsonl = open(base.with_suffix(".jsonl"), "a", encoding="utf-8")
        self.txt = open(base.with_suffix(".txt"), "a", encoding="utf-8")
        self.comandos = pasta / "comandos.md"
        self.sessoes = pasta / "sessoes.json"
        self.bytes = {"KIDE": 0, "ROBO": 0}
        self.linha_kide = bytearray()
        self.resposta = bytearray()
        self.ultimo_comando = None
        self.lado_txt = None
        self.trava = threading.Lock()
        self.bloco_kide = None     # bloco 02 ... 17 do KIDE sendo lido
        self.iac_pula = 0          # bytes da negociação do telnet que faltam pular
        self.blocos_kide = []      # (tipo, bytes) dos blocos do comando atual

    # ---------- gravação ----------

    def grava(self, lado, dados):
        t = agora()
        with self.trava:
            self.bytes[lado] += len(dados)
            self.jsonl.write(json.dumps({
                "t": t.isoformat(timespec="milliseconds"), "lado": lado,
                "hex": dados.hex(" "), "texto": dados.decode("latin-1")
            }, ensure_ascii=False) + "\n")
            self.jsonl.flush()
            if lado != self.lado_txt:
                self.txt.write(f"\n--- {t:%H:%M:%S.%f}"[:-3] + f" {lado} ---\n")
                self.lado_txt = lado
            self.txt.write(legivel(dados))
            self.txt.flush()
            if lado == "KIDE":
                self._kide(dados)
            else:
                self._robo(dados)

    def _kide(self, dados):
        """
        Separa o que o KIDE digita (até o Enter: cada linha é um comando) das respostas que ele
        dá aos blocos do robô no protocolo de arquivo (02 <tipo> "    0" [dados] 17): essas
        vão para a resposta do comando como "[KIDE manda C: n bytes]".
        """
        for b in dados:
            if self.bloco_kide is not None:
                if b == 0x17:
                    bl = bytes(self.bloco_kide)
                    tipo = chr(bl[0]) if bl else "?"
                    conteudo = bl[6:] if len(bl) >= 6 else b""
                    self.resposta.extend(f"\n    [KIDE responde {tipo}: {len(conteudo)} bytes] ".encode("latin-1") + conteudo[:120])
                    self.blocos_kide.append((tipo, len(conteudo)))
                    self.bloco_kide = None
                else:
                    self.bloco_kide.append(b)
                continue
            if b == 0x02:
                self.bloco_kide = bytearray()
                continue
            # negociação do telnet: IAC + 2 bytes (ex.: FF FB 18), não é texto digitado
            if self.iac_pula > 0:
                self.iac_pula -= 1
                continue
            if b == 0xFF:
                self.iac_pula = 2
                continue
            if b in (0x0D, 0x0A):
                if self.linha_kide or b == 0x0D:
                    self._fecha_comando()
                    cmd = self.linha_kide.decode("latin-1")
                    self.linha_kide.clear()
                    self.ultimo_comando = (agora(), cmd)
                    mostrar(f"[{agora():%H:%M:%S}] {self.porta} KIDE> {cmd!r}")
            elif b == 0x08:
                if self.linha_kide:
                    self.linha_kide.pop()
            elif 32 <= b < 127 or b >= 160:
                self.linha_kide.append(b)

    def _robo(self, dados):
        self.resposta.extend(dados)
        if bytes(self.resposta).rstrip().endswith(b">"):
            self._fecha_comando()

    def _fecha_comando(self):
        """Grava o último comando e a resposta que veio até agora."""
        if self.ultimo_comando is None and not self.resposta.strip():
            return
        t, cmd = self.ultimo_comando or (agora(), "(sem comando: o robô falou sozinho)")
        resposta = bytes(self.resposta)
        self.resposta.clear()
        self.ultimo_comando = None
        linhas = [f"\n### `{cmd}`  ", f"{t:%Y-%m-%d %H:%M:%S} · porta {self.porta} → K-ROSET {self.porta_kroset}"
                  + (f" · {self.rotulo}" if self.rotulo else "") + "\n"]
        bl = [b for b in blocos(resposta) if not b[0].startswith("resp")]
        if self.blocos_kide:
            resumo = {}
            for tipo, n in self.blocos_kide:
                q, tot = resumo.get(tipo, (0, 0))
                resumo[tipo] = (q + 1, tot + n)
            linhas.append("Respostas do KIDE: " + ", ".join(f"`{t}` x{q} ({tot} bytes)" for t, (q, tot) in resumo.items()) + "\n")
            self.blocos_kide = []
        if bl:
            linhas.append("Blocos do protocolo de arquivo:")
            for tipo, conteudo in bl[:40]:
                linhas.append(f"- `{tipo}` {len(conteudo)} bytes: `{legivel(conteudo[:80])}`")
            if len(bl) > 40:
                linhas.append(f"- … mais {len(bl) - 40} blocos")
            linhas.append("")
        texto = legivel(resposta)
        if len(texto) > 4000:
            texto = texto[:4000] + f"\n… ({len(resposta)} bytes no total: veja o .txt da sessão)"
        linhas.append("```")
        linhas.append(texto.replace("\\n\n", "\n"))
        linhas.append("```")
        with _trava:
            with open(self.comandos, "a", encoding="utf-8") as f:
                f.write("\n".join(linhas) + "\n")

    def fecha(self):
        with self.trava:
            self._fecha_comando()
            self.jsonl.close()
            self.txt.close()
        with _trava:
            lista = json.loads(self.sessoes.read_text(encoding="utf-8")) if self.sessoes.exists() else []
            lista.append({
                "n": self.n, "porta": self.porta, "kroset": self.porta_kroset,
                "cliente": f"{self.endereco[0]}:{self.endereco[1]}",
                "inicio": self.inicio.isoformat(timespec="seconds"), "fim": agora().isoformat(timespec="seconds"),
                "bytes_kide": self.bytes["KIDE"], "bytes_robo": self.bytes["ROBO"], "rotulo": self.rotulo
            })
            self.sessoes.write_text(json.dumps(lista, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---------- repasse ----------

    def repassa(self, de, para, lado, fim):
        try:
            while not fim.is_set():
                dados = de.recv(65536)
                if not dados:
                    break
                self.grava(lado, dados)
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

    def roda(self):
        try:
            robo = socket.create_connection((self.host, self.porta_kroset), timeout=5)
            robo.settimeout(None)
        except OSError as e:
            mostrar(f"[{agora():%H:%M:%S}] {self.porta}: o K-ROSET não respondeu em {self.host}:{self.porta_kroset} ({e}). "
                    "O controlador está ligado?")
            self.cliente.close()
            self.fecha()
            return
        for s in (self.cliente, robo):
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        mostrar(f"[{agora():%H:%M:%S}] {self.porta}: sessão {self.n} aberta ({self.endereco[0]} → K-ROSET {self.porta_kroset})")
        fim = threading.Event()
        t = threading.Thread(target=self.repassa, args=(robo, self.cliente, "ROBO", fim), daemon=True)
        t.start()
        self.repassa(self.cliente, robo, "KIDE", fim)
        t.join(timeout=2)
        self.cliente.close()
        robo.close()
        self.fecha()
        mostrar(f"[{agora():%H:%M:%S}] {self.porta}: sessão {self.n} fechada "
                f"(KIDE {self.bytes['KIDE']} bytes, robô {self.bytes['ROBO']} bytes)")


def escutar(servidor, porta, porta_kroset, host, pasta, rotulo, parar):
    while not parar.is_set():
        try:
            cliente, endereco = servidor.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        sessao = Sessao(cliente, endereco, porta, porta_kroset, host, pasta, rotulo)
        threading.Thread(target=sessao.roda, daemon=True).start()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="Escuta entre o KIDE e o K-ROSET, gravando os dois lados.")
    ap.add_argument("--host-kroset", default="127.0.0.1")
    ap.add_argument("--pares", help='pares "escuta:K-ROSET" separados por vírgula (padrão: 2401..2409 -> 9105..9905)')
    ap.add_argument("--rotulo", default="", help="texto gravado junto (ex.: o que você está testando)")
    ap.add_argument("--aberta", action="store_true", help="aceita conexões de outros PCs (padrão: só deste PC)")
    a = ap.parse_args()

    pares = ler_pares(a.pares) if a.pares else [(2400 + n, porta) for n, porta in CONTROLADORES.items()]
    pasta = RAIZ / f"{agora():%Y%m%d}"
    pasta.mkdir(parents=True, exist_ok=True)

    abertos = []
    for porta, porta_kroset in pares:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("0.0.0.0" if a.aberta else "127.0.0.1", porta))
        except OSError as e:
            mostrar(f"[!] Não abri a porta {porta} ({e})")
            continue
        s.listen(5)
        s.settimeout(1.0)
        abertos.append((s, porta, porta_kroset))
    if not abertos:
        mostrar("[ERRO] Nenhuma porta abriu.")
        sys.exit(1)

    mostrar("=" * 64)
    mostrar(" Escuta KIDE <-> K-ROSET (grava os dois lados)")
    mostrar("=" * 64)
    mostrar("\nNo KIDE, conecte em 127.0.0.1 com a porta da tabela:\n")
    mostrar(f"   {'Controlador':<12} {'Porta no KIDE':<15} K-ROSET")
    numero = {p: n for n, p in CONTROLADORES.items()}
    for _, porta, porta_kroset in abertos:
        mostrar(f"   {str(numero.get(porta_kroset, '-')):<12} {porta:<15} {porta_kroset}")
    mostrar(f"\nGravando em {pasta}")
    if a.rotulo:
        mostrar(f"Rótulo: {a.rotulo}")
    mostrar("Ctrl+C para sair.\n")

    parar = threading.Event()
    for s, porta, porta_kroset in abertos:
        threading.Thread(target=escutar, args=(s, porta, porta_kroset, a.host_kroset, pasta, a.rotulo, parar),
                         daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        mostrar("\nEncerrando.")
    finally:
        parar.set()
        for s, _, _ in abertos:
            s.close()


if __name__ == "__main__":
    main()
