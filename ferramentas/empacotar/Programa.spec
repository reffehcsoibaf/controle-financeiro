# -*- mode: python ; coding: utf-8 -*-
# O programa, em uma pasta. Gerado por ferramentas/empacotar.py.
import os

from PyInstaller.utils.hooks import collect_all, collect_submodules

RAIZ = os.path.abspath(os.path.join(SPECPATH, "..", ".."))

datas = [(os.path.join(RAIZ, "financeiro", "guia.txt"), "financeiro"),
         (os.path.join(RAIZ, "financeiro", "novidades.txt"), "financeiro")]
binaries = []
hiddenimports = collect_submodules("financeiro")
for pacote in ("accessible_output2", "openpyxl", "xlrd"):
    d, b, h = collect_all(pacote)
    datas += d
    binaries += b
    hiddenimports += h

a = Analysis([os.path.join(SPECPATH, "iniciar.py")], pathex=[RAIZ],
             binaries=binaries, datas=datas, hiddenimports=hiddenimports,
             hookspath=[], hooksconfig={}, runtime_hooks=[],
             excludes=["tkinter", "pytest", "PyInstaller"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="ControleFinanceiro",
          debug=False, strip=False, upx=False, console=False,
          icon=os.path.join(SPECPATH, "financeiro.ico"),
          version=os.environ.get("FINANCEIRO_VERSAO"))
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False,
               name="ControleFinanceiro")
