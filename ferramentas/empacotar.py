"""Gera em lancamento/ o que vai para a Release e confere o resultado:

    ControleFinanceiro.exe         o instalador; é o que as pessoas baixam
    ControleFinanceiro.zip         a pasta do programa; é o que o atualizador baixa
    ControleFinanceiro.zip.sha256  a conferência do zip

Uso: venv\\Scripts\\python ferramentas\\empacotar.py
"""
import hashlib
import os
import subprocess
import sys
import tempfile
import zipfile

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, RAIZ)

from financeiro import DESENVOLVEDOR, NOME, __version__  # noqa: E402
from financeiro.atualizacao import EXE, PASTA, ZIP  # noqa: E402

TRABALHO = os.path.join(RAIZ, "build-pyinstaller")
SAIDA = os.path.join(RAIZ, "lancamento")
SPECS = os.path.join(RAIZ, "ferramentas", "empacotar")


def arquivo_de_versao(descricao):
    """O que o Windows mostra em Propriedades, Detalhes."""
    numeros = tuple(int(x) for x in __version__.split("."))
    caminho = os.path.join(TRABALHO, "versao-%s.txt" % descricao.split()[0])
    with open(caminho, "w", encoding="utf-8") as f:
        f.write("""VSVersionInfo(
  ffi=FixedFileInfo(filevers=%(n)s, prodvers=%(n)s, mask=0x3f, flags=0x0,
                    OS=0x40004, fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[
    StringFileInfo([StringTable('041604b0', [
      StringStruct('CompanyName', %(a)r),
      StringStruct('FileDescription', %(d)r),
      StringStruct('FileVersion', %(v)r),
      StringStruct('InternalName', %(p)r),
      StringStruct('LegalCopyright', %(c)r),
      StringStruct('OriginalFilename', %(e)r),
      StringStruct('ProductName', %(p)r),
      StringStruct('ProductVersion', %(v)r)])]),
    VarFileInfo([VarStruct('Translation', [0x0416, 1200])])
  ]
)
""" % dict(n=numeros, a=DESENVOLVEDOR, d=descricao, v=__version__, p=NOME,
           c="Copyright (C) 2026 " + DESENVOLVEDOR, e=EXE))
    return caminho


def pyinstaller(spec, dist, work, **ambiente):
    subprocess.check_call(
        [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
         "--distpath", dist, "--workpath", work, os.path.join(SPECS, spec)],
        cwd=RAIZ, env=dict(os.environ, **ambiente))


def montar_zip(pasta, destino):
    with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for atual, _, arquivos in os.walk(pasta):
            for arquivo in arquivos:
                completo = os.path.join(atual, arquivo)
                z.write(completo, os.path.join(PASTA, os.path.relpath(completo, pasta)))
    resumo = hashlib.sha256()
    with open(destino, "rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            resumo.update(bloco)
    return resumo.hexdigest()


def conferir(exe):
    """Roda o executável com a opção que testa o pacote e sai sem abrir janela."""
    with tempfile.TemporaryDirectory() as dados:
        codigo = subprocess.call(
            [exe, "--testar-pacote"], timeout=120,
            env=dict(os.environ, FINANCEIRO_DADOS=dados))
    if codigo != 0:
        sys.exit("A conferência de %s falhou (código %s)." % (exe, codigo))


def main():
    os.makedirs(SAIDA, exist_ok=True)
    os.makedirs(TRABALHO, exist_ok=True)
    programa = os.path.join(TRABALHO, "programa")
    print("1/4 Gerando o programa")
    pyinstaller("Programa.spec", programa, os.path.join(TRABALHO, "work-programa"),
                FINANCEIRO_VERSAO=arquivo_de_versao("Controle Financeiro para Windows"))
    print("2/4 Conferindo o programa")
    conferir(os.path.join(programa, PASTA, EXE))
    print("3/4 Montando o zip")
    zip_ = os.path.join(SAIDA, ZIP)
    resumo = montar_zip(os.path.join(programa, PASTA), zip_)
    with open(zip_ + ".sha256", "w", encoding="utf-8") as f:
        f.write("%s *%s\n" % (resumo, ZIP))
    print("4/4 Gerando o instalador")
    pyinstaller("Instalador.spec", SAIDA, os.path.join(TRABALHO, "work-instalador"),
                FINANCEIRO_PACOTE=zip_,
                FINANCEIRO_VERSAO=arquivo_de_versao("Instalador do Controle Financeiro"))
    instalador = os.path.join(SAIDA, EXE)
    conferir(instalador)
    print("Pronto, versão %s:\n  %s  %.0f MB\n  %s  %.0f MB\n  SHA-256 do zip: %s" % (
        __version__, ZIP, os.path.getsize(zip_) / 1048576,
        EXE, os.path.getsize(instalador) / 1048576, resumo))


if __name__ == "__main__":
    main()
