"""Login com Google e download dos dados do Supabase, sem interface.

O login abre o navegador padrão e recebe a resposta num endereço local
(fluxo PKCE). A sessão fica guardada cifrada com DPAPI, presa à conta do
Windows. Chamadas de rede rodam fora da thread da janela.
"""
import base64
import ctypes
from ctypes import wintypes
import email.utils
import hashlib
import http.server
import json
import logging
import os
import secrets
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

from financeiro import config

log = logging.getLogger("financeiro.nuvem")
TAMANHO_PAGINA = 1000
TEMPO_REDE = 30
TEMPO_LOGIN = 300


class SemConexao(Exception):
    pass


class SessaoExpirada(Exception):
    pass


class AcessoNegado(Exception):
    pass


class ErroServidor(Exception):
    pass


class LoginCancelado(Exception):
    pass


# ---- DPAPI ----
class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD),
                ("pbData", ctypes.POINTER(ctypes.c_char))]


def _dpapi(funcao, dados):
    buffer = ctypes.create_string_buffer(dados, len(dados))
    entrada = _Blob(len(dados), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    saida = _Blob()
    if not funcao(ctypes.byref(entrada), None, None, None, None, 0,
                  ctypes.byref(saida)):
        raise OSError("DPAPI falhou")
    try:
        return ctypes.string_at(saida.pbData, saida.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(saida.pbData)


def cifrar(dados):
    return _dpapi(ctypes.windll.crypt32.CryptProtectData, dados)


def decifrar(dados):
    return _dpapi(ctypes.windll.crypt32.CryptUnprotectData, dados)


# ---- relógio ----
_defasagem = None


def _anotar_relogio(cabecalho_date):
    """Segundos que somados à hora deste computador dão a hora do servidor."""
    global _defasagem
    try:
        servidor = email.utils.parsedate_to_datetime(cabecalho_date).timestamp()
    except (TypeError, ValueError):
        return
    _defasagem = servidor - time.time()


def defasagem():
    """None até a primeira resposta do servidor."""
    return _defasagem


# ---- HTTP ----
def _pedir(url, corpo=None, cabecalhos=None, abrir=urllib.request.urlopen,
           metodo=None):
    dados = json.dumps(corpo).encode() if corpo is not None else None
    pedido = urllib.request.Request(url, data=dados, method=metodo, headers={
        "apikey": config.SUPABASE_KEY, "Content-Type": "application/json",
        **(cabecalhos or {})})
    try:
        with abrir(pedido, timeout=TEMPO_REDE) as r:
            _anotar_relogio(r.headers.get("Date"))
            return json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        texto = e.read().decode(errors="replace")[:300]
        log.warning("HTTP %s em %s: %s", e.code, url.split("?")[0], texto)
        # Só isto derruba a sessão; outro erro não pode tirar a pessoa da conta.
        if e.code == 401 or (e.code == 400 and "/auth/v1/" in url):
            raise SessaoExpirada(texto) from e
        raise ErroServidor("O servidor respondeu %s." % e.code) from e
    except (urllib.error.URLError, socket.timeout, TimeoutError,
            ConnectionError) as e:
        raise SemConexao(str(e)) from e


def _pkce():
    verificador = base64.urlsafe_b64encode(secrets.token_bytes(48)) \
        .rstrip(b"=").decode()
    desafio = base64.urlsafe_b64encode(
        hashlib.sha256(verificador.encode()).digest()).rstrip(b"=").decode()
    return verificador, desafio


PAGINA_RETORNO = """<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<title>%s</title><h1>%s</h1><p>%s</p></html>"""


class _Retorno(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        partes = urllib.parse.urlsplit(self.path)
        if partes.path != "/callback":
            self.send_error(404)
            return
        q = urllib.parse.parse_qs(partes.query)
        self.server.codigo = (q.get("code") or [""])[0]
        self.server.erro = (q.get("error_description") or q.get("error")
                            or [""])[0]
        if self.server.codigo:
            t, m = "Login concluído", "Pode fechar esta aba e voltar ao programa."
        else:
            t, m = "Login não concluído", "Volte ao programa e tente de novo."
        corpo = (PAGINA_RETORNO % (t, t, m)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(corpo)))
        self.end_headers()
        self.wfile.write(corpo)

    def log_message(self, *args):
        pass


class Nuvem:
    def __init__(self):
        self.sessao = self._ler_sessao()
        self._cancelar = threading.Event()

    # ---- sessão ----
    def _ler_sessao(self):
        try:
            with open(config.caminho_sessao(), "rb") as f:
                return json.loads(decifrar(f.read()).decode())
        except FileNotFoundError:
            return None
        except (OSError, ValueError) as e:
            log.warning("sessão guardada ilegível: %r", e)
            return None

    def _guardar_sessao(self, resposta):
        self.sessao = {
            "access_token": resposta["access_token"],
            "refresh_token": resposta["refresh_token"],
            "expires_at": time.time() + resposta.get("expires_in", 3600),
            "user_id": resposta["user"]["id"],
            "email": resposta["user"].get("email", ""),
        }
        temporario = config.caminho_sessao() + ".tmp"
        with open(temporario, "wb") as f:
            f.write(cifrar(json.dumps(self.sessao).encode()))
        os.replace(temporario, config.caminho_sessao())

    def sair(self):
        self.sessao = None
        try:
            os.remove(config.caminho_sessao())
        except FileNotFoundError:
            pass

    @property
    def email(self):
        return self.sessao["email"] if self.sessao else ""

    # ---- login ----
    def cancelar_login(self):
        self._cancelar.set()

    def entrar(self):
        """Bloqueia até o login terminar; chamar fora da thread da janela."""
        self._cancelar.clear()
        verificador, desafio = _pkce()
        try:
            servidor = http.server.HTTPServer(
                ("127.0.0.1", config.PORTA_LOGIN), _Retorno)
        except OSError as e:
            raise ErroServidor("A porta do login já está em uso.") from e
        servidor.timeout = 0.5
        servidor.codigo = servidor.erro = ""
        url = config.SUPABASE_URL + "/auth/v1/authorize?" + urllib.parse.urlencode({
            "provider": "google", "redirect_to": config.URL_RETORNO,
            "code_challenge": desafio, "code_challenge_method": "s256"})
        try:
            webbrowser.open(url)
            limite = time.time() + TEMPO_LOGIN
            while not (servidor.codigo or servidor.erro):
                if self._cancelar.is_set() or time.time() > limite:
                    raise LoginCancelado()
                servidor.handle_request()
        finally:
            servidor.server_close()
        if servidor.erro:
            raise ErroServidor(servidor.erro)
        resposta = _pedir(
            config.SUPABASE_URL + "/auth/v1/token?grant_type=pkce",
            {"auth_code": servidor.codigo, "code_verifier": verificador})
        self._guardar_sessao(resposta)

    def _token_valido(self):
        if not self.sessao:
            raise SessaoExpirada("sem sessão")
        if self.sessao["expires_at"] - time.time() < 60:
            resposta = _pedir(
                config.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token",
                {"refresh_token": self.sessao["refresh_token"]})
            self._guardar_sessao(resposta)
        return self.sessao["access_token"]

    # ---- dados ----
    def _tabela(self, tabela, ordem, token, pedir=_pedir):
        linhas, deslocamento = [], 0
        while True:
            q = urllib.parse.urlencode({
                "select": "*", "user_id": "eq." + self.sessao["user_id"],
                "order": ordem, "limit": TAMANHO_PAGINA,
                "offset": deslocamento})
            pagina = pedir("%s/rest/v1/%s?%s" % (config.SUPABASE_URL, tabela, q),
                           cabecalhos={"Authorization": "Bearer " + token})
            linhas += pagina
            if len(pagina) < TAMANHO_PAGINA:
                return linhas
            deslocamento += TAMANHO_PAGINA

    def conferir_acesso(self, token):
        q = urllib.parse.urlencode({
            "select": "habilitado", "user_id": "eq." + self.sessao["user_id"],
            "modulo": "eq." + config.MODULO})
        linhas = _pedir("%s/rest/v1/profiles_modulos?%s"
                        % (config.SUPABASE_URL, q),
                        cabecalhos={"Authorization": "Bearer " + token})
        if not linhas or linhas[0].get("habilitado") is not True:
            raise AcessoNegado()

    def enviar_pago(self, pendencia, token, pedir=_pedir):
        """Devolve o novo updated_at, ou None se o lançamento mudou ou sumiu no
        servidor desde a marcação (conflito: nada é sobrescrito)."""
        filtro = {"id": "eq.%d" % pendencia["lancamento_id"],
                  "user_id": "eq." + self.sessao["user_id"],
                  "select": "updated_at"}
        if pendencia["base_updated_at"]:
            filtro["updated_at"] = "eq." + pendencia["base_updated_at"]
        linhas = pedir(
            "%s/rest/v1/financeiro_lancamentos?%s"
            % (config.SUPABASE_URL, urllib.parse.urlencode(filtro)),
            corpo={"pago": bool(pendencia["pago"])},
            cabecalhos={"Authorization": "Bearer " + token,
                        "Prefer": "return=representation"},
            metodo="PATCH")
        return linhas[0]["updated_at"] if linhas else None

    def enviar_lancamento(self, envio, token, pedir=_pedir):
        """Devolve {"status": "ok", "id", "updated_at"} ou {"status": "conflito"}."""
        cabecalhos = {"Authorization": "Bearer " + token}
        i = envio["lancamento_id"]
        if envio["operacao"] == "excluir":
            filtro = {"id": "eq.%d" % i, "user_id": "eq." + self.sessao["user_id"]}
            if envio["base_updated_at"]:
                filtro["updated_at"] = "eq." + envio["base_updated_at"]
            linhas = pedir(
                "%s/rest/v1/financeiro_lancamentos?%s"
                % (config.SUPABASE_URL, urllib.parse.urlencode(filtro)),
                cabecalhos=dict(cabecalhos, Prefer="return=representation"),
                metodo="DELETE")
            # Nada apagado: já foi apagado lá, ou mudou. O download decide.
            return {"status": "ok" if linhas else "conflito"}
        return pedir(
            config.SUPABASE_URL + "/rest/v1/rpc/financeiro_salvar_lancamento",
            corpo={"p_id": i if i > 0 else None,
                   "p_base": envio["base_updated_at"],
                   "p_client_uuid": envio["client_uuid"],
                   "p_dados": {k: v for k, v in envio["dados"].items()
                               if k not in ("id", "updated_at",
                                            "lancamento_vinculado_id")},
                   "p_itens": envio["itens"],
                   "p_pagamentos": envio["pagamentos"]},
            cabecalhos=cabecalhos)

    def sincronizar(self, pendencias, resultados, envios=(), resultados_lanc=None):
        """Envia as alterações e baixa tudo. Os resultados são anotados à medida
        que acontecem, para valer mesmo se a conexão cair depois. Erro do
        servidor num lançamento não impede o resto: ele fica na fila."""
        token = self._token_valido()
        self.conferir_acesso(token)
        for envio in envios:
            try:
                resposta = self.enviar_lancamento(envio, token)
            except ErroServidor as e:
                log.warning("lançamento %s não enviado: %s",
                            envio["lancamento_id"], e)
                resposta = {"status": "erro"}
            resultados_lanc.append((envio, resposta))
        for pendencia in pendencias:
            resultados.append((pendencia, self.enviar_pago(pendencia, token)))
        return self.baixar_tudo()

    def baixar_tudo(self):
        token = self._token_valido()
        return converter({
            "lancamentos": self._tabela("financeiro_lancamentos", "id", token),
            "itens": self._tabela("financeiro_itens_lancamento", "id", token),
            "pagamentos": self._tabela("financeiro_pagamentos_lancamento",
                                       "id", token),
            "contas": self._tabela("financeiro_contas_config", "id", token),
            "opcoes": self._tabela("financeiro_opcoes_listas", "id", token),
            "documentos": self._tabela("financeiro_documentos_armazenados",
                                       "id", token),
        })


def converter(tabelas):
    """Valores monetários chegam como número ou texto; aqui viram float."""
    for tabela in ("lancamentos", "itens", "pagamentos"):
        for linha in tabelas[tabela]:
            linha["valor"] = float(linha.get("valor") or 0)
    for linha in tabelas["lancamentos"]:
        linha["pago"] = 1 if linha.get("pago") else 0
    for linha in tabelas["contas"]:
        for campo in ("saldo_inicial", "limite_total"):
            if linha.get(campo) is not None:
                linha[campo] = float(linha[campo])
    return tabelas
