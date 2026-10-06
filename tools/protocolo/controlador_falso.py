"""
Controlador Kawasaki falso para os testes do protocolo (tools/protocolo).

Imita o terminal AS do K-ROSET byte a byte (login, prompt ">", ID, TIME, FREE, LOAD e SAVE com
os blocos 05 02 <tipo> ... 17) e permite provocar falhas. O cenário é escolhido pelo usuário do
login (o teste cadastra o robô com loginUser = cenário):

    normal        tudo certo
    fragmentado   cada envio sai em pedaços de 1 a 3 bytes (blocos partidos entre pacotes)
    erro_load     o LOAD termina com "File load completed. (2 errors)"
    recusa_load   o LOAD é recusado com uma mensagem de erro (sem pedir o arquivo)
    corta_load    a conexão cai no meio do LOAD (depois do primeiro pedaço)
    para_load     depois do primeiro pedaço o robô para de responder (não pede mais nada)
    corta_save    a conexão cai no meio do SAVE
    pede_dados    logo depois do login, pede dados (C) sem nenhum LOAD em andamento
    mudo          depois do login para de responder a qualquer comando (sem eco, sem prompt)
    pergunta_load no meio do LOAD acha um "erro de sintaxe" e pergunta, como o K-ROSET:
                  "(0:Change to comment and continue, 1:Delete program and abort)"

Cada sessão registra eventos em JSON (uma linha por evento) no arquivo --eventos. O evento mais
importante é "preso": o controlador pediu algo (bloco A, B, C ou E) e o app não respondeu em
--limite segundos. Isso é exatamente o que trava um controlador de verdade.

Uso (o rodar_testes.py já faz isso):
    python controlador_falso.py --porta 9300 --eventos eventos.jsonl
"""
import argparse
import json
import random
import socket
import threading
import time

IAC_DO_TTYPE = b"\xff\xfd\x18"
ENQ_STX = b"\x05\x02"
ETB = b"\x17"

PROGRAMA_PADRAO = (
    ".PROGRAM pgfalso()\r\n"
    "  SPRAY_SPEED 500mm/s\r\n"
    "  BASE fr_[100]\r\n"
    "  TWAIT 1\r\n"
    ".END\r\n"
)


class Eventos:
    def __init__(self, caminho):
        self.caminho = caminho
        self.lock = threading.Lock()

    def registra(self, sessao, tipo, **dados):
        linha = {"t": round(time.time(), 3), "sessao": sessao, "tipo": tipo, **dados}
        with self.lock:
            with open(self.caminho, "a", encoding="utf-8") as f:
                f.write(json.dumps(linha, ensure_ascii=False) + "\n")


class Fechou(Exception):
    pass


class Sessao(threading.Thread):
    """Uma conexão do app com o controlador falso."""

    contador = 0
    memoria = {}          # programas carregados (nome -> texto), compartilhado entre sessões
    memoria_lock = threading.Lock()

    def __init__(self, conn, eventos, limite):
        super().__init__(daemon=True)
        Sessao.contador += 1
        self.id = Sessao.contador
        self.conn = conn
        self.ev = eventos
        self.limite = limite
        self.cenario = "normal"
        self.rx = bytearray()
        self.conn.settimeout(0.2)

    # ---------------- envio ----------------

    def envia(self, dados: bytes):
        if self.cenario == "fragmentado":
            i = 0
            while i < len(dados):
                n = random.randint(1, 3)
                self.conn.sendall(dados[i:i + n])
                i += n
                time.sleep(0.002)
        else:
            self.conn.sendall(dados)

    def texto(self, s: str):
        self.envia(s.encode("latin-1"))

    def bloco(self, tipo: str, conteudo: bytes = b""):
        self.envia(ENQ_STX + tipo.encode() + conteudo + ETB)

    def prompt(self):
        self.texto("\r\n>")

    # ---------------- recepção ----------------

    def recebe(self, timeout=None):
        """Lê o que chegar; levanta Fechou se o app fechar a conexão."""
        fim = time.time() + (timeout if timeout is not None else 0.2)
        while True:
            try:
                d = self.conn.recv(65536)
                if not d:
                    raise Fechou()
                self.rx.extend(d)
                return True
            except socket.timeout:
                if time.time() >= fim:
                    return False

    def linha(self):
        """Espera uma linha (até \\r ou \\n) digitada pelo app. Ignora a negociação telnet."""
        while True:
            # descarta IAC WILL/WONT/DO/DONT x
            while True:
                i = self.rx.find(b"\xff")
                if i < 0 or i + 2 >= len(self.rx):
                    break
                del self.rx[i:i + 3]
            for sep in (b"\r\n", b"\r", b"\n"):
                i = self.rx.find(sep)
                if i >= 0:
                    linha = bytes(self.rx[:i]).decode("latin-1")
                    del self.rx[:i + len(sep)]
                    # ecoa o que foi digitado, como o controlador real
                    self.texto(linha + "\r\n")
                    return linha
            self.recebe(1.0)

    def linha_com_limite(self, segundos: float):
        """Como linha(), mas devolve None se nada chegar em [segundos]."""
        fim = time.time() + segundos
        while time.time() < fim:
            for sep in (b"\r\n", b"\r", b"\n"):
                i = self.rx.find(sep)
                if i >= 0:
                    linha = bytes(self.rx[:i]).decode("latin-1")
                    del self.rx[:i + len(sep)]
                    self.texto(" " + linha + "\r\n")
                    return linha
            self.recebe(0.5)
        return None

    def resposta_do_app(self, tipo: str):
        """
        Espera o app responder ao bloco pedido: 02 <tipo> "    0" [dados] 17.
        Devolve os dados (no caso do C) ou registra "preso" se passar do limite.
        """
        inicio = time.time()
        while True:
            i = self.rx.find(b"\x02" + tipo.encode())
            if i >= 0:
                j = self.rx.find(ETB, i)
                if j >= 0:
                    conteudo = bytes(self.rx[i + 7:j])
                    del self.rx[:j + 1]
                    return conteudo
            if time.time() - inicio > self.limite:
                self.ev.registra(self.id, "preso", esperando=tipo, cenario=self.cenario,
                                 detalhe=f"o app não respondeu ao bloco {tipo} em {self.limite} s")
                raise Fechou()
            self.recebe(0.5)

    # ---------------- comandos ----------------

    def run(self):
        try:
            self.envia(IAC_DO_TTYPE + b"login: ")
            usuario = self.linha().strip()
            self.cenario = usuario or "normal"
            self.ev.registra(self.id, "login", cenario=self.cenario)
            self.texto('This is AS monitor terminal "FALSO"')
            self.prompt()
            if self.cenario == "pede_dados":
                self.pede_dados_sem_load()
            if self.cenario == "mudo":
                # lê e ignora tudo: o app não pode mandar dados sem a confirmação de estado
                while True:
                    self.recebe(1.0)
                    self.rx.clear()
            while True:
                cmd = self.linha().strip()
                if cmd == "":
                    self.prompt()
                    continue
                self.ev.registra(self.id, "comando", comando=cmd, cenario=self.cenario)
                self.executa(cmd)
        except Fechou:
            self.ev.registra(self.id, "fechou", cenario=self.cenario)
        except (ConnectionError, OSError) as e:
            self.ev.registra(self.id, "fechou", cenario=self.cenario, erro=str(e))
        finally:
            try:
                self.conn.close()
            except OSError:
                pass

    def executa(self, cmd: str):
        up = cmd.upper()
        if up == "ID":
            self.texto("        Robot name: KJ264-A001   Num of axes 7   Serial No. 1996\r\n"
                       "        Number of signals: output = 256  input = 256  internal = 960")
            self.prompt()
        elif up == "TIME":
            agora = time.localtime()
            self.texto(time.strftime("TIME %y-%m-%d(SAT) %H:%M:%S", agora) + "\r\n")
            self.texto("Change? (If not, Hit RETURN only)\r\n")
            self.linha()
            self.prompt()
        elif up.startswith("TIME "):
            self.texto(time.strftime("TIME %y-%m-%d(SAT) %H:%M:%S") + "\r\nChange? (If not, Hit RETURN only)\r\n")
            self.linha()
            self.prompt()
        elif up == "FREE":
            self.texto("Total memory     8192 KB\r\nAvailable memory size  8175 KB")
            self.prompt()
        elif up.startswith("LOAD "):
            self.load(cmd[5:].strip())
        elif up.startswith("SAVE"):
            self.save(cmd)
        elif up.startswith("DELETE"):
            self.texto("Are you sure ? (Yes:1, No:0)")
            resposta = self.linha().strip()
            if resposta == "1":
                nome = cmd.split()[-1]
                with Sessao.memoria_lock:
                    Sessao.memoria.pop(nome, None)
            self.prompt()
        else:
            self.prompt()

    def load(self, arquivo: str):
        if self.cenario == "recusa_load":
            self.texto("(E1018) File not found.")
            self.prompt()
            return
        self.bloco("A", arquivo.encode("latin-1"))
        self.resposta_do_app("A")
        self.texto(f"Loading...({arquivo})\r\n")
        recebido = bytearray()
        pedacos = 0
        while True:
            self.bloco("C")
            dados = self.resposta_do_app("C")
            pedacos += 1
            if dados.endswith(b"\x1a"):
                recebido.extend(dados[:-1])
                break
            recebido.extend(dados)
            if self.cenario == "pergunta_load" and pedacos == 1:
                self.texto("Program  pgtesteapp()\r\n\r\n   1 ESTA_INSTRUCAO_NAO_EXISTE 1,2,3\r\n"
                           "     ^(P0109)Invalid statement.\r\n\r\nSTEP syntax error.\r\n\r\n"
                           "(0:Change to comment and continue, 1:Delete program and abort)\r\n")
                resposta = self.linha_com_limite(60)
                self.ev.registra(self.id, "pergunta", cenario=self.cenario, resposta=resposta)
                if resposta is None:
                    self.ev.registra(self.id, "preso", esperando="resposta", cenario=self.cenario,
                                     detalhe="o app não respondeu à pergunta do erro de sintaxe em 60 s")
                    raise Fechou()
                if resposta.strip() == "1":
                    self.bloco("E")
                    self.resposta_do_app("E")
                    self.texto("Program deleted. LOAD aborted.")
                    self.prompt()
                    return
            if self.cenario == "corta_load" and pedacos == 1:
                self.ev.registra(self.id, "corte", cenario=self.cenario, onde="LOAD")
                raise Fechou()
            if self.cenario == "para_load" and pedacos == 1:
                # para de pedir: o app precisa encerrar sozinho (manda o fim de arquivo)
                self.ev.registra(self.id, "parou", cenario=self.cenario)
                if self.espera_fim_de_arquivo():
                    self.ev.registra(self.id, "app_encerrou_load_parado", cenario=self.cenario)
                self.bloco("E")
                self.resposta_do_app("E")
                self.texto("File load completed. (0 errors)")
                self.prompt()
                return
        self.bloco("E")
        self.resposta_do_app("E")
        texto = recebido.decode("latin-1")
        nomes = []
        for parte in texto.split(".PROGRAM ")[1:]:
            nome = parte.split("(")[0].strip()
            corpo = ".PROGRAM " + parte.split(".END")[0] + ".END\r\n"
            nomes.append(nome)
            with Sessao.memoria_lock:
                Sessao.memoria[nome] = corpo
            self.texto(f"Program  {nome}()\r\n")
        erros = 2 if self.cenario == "erro_load" else (1 if self.cenario == "pergunta_load" else 0)
        self.ev.registra(self.id, "load", cenario=self.cenario, arquivo=arquivo, bytes=len(recebido),
                         programas=nomes, erros=erros)
        self.texto(f"File load completed. ({erros} errors)")
        self.prompt()

    def espera_fim_de_arquivo(self) -> bool:
        """No cenário para_load: o app deve mandar o fim de arquivo sozinho (vigia)."""
        inicio = time.time()
        while time.time() - inicio < 60:
            i = self.rx.find(b"\x02C")
            if i >= 0 and self.rx.find(b"\x1a\x17", i) >= 0:
                del self.rx[:]
                return True
            self.recebe(0.5)
        self.ev.registra(self.id, "preso", esperando="fim", cenario=self.cenario,
                         detalhe="o app não encerrou o LOAD parado em 60 s")
        return False

    def save(self, cmd: str):
        # SAVE/FULL nome | SAVE/P/SEL nome=prog | SAVE nome
        partes = cmd.split(None, 1)
        alvo = partes[1].strip() if len(partes) > 1 else "backup"
        nome, _, prog = alvo.partition("=")
        nome = nome.strip()
        if "." not in nome:
            nome += ".as"
        if prog:
            with Sessao.memoria_lock:
                corpo = Sessao.memoria.get(prog.strip(), "")
            linhas = [".* arquivo do controlador falso\r\n"] + corpo.splitlines(keepends=True)
        else:
            # "backup completo": cabeçalho + 3000 linhas (mais que o histórico de 1000 do app)
            linhas = [".***************************************************************************\r\n",
                      ".*=== AS GROUP ===         : ASE_FALSO\r\n", ".ROBOTDATA1\r\n",
                      "ZROBOT.TYPE     35    1    7 1996\r\n", ".END\r\n"]
            linhas += PROGRAMA_PADRAO.splitlines(keepends=True)
            linhas += [".REALS\r\n"] + [f"real_{i} = {i}\r\n" for i in range(3000)] + [".END\r\n"]
        self.bloco("B", nome.encode("latin-1"))
        self.resposta_do_app("B")
        self.texto("Saving...(" + nome + ")\r\n")
        total = 0
        for k, l in enumerate(linhas):
            dados = l.encode("latin-1")
            total += len(dados)
            self.bloco("D", dados)
            if self.cenario == "corta_save" and k == len(linhas) // 2:
                self.ev.registra(self.id, "corte", cenario=self.cenario, onde="SAVE")
                raise Fechou()
        self.bloco("E")
        self.resposta_do_app("E")
        self.ev.registra(self.id, "save", cenario=self.cenario, arquivo=nome, bytes=total)
        self.texto("File save completed.")
        self.prompt()

    def pede_dados_sem_load(self):
        """Pede dados (C) sem LOAD: o app tem que responder com o fim de arquivo."""
        time.sleep(0.5)
        self.bloco("C")
        dados = self.resposta_do_app("C")
        self.ev.registra(self.id, "pedido_sem_load", cenario=self.cenario, fim=dados.endswith(b"\x1a"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--porta", type=int, default=9300)
    ap.add_argument("--eventos", default="eventos.jsonl")
    ap.add_argument("--limite", type=float, default=8.0, help="segundos para o app responder a um bloco")
    args = ap.parse_args()
    eventos = Eventos(args.eventos)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.porta))
    srv.listen(16)
    print(f"controlador falso na porta {args.porta}", flush=True)
    while True:
        conn, _ = srv.accept()
        Sessao(conn, eventos, args.limite).start()


if __name__ == "__main__":
    main()
