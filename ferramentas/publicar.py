"""Monta as notas e, só com --publicar, cria a Release no GitHub.

O repositório é o mesmo do site, então a tag aponta para o ramo `windows`. Criar
a Release não muda o site, que é publicado a partir do ramo `main`.

Uso: venv\\Scripts\\python ferramentas\\publicar.py [--publicar]
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, RAIZ)

from financeiro import NOME, __version__  # noqa: E402
from financeiro.atualizacao import EXE, ZIP  # noqa: E402

SAIDA = os.path.join(RAIZ, "lancamento")
RAMO = "windows"


def novidades(versao, texto=None):
    """O texto da versão em novidades.txt."""
    if texto is None:
        with open(os.path.join(RAIZ, "financeiro", "novidades.txt"),
                  encoding="utf-8") as f:
            texto = f.read()
    achou = re.search(r"^# Versão %s\n(.*?)(?=^# |\Z)" % re.escape(versao),
                      texto, re.S | re.M)
    return achou.group(1).strip() if achou else ""


def montar_notas(versao, resumo):
    return "\n".join([
        "## Novidades da versão %s" % versao, "",
        novidades(versao) or "(Sem novidades descritas.)", "",
        "## Como usar", "",
        "Baixe `%s` e abra. A primeira tela tem os botões Instalar, Cópia portátil, "
        "Guia de uso e Fechar." % EXE, "",
        "Para atualizar, use Menu, Ajuda, Verificar atualizações, dentro do programa. "
        "Os seus dados não são afetados.", "",
        "SHA-256 do zip: `%s`" % resumo, ""])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--publicar", action="store_true")
    args = ap.parse_args()
    instalador, zip_ = os.path.join(SAIDA, EXE), os.path.join(SAIDA, ZIP)
    for caminho in (instalador, zip_, zip_ + ".sha256"):
        if not os.path.isfile(caminho):
            sys.exit("Não achei %s. Rode ferramentas/empacotar.py antes." % caminho)
    with open(zip_, "rb") as f:
        resumo = hashlib.sha256(f.read()).hexdigest()
    if not novidades(__version__):
        sys.exit("novidades.txt não tem a seção da versão %s." % __version__)
    notas = os.path.join(SAIDA, "notas.md")
    with open(notas, "w", encoding="utf-8") as f:
        f.write(montar_notas(__version__, resumo))
    comando = ["gh", "release", "create", __version__, instalador, zip_,
               zip_ + ".sha256", "--repo", "reffehcsoibaf/controle-financeiro",
               "--target", RAMO, "--title", "%s para Windows %s" % (NOME, __version__),
               "--notes-file", notas, "--latest"]
    if not args.publicar:
        print("Notas em %s.\nPara publicar: %s" % (notas, " ".join(comando)))
        return 0
    return subprocess.call(comando, cwd=RAIZ)


if __name__ == "__main__":
    sys.exit(main())
