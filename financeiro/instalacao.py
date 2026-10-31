"""Instalar, criar cópia portátil e desinstalar. Sem wxPython.

Instalado: %LOCALAPPDATA%\\Programs\\ControleFinanceiro, sem precisar de
administrador, com os dados em %APPDATA%\\ControleFinanceiro. Portátil: a pasta
`dados` ao lado do executável guarda tudo, inclusive a sessão de login.
"""
import contextlib
import logging
import os
import shutil
import subprocess

from financeiro import DESENVOLVEDOR, NOME, __version__, atualizacao, config

log = logging.getLogger("financeiro.instalacao")

EXE = atualizacao.EXE
PASTA = atualizacao.PASTA
MARCA = "instalado.txt"
CHAVE_DESINSTALAR = (r"Software\Microsoft\Windows\CurrentVersion\Uninstall"
                     r"\ControleFinanceiro")
FOLDERID_DESKTOP = "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"
FOLDERID_PROGRAMS = "{A77F5D77-2E2B-44C3-A6A2-ABA601054A51}"


class ErroInstalacao(Exception):
    """O texto é dito à pessoa."""


def pasta_de_instalacao_padrao():
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    return os.path.join(base, "Programs", PASTA)


def modo(programa=None):
    programa = programa or config.pasta_do_programa()
    if not programa:
        return "desenvolvimento"
    return "instalado" if os.path.isfile(os.path.join(programa, MARCA)) else "portatil"


def _mesma_pasta(a, b):
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


def _dentro_de(pasta, pai):
    return os.path.normcase(os.path.abspath(pasta)).startswith(
        os.path.normcase(os.path.abspath(pai)) + os.sep)


def faixa(progresso, inicio, fim):
    """Leva o avanço de 0 a 100 de uma etapa para a faixa [inicio, fim] do total."""
    if progresso is None:
        return None

    def mapeado(pct, texto=""):
        progresso(None if pct is None else inicio + pct * (fim - inicio) / 100, texto)
    return mapeado


def _copiar_programa(origem, destino, progresso=None):
    ignorados = (config.PASTA_DADOS_PORTATIL, MARCA)
    arquivos, total = [], 0
    for raiz, pastas, nomes in os.walk(origem):
        if _mesma_pasta(raiz, origem):
            pastas[:] = [p for p in pastas if p not in ignorados]
            nomes = [n for n in nomes if n not in ignorados]
        relativo = os.path.relpath(raiz, origem)
        alvo = destino if relativo == "." else os.path.join(destino, relativo)
        os.makedirs(alvo, exist_ok=True)
        for nome in nomes:
            de = os.path.join(raiz, nome)
            arquivos.append((de, os.path.join(alvo, nome), os.path.getsize(de)))
            total += arquivos[-1][2]
    feito = 0
    for de, para, tamanho in arquivos:
        shutil.copy2(de, para)
        feito += tamanho
        if progresso:
            progresso(feito * 100 / max(total, 1), "Copiando o programa")


# ---- atalhos e registro ----
def _atalho(caminho, alvo, pasta):
    """Cria o .lnk pelo WScript.Shell, sem janela. Os caminhos vão em variáveis
    de ambiente para não precisar escapar nada."""
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    script = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:CF_LNK);"
              "$s.TargetPath=$env:CF_ALVO;$s.WorkingDirectory=$env:CF_PASTA;$s.Save()")
    r = subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        env=dict(os.environ, CF_LNK=caminho, CF_ALVO=alvo, CF_PASTA=pasta),
        capture_output=True, timeout=60,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if r.returncode != 0 or not os.path.isfile(caminho):
        raise ErroInstalacao("Não consegui criar o atalho %s."
                             % os.path.basename(caminho))


def _pasta_conhecida(guid):
    """A área de trabalho pode estar no OneDrive: quem sabe o caminho é o Windows."""
    try:
        import ctypes
        import uuid
        from ctypes import wintypes

        class GUID(ctypes.Structure):
            _fields_ = [("a", wintypes.DWORD), ("b", wintypes.WORD),
                        ("c", wintypes.WORD), ("d", ctypes.c_ubyte * 8)]

        u = uuid.UUID(guid)
        g = GUID(u.time_low, u.time_mid, u.time_hi_version,
                 (ctypes.c_ubyte * 8)(*u.bytes[8:]))
        saida = ctypes.c_wchar_p()
        if ctypes.windll.shell32.SHGetKnownFolderPath(
                ctypes.byref(g), 0, None, ctypes.byref(saida)) != 0:
            return None
        caminho = saida.value
        ctypes.windll.ole32.CoTaskMemFree(saida)
        return caminho
    except Exception as e:
        log.warning("pasta especial %s: %r", guid, e)
        return None


def _caminhos_dos_atalhos():
    """(menu Iniciar, área de trabalho)."""
    programas = _pasta_conhecida(FOLDERID_PROGRAMS) or os.path.join(
        os.environ.get("APPDATA") or os.path.expanduser("~"),
        "Microsoft", "Windows", "Start Menu", "Programs")
    area = _pasta_conhecida(FOLDERID_DESKTOP) or os.path.join(
        os.path.expanduser("~"), "Desktop")
    return (os.path.join(programas, NOME + ".lnk"),
            os.path.join(area, NOME + ".lnk"))


def registrar(destino):
    """A entrada em Aplicativos instalados."""
    import winreg
    exe = os.path.join(destino, EXE)
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, CHAVE_DESINSTALAR, 0,
                            winreg.KEY_SET_VALUE) as k:
        for nome, valor in (("DisplayName", NOME), ("DisplayVersion", __version__),
                            ("Publisher", DESENVOLVEDOR),
                            ("InstallLocation", destino), ("DisplayIcon", exe),
                            ("UninstallString", '"%s" --desinstalar' % exe)):
            winreg.SetValueEx(k, nome, 0, winreg.REG_SZ, valor)
        winreg.SetValueEx(k, "NoModify", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(k, "NoRepair", 0, winreg.REG_DWORD, 1)


def _desregistrar():
    import winreg
    try:
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, CHAVE_DESINSTALAR)
    except FileNotFoundError:
        pass


# ---- instalador de um arquivo só ----
@contextlib.contextmanager
def extrair_pacote(arquivo_zip, progresso=None):
    """Entrega a pasta do programa extraída numa pasta de trabalho e a apaga depois."""
    base = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"),
                        "ControleFinanceiro-instalador")
    pasta = os.path.join(base, str(os.getpid()))
    try:
        try:
            yield atualizacao.extrair(arquivo_zip, pasta, (
                lambda feito, total: progresso(feito * 100 / max(total, 1),
                                               "Extraindo o programa"))
                if progresso else None)
        except atualizacao.ErroAtualizacao as e:
            raise ErroInstalacao(str(e))
    finally:
        shutil.rmtree(pasta, ignore_errors=True)
        try:
            os.rmdir(base)
        except OSError:
            pass


# ---- instalar ----
def _conferir_destino(programa, destino):
    if not os.path.isfile(os.path.join(programa, EXE)):
        raise ErroInstalacao("Não achei o %s em %s." % (EXE, programa))
    if _mesma_pasta(programa, destino) or _dentro_de(destino, programa):
        raise ErroInstalacao("Escolha uma pasta fora da pasta do programa.")
    # A pasta é trocada inteira: uma pasta qualquer, ou uma cópia portátil com a
    # pasta `dados`, nunca pode ser apagada por engano.
    if os.path.isdir(destino) and os.listdir(destino) and \
            not os.path.isfile(os.path.join(destino, MARCA)):
        raise ErroInstalacao(
            "A pasta %s já tem outros arquivos. Escolha outra pasta." % destino)


def instalar(programa, destino=None, atalho_menu=True, atalho_area=False,
             fazer_atalho=_atalho, fazer_registro=registrar, progresso=None):
    """Devolve (caminho do executável, avisos)."""
    destino = os.path.abspath(destino or pasta_de_instalacao_padrao())
    _conferir_destino(programa, destino)
    nova, antiga = destino + ".nova", destino + ".antiga"
    shutil.rmtree(nova, ignore_errors=True)
    shutil.rmtree(antiga, ignore_errors=True)
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    trocou = False
    try:
        _copiar_programa(programa, nova, faixa(progresso, 0, 90))
        with open(os.path.join(nova, MARCA), "w", encoding="utf-8") as f:
            f.write("Esta pasta é uma instalação do %s.\n" % NOME)
        if os.path.exists(destino):
            try:
                os.rename(destino, antiga)
                trocou = True
            except OSError:
                raise ErroInstalacao(
                    "Não consegui trocar a instalação que já existe em %s. Feche o "
                    "%s e tente de novo." % (destino, NOME))
        os.rename(nova, destino)
    except BaseException:
        shutil.rmtree(nova, ignore_errors=True)
        if trocou and not os.path.exists(destino):
            os.rename(antiga, destino)
        raise
    shutil.rmtree(antiga, ignore_errors=True)
    if progresso:
        progresso(95, "Criando os atalhos")
    exe = os.path.join(destino, EXE)
    avisos = []
    menu, area = _caminhos_dos_atalhos()
    for quer, atalho in ((atalho_menu, menu), (atalho_area, area)):
        if quer:
            try:
                fazer_atalho(atalho, exe, destino)
            except Exception as e:
                log.warning("atalho %s: %r", atalho, e)
                avisos.append(str(e))
    try:
        fazer_registro(destino)
    except Exception as e:
        log.warning("registro de desinstalação: %r", e)
        avisos.append("Não consegui criar a entrada em Aplicativos instalados.")
    if progresso:
        progresso(100, "Instalado")
    return exe, avisos


def criar_portatil(programa, pasta_destino, progresso=None):
    """Cria a pasta do programa dentro de `pasta_destino`, com a pasta `dados`
    vazia. Devolve o caminho criado."""
    alvo = os.path.join(os.path.abspath(pasta_destino), PASTA)
    if not os.path.isfile(os.path.join(programa, EXE)):
        raise ErroInstalacao("Não achei o %s em %s." % (EXE, programa))
    if os.path.exists(alvo) and os.listdir(alvo):
        raise ErroInstalacao("A pasta %s já existe e não está vazia. Escolha outro "
                             "lugar ou apague essa pasta." % alvo)
    if _dentro_de(alvo, programa):
        raise ErroInstalacao("Escolha um lugar fora da pasta do programa.")
    try:
        _copiar_programa(programa, alvo, progresso)
        os.makedirs(os.path.join(alvo, config.PASTA_DADOS_PORTATIL))
    except BaseException:
        shutil.rmtree(alvo, ignore_errors=True)
        raise
    return alvo


# ---- desinstalar ----
def desinstalar(programa=None, apagar_dados=False):
    """A pasta do programa (o executável está em uso) é apagada por um processo
    separado, logo depois que este terminar."""
    programa = programa or config.pasta_do_programa()
    if not programa or modo(programa) != "instalado":
        raise ErroInstalacao("Isto não é uma instalação do %s." % NOME)
    for atalho in _caminhos_dos_atalhos():
        try:
            os.remove(atalho)
        except OSError:
            pass
    try:
        _desregistrar()
    except OSError as e:
        log.warning("entrada de Aplicativos instalados: %r", e)
    if apagar_dados:
        shutil.rmtree(config.dados_do_computador(), ignore_errors=True)
    subprocess.Popen(
        'for /l %%i in (1,1,30) do (ping -n 2 127.0.0.1 >nul & '
        'rmdir /s /q "%s" 2>nul & if not exist "%s" exit)' % (programa, programa),
        shell=True, creationflags=(
            getattr(subprocess, "CREATE_NO_WINDOW", 0) |
            getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)))
