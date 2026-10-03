"""
Protocolo de testes do MyRobots: roda tudo sozinho e escreve um relatório.

O que ele faz, em ordem:
  1. Sobe o controlador falso (controlador_falso.py), que imita o K-ROSET e provoca falhas.
  2. Roda os testes no PC pelo Gradle, com o mesmo código de rede do app:
     - regras (parsers do AS, layout, mestre/escravo...): testes JVM de todos os módulos;
     - protocolo contra o controlador falso (ControladorFalsoTest);
     - protocolo contra o K-ROSET (KRosetTest), se ele estiver aberto.
  3. Opcional (--celular): instala o app no celular pelo adb, abre e confere que não fechou.
  4. Junta tudo num relatório em tools/protocolo/relatorios/ (Markdown) e mostra o resumo.

Uso (no Windows, na raiz do projeto):
    python tools/protocolo/rodar_testes.py                       # K-ROSET em 127.0.0.1:9205
    python tools/protocolo/rodar_testes.py --kroset 127.0.0.1:9105
    python tools/protocolo/rodar_testes.py --kroset nao          # sem K-ROSET
    python tools/protocolo/rodar_testes.py --celular             # também instala e abre no celular

Sai com código 0 só se nada falhou.
"""
import argparse
import datetime as dt
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
AQUI = Path(__file__).resolve().parent
JBR = r"C:\Program Files\Android\Android Studio\jbr"
GRADLEW = RAIZ / ("gradlew.bat" if os.name == "nt" else "gradlew")
ADB = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
PACOTE = "my.robots"

# descrições dos testes para o relatório (o nome do método já diz o essencial)
GRUPOS = {
    "ControladorFalsoTest": "Protocolo contra o controlador falso (falhas provocadas)",
    "KRosetTest": "Protocolo contra o K-ROSET",
}


def porta_livre() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def roda(cmd, log: Path, env=None, timeout=1800) -> int:
    with open(log, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.run(cmd, cwd=RAIZ, stdout=f, stderr=subprocess.STDOUT, env=env, timeout=timeout, shell=False)
    return p.returncode


def le_junit(pastas):
    """Casos de teste dos XML do JUnit: (classe, nome, situação, tempo, mensagem)."""
    casos = []
    for pasta in pastas:
        for xml in sorted(Path(pasta).glob("TEST-*.xml")):
            raiz = ET.parse(xml).getroot()
            for tc in raiz.iter("testcase"):
                classe = tc.get("classname", "").split(".")[-1]
                nome = tc.get("name", "")
                tempo = float(tc.get("time", "0") or 0)
                if tc.find("failure") is not None or tc.find("error") is not None:
                    el = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
                    msg = (el.get("message") or el.text or "").strip().splitlines()[0][:300]
                    casos.append((classe, nome, "FALHOU", tempo, msg))
                elif tc.find("skipped") is not None:
                    el = tc.find("skipped")
                    msg = (el.get("message") or "").strip()[:200]
                    casos.append((classe, nome, "PULADO", tempo, msg))
                else:
                    casos.append((classe, nome, "OK", tempo, ""))
    return casos


def teste_celular(saida: Path, env) -> list:
    """Instala o APK, abre o app e confere que ele continua aberto e sem erro fatal."""
    res = []
    if not ADB.exists():
        return [("Celular", "adb encontrado", "PULADO", 0, f"adb não está em {ADB}")]
    dispositivos = subprocess.run([str(ADB), "devices"], capture_output=True, text=True).stdout
    if "\tdevice" not in dispositivos:
        return [("Celular", "celular conectado", "PULADO", 0, "nenhum celular no adb")]
    t = time.time()
    rc = roda([str(GRADLEW), "assembleDebug", "--console=plain"], saida / "gradle_apk.log", env)
    res.append(("Celular", "gera o APK", "OK" if rc == 0 else "FALHOU", time.time() - t, "" if rc == 0 else "veja gradle_apk.log"))
    if rc != 0:
        return res
    apk = RAIZ / "app/build/outputs/apk/debug/app-debug.apk"
    t = time.time()
    p = subprocess.run([str(ADB), "install", "-r", str(apk)], capture_output=True, text=True)
    ok = "Success" in p.stdout
    res.append(("Celular", "instala o APK", "OK" if ok else "FALHOU", time.time() - t, "" if ok else p.stdout[-200:]))
    if not ok:
        return res
    subprocess.run([str(ADB), "logcat", "-c"])
    subprocess.run([str(ADB), "shell", "am", "force-stop", PACOTE])
    subprocess.run([str(ADB), "shell", "am", "start", "-n", f"{PACOTE}/.MainActivity"], capture_output=True)
    time.sleep(8)
    pid = subprocess.run([str(ADB), "shell", "pidof", PACOTE], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()
    erros = subprocess.run([str(ADB), "logcat", "-d", "-b", "crash"], capture_output=True, text=True).stdout
    (saida / "logcat_crash.txt").write_text(erros, encoding="utf-8")
    fatal = PACOTE in erros and "FATAL" in erros
    res.append(("Celular", "abre e continua aberto", "OK" if pid and not fatal else "FALHOU", 8,
                "" if pid and not fatal else "o app fechou: veja logcat_crash.txt"))
    return res


def eventos_do_falso(caminho: Path):
    if not caminho.exists():
        return []
    return [json.loads(l) for l in caminho.read_text(encoding="utf-8").splitlines() if l.strip()]


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="Protocolo de testes do MyRobots")
    ap.add_argument("--kroset", default="127.0.0.1:9205", help="host:porta do K-ROSET, ou 'nao'")
    ap.add_argument("--celular", action="store_true", help="também instala e abre o app no celular")
    args = ap.parse_args()

    agora = dt.datetime.now()
    nome = agora.strftime("%Y%m%d_%H%M")
    saida = AQUI / "relatorios" / nome
    registros = saida / "terminais"
    registros.mkdir(parents=True, exist_ok=True)

    env = dict(os.environ)
    if os.name == "nt" and Path(JBR).exists():
        env["JAVA_HOME"] = JBR

    # 1. controlador falso
    porta = porta_livre()
    eventos = saida / "eventos_controlador_falso.jsonl"
    falso = subprocess.Popen([sys.executable, str(AQUI / "controlador_falso.py"), "--porta", str(porta),
                              "--eventos", str(eventos)], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    time.sleep(1.0)
    print(f"[1/3] controlador falso na porta {porta}")

    # 2. testes no PC
    print("[2/3] testes no PC (Gradle)... isso leva alguns minutos")
    inicio = time.time()
    cmd = [str(GRADLEW), "testDebugUnitTest", "--rerun", "--continue", "--console=plain",
           f"-Pprotocolo.falso=127.0.0.1:{porta}",
           f"-Pprotocolo.kroset={args.kroset}",
           f"-Pprotocolo.saida={registros}"]
    # --rerun só vale para a tarefa nomeada: roda todos os testDebugUnitTest de novo
    rc = roda(cmd, saida / "gradle_testes.log", env)
    tempo_gradle = time.time() - inicio
    falso.terminate()

    pastas = [p for p in RAIZ.glob("**/build/test-results/testDebugUnitTest") if "node_modules" not in str(p)]
    casos = le_junit(pastas)
    if rc != 0 and not any(c[2] == "FALHOU" for c in casos):
        casos.append(("Gradle", "compila e roda os testes", "FALHOU", tempo_gradle, "o Gradle falhou: veja gradle_testes.log"))

    # 3. celular
    if args.celular:
        print("[3/3] celular: instala e abre o app")
        casos += teste_celular(saida, env)
    else:
        print("[3/3] celular: pulado (use --celular)")

    # ---------------- relatório ----------------
    evs = eventos_do_falso(eventos)
    presos = [e for e in evs if e["tipo"] == "preso"]
    ok = sum(1 for c in casos if c[2] == "OK")
    falhou = [c for c in casos if c[2] == "FALHOU"]
    pulado = [c for c in casos if c[2] == "PULADO"]
    commit = subprocess.run(["git", "log", "-1", "--format=%h %s"], cwd=RAIZ, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()
    sujo = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=RAIZ, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()

    L = []
    L.append(f"# Relatório do protocolo de testes — {agora:%d/%m/%Y %H:%M}")
    L.append("")
    situacao = "✅ TUDO CERTO" if not falhou and not presos else "❌ HÁ FALHAS"
    L.append(f"**{situacao}** · {ok} ok · {len(falhou)} falharam · {len(pulado)} pulados · "
             f"{len(presos)} alerta(s) de controlador preso · Gradle {tempo_gradle:.0f} s")
    L.append("")
    L.append(f"- Código: `{commit}`" + (" (com alterações não commitadas)" if sujo else ""))
    L.append(f"- K-ROSET: `{args.kroset}` · controlador falso: porta {porta} · celular: {'sim' if args.celular else 'não'}")
    L.append("")

    if presos:
        L.append("## ⚠️ Controlador preso")
        L.append("O controlador pediu algo e o app não respondeu. Num robô de verdade isso trava o controlador.")
        L.append("")
        for e in presos:
            L.append(f"- cenário `{e.get('cenario')}`: {e.get('detalhe')}")
        L.append("")

    if falhou:
        L.append("## Falhas")
        for classe, nome_t, _, tempo, msg in falhou:
            L.append(f"### {classe} · {nome_t}")
            L.append(f"- motivo: {msg or '(sem mensagem)'}")
            reg = registros / f"{classe}.{nome_t}.txt"
            if reg.exists():
                linhas = reg.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
                L.append("- últimas linhas do terminal:")
                L.append("```")
                L.extend(linhas)
                L.append("```")
            L.append("")

    L.append("## Todos os testes")
    grupos = {}
    for c in casos:
        grupos.setdefault(c[0], []).append(c)
    ordem = ["ControladorFalsoTest", "KRosetTest", "Celular"]
    for classe in sorted(grupos, key=lambda k: (ordem.index(k) if k in ordem else 99, k)):
        lista = grupos[classe]
        titulo = GRUPOS.get(classe, classe)
        n_ok = sum(1 for c in lista if c[2] == "OK")
        if classe not in ordem and all(c[2] == "OK" for c in lista):
            # testes de regras: só a contagem, para o relatório não ficar enorme
            L.append(f"- **{titulo}**: {n_ok} ok")
            continue
        L.append("")
        L.append(f"### {titulo} ({n_ok} de {len(lista)} ok)")
        L.append("| Teste | Resultado | Tempo | Observação |")
        L.append("|---|---|---|---|")
        for _, nome_t, sit, tempo, msg in lista:
            icone = {"OK": "✅", "FALHOU": "❌", "PULADO": "⏭️"}[sit]
            L.append(f"| {nome_t} | {icone} {sit} | {tempo:.1f} s | {msg.replace('|', '/')} |")
    L.append("")

    if evs:
        L.append("## Controlador falso: o que ele viu")
        loads = [e for e in evs if e["tipo"] == "load"]
        saves = [e for e in evs if e["tipo"] == "save"]
        L.append(f"- {len([e for e in evs if e['tipo'] == 'login'])} logins · {len(loads)} LOAD · {len(saves)} SAVE · "
                 f"{len([e for e in evs if e['tipo'] == 'corte'])} quedas provocadas · "
                 f"{len([e for e in evs if e['tipo'] == 'app_encerrou_load_parado'])} LOAD parado encerrado pelo app")
        L.append("")

    L.append("---")
    L.append(f"Arquivos desta rodada: `tools/protocolo/relatorios/{nome}/` (gradle_testes.log, terminais/, eventos).")
    texto = "\n".join(L) + "\n"
    (saida / "relatorio.md").write_text(texto, encoding="utf-8")
    shutil.copy(saida / "relatorio.md", AQUI / "relatorios" / "ultimo.md")

    print()
    print(f"{situacao}: {ok} ok, {len(falhou)} falharam, {len(pulado)} pulados, {len(presos)} controlador preso")
    for c in falhou:
        print(f"  FALHOU {c[0]} · {c[1]}: {c[4]}")
    print(f"Relatório: {saida / 'relatorio.md'}")
    sys.exit(0 if not falhou and not presos else 1)


if __name__ == "__main__":
    main()
