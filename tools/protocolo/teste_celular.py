"""
Percurso automático pelas telas do MyRobots no celular (adb + uiautomator), sem alterar nada.

Cada passo faz uma ação (tocar num texto, voltar, digitar) e confere o que a tela tem que mostrar.
Falhou: guarda a foto da tela e segue para o próximo grupo de passos. No fim, confere se o app
não fechou (crash) durante o percurso.

Uso direto (o rodar_testes.py --celular chama sozinho):
    python tools/protocolo/teste_celular.py --robo C03 --saida pasta/
Devolve a lista de resultados em JSON na saída padrão (última linha).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
PACOTE = "my.robots"


class Celular:
    def __init__(self, saida: Path):
        self.saida = saida
        self.nos = []

    def adb(self, *args, timeout=60, binario=False):
        env = dict(os.environ, MSYS_NO_PATHCONV="1")
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout, env=env)
        return r.stdout if binario else r.stdout.decode("utf-8", errors="replace")

    def tela(self):
        """Lê a árvore da tela (texto, descrição e posição de cada elemento)."""
        for _ in range(3):
            self.adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            xml = self.adb("shell", "cat", "/sdcard/ui.xml")
            if "<hierarchy" in xml:
                try:
                    raiz = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
                except ET.ParseError:
                    time.sleep(0.5)
                    continue
                self.nos = []
                for n in raiz.iter("node"):
                    b = re.findall(r"\d+", n.get("bounds", ""))
                    if len(b) == 4:
                        x1, y1, x2, y2 = map(int, b)
                        self.nos.append((n.get("text", ""), n.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2))
                return self.nos
            time.sleep(0.5)
        self.nos = []
        return self.nos

    def acha(self, texto, exato=False):
        for t, d, x, y in self.nos:
            for v in (t, d):
                if (v == texto) if exato else (texto in v):
                    return x, y
        return None

    def espera(self, texto, segundos=10, exato=False):
        fim = time.time() + segundos
        while time.time() < fim:
            self.tela()
            if self.acha(texto, exato):
                return True
            time.sleep(0.7)
        return False

    def toca(self, texto, segundos=8, exato=False):
        if not self.espera(texto, segundos, exato):
            return False
        x, y = self.acha(texto, exato)
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)
        return True

    def toca_xy(self, x, y):
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.0)

    def volta(self, vezes=1):
        for _ in range(vezes):
            self.adb("shell", "input", "keyevent", "BACK")
            time.sleep(1.0)

    def rola(self, vezes=1, para_baixo=True):
        for _ in range(vezes):
            if para_baixo:
                self.adb("shell", "input", "swipe", "700", "2400", "700", "900", "300")
            else:
                self.adb("shell", "input", "swipe", "700", "900", "700", "2400", "300")
            time.sleep(0.6)

    def digita(self, texto):
        self.adb("shell", "input", "text", texto.replace(" ", "%s"))
        time.sleep(0.8)

    def foto(self, nome):
        dados = self.adb("exec-out", "screencap", "-p", binario=True)
        caminho = self.saida / f"{nome}.png"
        caminho.write_bytes(dados)
        return caminho.name

    def fecha_avisos(self):
        """Fecha a janela do relógio ou da série, se aparecer (não muda nada no robô)."""
        self.tela()
        for t in ("Agora não", "Manter "):
            if self.acha(t):
                x, y = self.acha(t)
                self.toca_xy(x, y)

    def pid(self):
        return self.adb("shell", "pidof", PACOTE).strip()


def percurso(c: Celular, robo: str):
    resultados = []

    def passo(nome, ok, detalhe=""):
        r = {"grupo": grupo_atual[0], "passo": nome, "ok": bool(ok), "detalhe": detalhe}
        if not ok:
            r["foto"] = c.foto(re.sub(r"\W+", "_", f"{grupo_atual[0]}_{nome}")[:60])
        resultados.append(r)
        return ok

    grupo_atual = ["Abertura"]

    def grupo(nome):
        grupo_atual[0] = nome
        c.fecha_avisos()

    # ---------- abertura ----------
    c.adb("shell", "am", "force-stop", PACOTE)
    c.adb("shell", "am", "start", "-n", f"{PACOTE}/.MainActivity")
    time.sleep(6)
    pid = c.pid()
    passo("app abriu", bool(pid))
    passo("lista de robôs", c.espera("My Robots", 15))
    passo("legenda Conectado / Sem sinal / Desligado", c.acha("Conectado") and c.acha("Sem sinal") and c.acha("Desligado"))
    passo(f"robô {robo} na lista", c.espera(robo, 5, exato=True))

    # ---------- menu da lista ----------
    grupo("Lista: menu ⋮")
    if passo("abre o ⋮", c.toca("Mais opções", exato=True)):
        for item in ("Ordenar A-Z", "Mestre / Escravo", "Fabricantes", "Configurar Wi-Fi", "Pasta dos arquivos"):
            passo(f"item {item}", c.acha(item))
        c.volta()

    grupo("Fabricantes")
    if c.toca("Mais opções", exato=True) and c.toca("Fabricantes"):
        passo("tela Fabricantes", c.espera("Pesquisa rápida do editor"))
        passo("abas dos fabricantes", c.acha("Kawasaki") and c.acha("Fanuc"))
        passo("termo .PROGRAM na lista", c.acha(".PROGRAM"))
        c.rola(12)
        passo("comandos rápidos padrão", c.espera("Comandos rápidos padrão", 3) or c.espera("SAVE", 3))
        c.volta()
    else:
        passo("abre Fabricantes", False)

    grupo("Mestre / Escravo")
    if c.toca("Mais opções", exato=True) and c.toca("Mestre / Escravo"):
        passo("tela Mestre / Escravo", c.espera("Mestre / Escravo"))
        passo("botão Nova", c.acha("Nova"))
        if c.acha("Origem → destino"):
            passo("cartão com origem e destino", True)
            passo("campo do offset", c.espera("Variável do offset", 3) or (c.rola(2) or c.espera("Variável do offset", 3)))
        c.volta()
    else:
        passo("abre Mestre / Escravo", False)

    # ---------- painel do robô ----------
    grupo("Painel do robô")
    c.espera("My Robots", 6)
    c.toca(robo, exato=True)
    c.fecha_avisos()
    passo("painel abriu", c.espera("Memória de programas", 12))
    passo("botão Atualizar", c.acha("Atualizar: baixar"))
    passo("botão do terminal", c.acha("Abrir terminal"))
    c.rola(1)
    passo("uso do robô", c.espera("Uso do robô", 5))
    c.rola(4)

    for secao, esperado in (("Programas", "Marcar todos"), ("Variáveis", "Marcar todos"), ("Data Bank", "Marcar todos")):
        grupo(f"Painel: {secao}")
        if c.toca(secao, exato=True):
            passo(f"seção {secao}", c.espera(esperado, 8))
            passo("lupa", c.acha("Pesquisar"))
            if c.toca("Pesquisar", exato=True):
                passo("campo de busca", c.espera("Pesquisar na lista", 4) or c.espera("Pesquisar...", 2))
                c.volta()  # fecha o teclado/busca
            c.volta()
        else:
            passo(f"abre {secao}", False)
        c.fecha_avisos()

    grupo("Painel: Terminal")
    if c.toca("Terminal", exato=True):
        passo("terminal", c.espera("Arquivos", 6) and c.acha("Limpar"))
        passo("campo de comando", c.acha("Enviar comando"))
        c.volta()
    else:
        passo("abre o terminal", False)
    c.fecha_avisos()

    # ---------- editor ----------
    grupo("Editor de programa")
    c.rola(6, para_baixo=False)
    if c.toca("Mais opções", exato=True) and c.toca("Ver arquivo completo"):
        passo("editor abriu", c.espera("linhas", 25))
        passo("ações Pesquisar / Editar / Salvar", c.acha("Pesquisar") and c.acha("Editar") and (c.acha("Salvo") or c.acha("Salvar")))
        if c.toca("Pesquisar", exato=True):
            passo("barra de pesquisa", c.espera("Pesquisar...", 4))
            passo("pesquisa rápida", c.acha("Comandos do arquivo"))
        if c.toca("Editar", exato=True):
            passo("barra de edição", c.espera("Copiar", 4) and c.acha("Colar") and c.acha("Desfazer") and c.acha("Refazer"))
            passo("Substituir no modo de edição", c.acha("Substituir"))
            if c.toca("Substituir", exato=True):
                passo("campo Substituir por", c.espera("Substituir por", 4))
                passo("botões Substituir e Todos", c.acha("Todos"))
        c.volta(4)
        c.fecha_avisos()
    else:
        passo("abre o editor", False)
    c.volta()  # sai do painel

    # ---------- projeto ----------
    grupo("Projeto")
    c.espera("My Robots", 6)
    if c.toca("Abrir projeto"):
        passo("tela do projeto", c.espera("Ações em grupo", 10))
        passo("ações Backup / Comando / Duplicar", c.acha("Backup de todos") and c.acha("Comando") and c.acha("Duplicar programa"))
        passo("linha de ações do projeto", c.acha("Conectar todos") and c.acha("Terminal Geral"))
        if c.toca("Duplicar programa"):
            passo("duplicar: origem / cópia / frame", c.espera("ORIGEM", 10) and c.acha("CÓPIA"))
            c.rola(2)
            passo("duplicar: bloco do frame", c.espera("FRAME DA BASE", 4))
            c.toca("Cancelar", exato=True)
        c.rola(3)
        if c.acha("Transferir"):
            if c.toca("Transferir", exato=True):
                passo("transferir: origem e destino", c.espera("ORIGEM (mestre)", 10) and c.acha("DESTINO (escravo)"))
                c.rola(3)
                passo("transferir: botão Analisar", c.espera("Analisar", 4))
                c.toca("Cancelar", exato=True)
        c.rola(3)
        passo("mini terminais", c.espera("Terminais", 4))
        c.volta()
    else:
        passo("abre o projeto", False)

    # ---------- fim ----------
    grupo("Estabilidade")
    passo("app continua aberto (mesmo processo)", c.pid() == pid, f"pid inicial {pid}, final {c.pid()}")
    crash = c.adb("logcat", "-d", "-b", "crash")
    passo("sem erro fatal do app", not (PACOTE in crash and "FATAL" in crash))
    return resultados


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--robo", default="C03", help="robô com backup para abrir o painel e o editor")
    ap.add_argument("--saida", default=".")
    a = ap.parse_args()
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    c = Celular(saida)
    c.adb("logcat", "-c", "-b", "crash")
    resultados = percurso(c, a.robo)
    # ASCII puro: o console do Windows não imprime todos os caracteres
    print(json.dumps(resultados, ensure_ascii=True))


if __name__ == "__main__":
    main()
