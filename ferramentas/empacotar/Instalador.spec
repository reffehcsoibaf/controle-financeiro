# -*- mode: python ; coding: utf-8 -*-
# O instalador de um arquivo só, com o zip do programa dentro. Gerado por
# ferramentas/empacotar.py, que passa o zip em FINANCEIRO_PACOTE.
import os

RAIZ = os.path.abspath(os.path.join(SPECPATH, "..", ".."))

a = Analysis([os.path.join(SPECPATH, "instalar.py")], pathex=[RAIZ], binaries=[],
             datas=[(os.environ["FINANCEIRO_PACOTE"], "."),
                    (os.path.join(RAIZ, "financeiro", "guia.txt"), "financeiro")],
             hiddenimports=["financeiro.gerenciar"], hookspath=[], hooksconfig={},
             runtime_hooks=[], excludes=["tkinter", "pytest", "PyInstaller"],
             noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name="ControleFinanceiro",
          debug=False, strip=False, upx=False, runtime_tmpdir=None, console=False,
          icon=os.path.join(SPECPATH, "financeiro.ico"),
          version=os.environ.get("FINANCEIRO_VERSAO"))
