"""Atualização pelas Releases do GitHub: consulta, baixa o zip, confere o SHA-256
e, depois que o programa fecha, um script troca a pasta e abre a versão nova.
Sem wxPython.

O repositório é o mesmo do site, mas o site não publica Releases, e tags fora do
formato ano.mes.dia.dígito são ignoradas.
"""
import hashlib
import logging
import os
import re
import shutil
import socket
import ssl
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field

from financeiro import __version__, config

log = logging.getLogger("financeiro.atualizacao")

REPOSITORIO = "https://github.com/reffehcsoibaf/controle-financeiro"
URL_LANCAMENTOS = ("https://api.github.com/repos/reffehcsoibaf/controle-financeiro"
                   "/releases?per_page=15")
PASTA = "ControleFinanceiro"
EXE = "ControleFinanceiro.exe"
ZIP = "ControleFinanceiro.zip"
MANTER = (config.PASTA_DADOS_PORTATIL, "instalado.txt")
HOSTS = ("github.com", "api.github.com")
IDENTIFICACAO = "ControleFinanceiroWindows/" + __version__


class ErroAtualizacao(Exception):
    """O texto é dito à pessoa."""


@dataclass
class Lancamento:
    versao: str
    pagina: str = ""
    url_zip: str = ""
    url_hash: str = ""
    tamanho: int = 0
    # [(versão, novidades)] do que saiu depois da versão em uso, da mais nova
    # para a mais antiga
    historico: list = field(default_factory=list)


# ---- consulta ----
def como_tupla(versao):
    if re.fullmatch(r"\d{4}\.\d{1,2}\.\d{1,2}\.\d+", str(versao or "")):
        return tuple(int(x) for x in versao.split("."))
    return None


def _versao_da_tag(release):
    return str(release.get("tag_name") or "").lstrip("vV")


def novidades_do_corpo(corpo):
    """A seção de novidades das notas; sem ela, as notas inteiras."""
    corpo = str(corpo or "").replace("\r\n", "\n").strip()
    achou = re.search(r"^##\s+Novidades da vers[ãa]o[^\n]*\n(.*?)(?=^##\s|\Z)",
                      corpo, re.S | re.M | re.I)
    return (achou.group(1).strip() if achou else corpo) or "(sem novidades descritas)"


def mais_nova(dados, atual=__version__):
    """O lançamento de maior versão na lista da API, ou None."""
    validas = []
    for release in dados if isinstance(dados, list) else []:
        if isinstance(release, dict) and not release.get("draft") \
                and not release.get("prerelease"):
            chave = como_tupla(_versao_da_tag(release))
            if chave:
                validas.append((chave, release))
    if not validas:
        return None
    validas.sort(key=lambda par: par[0], reverse=True)
    chave, release = validas[0]
    arquivos = {str(a.get("name")): a for a in release.get("assets") or []
                if isinstance(a, dict)}
    zip_, hash_ = arquivos.get(ZIP) or {}, arquivos.get(ZIP + ".sha256") or {}
    velha = como_tupla(atual)
    return Lancamento(
        _versao_da_tag(release), str(release.get("html_url") or ""),
        str(zip_.get("browser_download_url") or ""),
        str(hash_.get("browser_download_url") or ""), int(zip_.get("size") or 0),
        [(_versao_da_tag(r), novidades_do_corpo(r.get("body")))
         for c, r in validas if velha and c > velha])


def e_mais_nova(lancamento, atual=__version__):
    if not lancamento:
        return False
    nova, velha = como_tupla(lancamento.versao), como_tupla(atual)
    return bool(nova and velha and nova > velha)


def deve_avisar_ao_abrir(lancamento, ignorada, atual=__version__):
    """Avisa a cada abertura, até atualizar ou marcar "Não avisar mais nesta
    versão". Uma versão mais nova que a marcada volta a avisar."""
    return e_mais_nova(lancamento, atual) and lancamento.versao != ignorada


def _abrir(url, tempo=30):
    return urllib.request.urlopen(urllib.request.Request(
        url, headers={"User-Agent": IDENTIFICACAO,
                      "Accept": "application/vnd.github+json, */*"}), timeout=tempo)


def _buscar_json(url):
    import json
    with _abrir(url, 15) as r:
        return json.loads(r.read().decode("utf-8"))


def _pelo_site():
    """Quando a API limita as consultas: o endereço da última versão leva à
    página dela, e daí saem os endereços dos arquivos. Vem sem as novidades."""
    with _abrir(REPOSITORIO + "/releases/latest", 15) as r:
        final = r.geturl()
    achou = re.search(r"/releases/tag/([^/?#]+)$", final)
    versao = achou.group(1).lstrip("vV") if achou else ""
    if not como_tupla(versao):
        raise ValueError("página da última versão inesperada")
    base = "%s/releases/download/%s/" % (REPOSITORIO, achou.group(1))
    return Lancamento(versao, final, base + ZIP, base + ZIP + ".sha256", 0, [
        (versao, "As novidades estão na página da versão: " + final)])


def motivo_da_falha(erro):
    if isinstance(erro, urllib.error.HTTPError):
        if erro.code in (403, 429):
            return ("o GitHub limitou as consultas feitas a partir deste endereço "
                    "de internet; tente de novo daqui a uma hora")
        return "o GitHub respondeu com o erro %d" % erro.code
    causa = getattr(erro, "reason", erro)
    if isinstance(causa, ssl.SSLError):
        return ("o Windows não confiou no certificado do GitHub; confira a data e "
                "a hora do computador e o antivírus")
    if isinstance(causa, (socket.timeout, TimeoutError)):
        return "o GitHub demorou demais para responder"
    if isinstance(causa, socket.gaierror):
        return "sem conexão com a internet"
    if isinstance(erro, ValueError):
        return "a resposta do GitHub veio em um formato inesperado"
    return "a conexão com o GitHub falhou"


def consultar(buscar=_buscar_json, pelo_site=_pelo_site):
    """O lançamento mais novo (None se ainda não há nenhum)."""
    try:
        return mais_nova(buscar(URL_LANCAMENTOS))
    except (OSError, ValueError) as erro:
        motivo = motivo_da_falha(erro)
        log.warning("consulta da API falhou: %s", motivo)
    try:
        return pelo_site()
    except (OSError, ValueError) as erro:
        log.warning("consulta pelo site falhou: %r", erro)
    raise ErroAtualizacao("Não consegui verificar as atualizações: %s." % motivo)


# ---- download ----
def pasta_de_espera(programa):
    return programa.rstrip("\\/") + ".atualizacao"


def pasta_antiga(programa):
    return programa.rstrip("\\/") + ".antiga"


def limpar_restos(programa):
    """Na abertura: tira as pastas que a atualização anterior deixou."""
    for pasta in (pasta_de_espera(programa), pasta_antiga(programa)):
        shutil.rmtree(pasta, ignore_errors=True)


def _url_ok(url):
    partes = urllib.parse.urlparse(url)
    return partes.scheme == "https" and (partes.hostname or "") in HOSTS


def _hash_esperado(lancamento, abrir):
    with abrir(lancamento.url_hash) as r:
        texto = r.read(4096).decode("utf-8", "replace")
    achou = re.match(r"\s*([0-9a-fA-F]{64})\b", texto)
    if not achou:
        raise ErroAtualizacao("O arquivo de conferência da versão %s não está no "
                              "formato esperado." % lancamento.versao)
    return achou.group(1).lower()


def baixar(lancamento, pasta, progresso=None, abrir=_abrir, cancelado=None):
    """Baixa o zip para `pasta`, confere o SHA-256 e devolve o caminho.
    `progresso(feito, total)`; `cancelado()` diz se a pessoa desistiu."""
    if not (lancamento.url_zip and lancamento.url_hash):
        raise ErroAtualizacao("Esta versão não tem o arquivo para atualização "
                              "automática.")
    if not (_url_ok(lancamento.url_zip) and _url_ok(lancamento.url_hash)):
        raise ErroAtualizacao("O endereço do download não é do GitHub.")
    os.makedirs(pasta, exist_ok=True)
    destino = os.path.join(pasta, ZIP)
    resumo = hashlib.sha256()
    feito = 0
    try:
        esperado = _hash_esperado(lancamento, abrir)
        with abrir(lancamento.url_zip) as r, open(destino, "wb") as f:
            total = int(r.headers.get("Content-Length") or lancamento.tamanho or 0)
            for bloco in iter(lambda: r.read(1 << 20), b""):
                if cancelado is not None and cancelado():
                    raise ErroAtualizacao("Atualização cancelada.")
                f.write(bloco)
                resumo.update(bloco)
                feito += len(bloco)
                if progresso:
                    progresso(feito, total)
        if resumo.hexdigest() != esperado:
            raise ErroAtualizacao("O arquivo baixado não confere com o SHA-256 "
                                  "publicado, então não foi usado.")
    except ErroAtualizacao:
        _apagar(destino)
        raise
    except OSError as e:
        _apagar(destino)
        raise ErroAtualizacao("Não consegui baixar a atualização: %s. Tente de "
                              "novo." % motivo_da_falha(e))
    return destino


def _apagar(caminho):
    try:
        os.remove(caminho)
    except OSError:
        pass


def extrair(arquivo_zip, pasta, progresso=None):
    """Extrai em `pasta` e devolve a pasta do programa, de dentro dela."""
    shutil.rmtree(pasta, ignore_errors=True)
    os.makedirs(pasta)
    raiz = os.path.realpath(pasta)
    try:
        with zipfile.ZipFile(arquivo_zip) as z:
            nomes = z.namelist()
            if "%s/%s" % (PASTA, EXE) not in nomes:
                raise ErroAtualizacao("O arquivo da atualização não tem o %s." % EXE)
            for nome in nomes:
                alvo = os.path.realpath(os.path.join(pasta, nome))
                if alvo != raiz and not alvo.startswith(raiz + os.sep):
                    raise ErroAtualizacao("O arquivo da atualização tem caminhos "
                                          "inválidos.")
            for n, nome in enumerate(nomes, 1):
                z.extract(nome, pasta)
                if progresso:
                    progresso(n, len(nomes))
    except zipfile.BadZipFile:
        raise ErroAtualizacao("O arquivo da atualização está corrompido.")
    return os.path.join(pasta, PASTA)


# ---- troca da pasta ----
MODELO = r"""@echo off
chcp 65001 >nul
set "P=@P@"
set "N=@N@"
set "A=@A@"
set /a t=0
:esperar
if exist "%A%" rmdir /s /q "%A%" 2>nul
move "%P%" "%A%" >nul 2>&1
if not errorlevel 1 goto trocar
set /a t+=1
if %t% geq 90 goto abrir
ping -n 2 127.0.0.1 >nul
goto esperar
:trocar
move "%N%" "%P%" >nul 2>&1
if errorlevel 1 goto desfazer
for %%K in (@MANTER@) do if exist "%A%\%%K" move "%A%\%%K" "%P%\" >nul
goto abrir
:desfazer
move "%A%" "%P%" >nul 2>&1
:abrir
start "" "%P%\@EXE@"
exit
"""


def montar_script(programa, nova):
    texto = MODELO
    for chave, valor in (("@P@", programa.replace("%", "%%")),
                         ("@N@", nova.replace("%", "%%")),
                         ("@A@", pasta_antiga(programa).replace("%", "%%")),
                         ("@MANTER@", " ".join(MANTER)), ("@EXE@", EXE)):
        texto = texto.replace(chave, valor)
    return "\r\n".join(texto.splitlines()) + "\r\n"


def aplicar(programa, nova):
    """Inicia o script, que espera o programa fechar. Quem chama fecha em seguida."""
    script = os.path.join(os.path.dirname(os.path.dirname(nova)), "atualizar.bat")
    with open(script, "w", encoding="utf-8", newline="") as f:
        f.write(montar_script(programa, nova))
    # Sem DETACHED_PROCESS: com ele, cada ping abriria uma janela, que o leitor
    # de tela leria
    subprocess.Popen(["cmd", "/c", script], cwd=os.path.dirname(programa),
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    log.info("atualização preparada: %s", nova)
    return script
