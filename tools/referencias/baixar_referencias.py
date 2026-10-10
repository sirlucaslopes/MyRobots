"""
Baixa as referências Kawasaki que não vão para o git (ver referencias/README.md).

O que ele baixa, para Arquivos_Kawasaki/referencias/ (pasta ignorada pelo git):
  repos/  repositórios completos do GitHub, inclusive os sem licença e as malhas 3D
  pdf/    fichas técnicas do KJ264 publicadas pela Kawasaki

Uso (na raiz do projeto):
    python tools/referencias/baixar_referencias.py            # baixa o que falta
    python tools/referencias/baixar_referencias.py --atualizar  # também atualiza os repositórios

Precisa de git e internet. O CAD do KJ264 exige aceitar o aviso no site da Kawasaki, então é
baixado à mão (o link aparece no final).
"""
import argparse
import pathlib
import subprocess
import sys
import urllib.request

RAIZ = pathlib.Path(__file__).resolve().parents[2]
DESTINO = RAIZ / "Arquivos_Kawasaki" / "referencias"

REPOS = {
    "khi_robot": "https://github.com/Kawasaki-Robotics/khi_robot",
    "khi_robot_app": "https://github.com/Kawasaki-Robotics/khi_robot_app",
    "khi_robot_py": "https://github.com/IgorMIV/khi_robot_py",
    "kawasaki-robot-tcp-client": "https://github.com/marcinmajkowski/kawasaki-robot-tcp-client",
    "kawasaki_ethz": "https://github.com/ethz-asl/kawasaki",
    "khi2cpp_hw": "https://github.com/ham-lab-isu/khi2cpp_hw",
}

PDFS = {
    "KJ264J_especificacao_controlador_E.pdf": "https://kawasakirobotics.com/uploads/sites/2/2025/02/KJ264J.pdf",
    "KJ264_Wall_folheto.pdf": "https://robotics.kawasaki.com/userAssets1/productPDF/KJ264Wall.pdf",
    "KJ264U_KJ314U_especificacao.pdf": "https://robotics.kawasaki.com/userAssets1/productPDF/KJ264UFE35_KJ314UWE35-E.pdf",
}

PAGINA_CAD = "https://robotics.kawasaki.com/en1/products/robots/painting/KJ264-shelf/index.html"


def baixar_repo(nome, url, atualizar):
    pasta = DESTINO / "repos" / nome
    if (pasta / ".git").exists():
        if not atualizar:
            return "já existe"
        r = subprocess.run(["git", "-C", str(pasta), "pull", "--ff-only"], capture_output=True, text=True)
        return "atualizado" if r.returncode == 0 else "falhou ao atualizar: " + r.stderr.strip()
    pasta.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["git", "clone", "--depth", "1", url, str(pasta)], capture_output=True, text=True)
    return "baixado" if r.returncode == 0 else "falhou: " + r.stderr.strip()


def baixar_pdf(nome, url):
    arquivo = DESTINO / "pdf" / nome
    if arquivo.exists():
        return "já existe"
    arquivo.parent.mkdir(parents=True, exist_ok=True)
    pedido = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(pedido, timeout=60) as resp:
            dados = resp.read()
    except Exception as erro:  # rede, 404, site fora
        return f"falhou: {erro}"
    if not dados.startswith(b"%PDF"):
        return "falhou: o site não devolveu um PDF"
    arquivo.write_bytes(dados)
    return f"baixado ({len(dados) // 1024} KB)"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--atualizar", action="store_true", help="atualiza os repositórios que já existem")
    args = p.parse_args()

    falhas = 0
    print(f"Destino: {DESTINO}\n\nRepositórios:")
    for nome, url in REPOS.items():
        resultado = baixar_repo(nome, url, args.atualizar)
        falhas += resultado.startswith("falhou")
        print(f"  {nome}: {resultado}")

    print("\nPDFs:")
    for nome, url in PDFS.items():
        resultado = baixar_pdf(nome, url)
        falhas += resultado.startswith("falhou")
        print(f"  {nome}: {resultado}")

    print(f"\nCAD do KJ264: baixe à mão em {PAGINA_CAD}")
    print(f"e salve em {DESTINO / 'cad'}")
    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
