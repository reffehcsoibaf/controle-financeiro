import base64
import hashlib
import threading
import urllib.error
import urllib.request

import pytest

from financeiro import config, nuvem


@pytest.fixture(autouse=True)
def pasta_temporaria(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))


RESPOSTA_TOKEN = {"access_token": "a", "refresh_token": "r", "expires_in": 3600,
                  "user": {"id": "u1", "email": "x@exemplo.com"}}


def test_dpapi_volta_ao_original_e_nao_guarda_em_claro():
    cifrado = nuvem.cifrar(b"segredo")
    assert b"segredo" not in cifrado
    assert nuvem.decifrar(cifrado) == b"segredo"


def test_sessao_sobrevive_a_reabrir_e_some_ao_sair():
    n = nuvem.Nuvem()
    n._guardar_sessao(RESPOSTA_TOKEN)
    outra = nuvem.Nuvem()
    assert outra.email == "x@exemplo.com"
    outra.sair()
    assert nuvem.Nuvem().sessao is None


def test_pkce_desafio_confere_com_verificador():
    verificador, desafio = nuvem._pkce()
    esperado = base64.urlsafe_b64encode(
        hashlib.sha256(verificador.encode()).digest()).rstrip(b"=").decode()
    assert desafio == esperado


def test_login_recebe_o_codigo_e_troca_pela_sessao(monkeypatch):
    enviados = {}

    def navegador_falso(url):
        enviados["url"] = url
        threading.Timer(0.1, lambda: urllib.request.urlopen(
            config.URL_RETORNO + "?code=abc", timeout=5).read()).start()

    def pedir_falso(url, corpo=None, cabecalhos=None):
        enviados["token_url"], enviados["corpo"] = url, corpo
        return RESPOSTA_TOKEN

    monkeypatch.setattr(nuvem.webbrowser, "open", navegador_falso)
    monkeypatch.setattr(nuvem, "_pedir", pedir_falso)
    n = nuvem.Nuvem()
    n.entrar()
    assert "provider=google" in enviados["url"]
    assert "redirect_to=http%3A%2F%2F127.0.0.1" in enviados["url"]
    assert enviados["corpo"]["auth_code"] == "abc"
    assert n.sessao["user_id"] == "u1"


def test_login_pode_ser_cancelado(monkeypatch):
    monkeypatch.setattr(nuvem.webbrowser, "open", lambda url: None)
    n = nuvem.Nuvem()
    threading.Timer(0.1, n.cancelar_login).start()
    with pytest.raises(nuvem.LoginCancelado):
        n.entrar()


def test_paginacao_junta_todas_as_paginas():
    n = nuvem.Nuvem()
    n._guardar_sessao(RESPOSTA_TOKEN)
    pedidos = []

    def pedir(url, cabecalhos=None):
        pedidos.append(url)
        return [{}] * (nuvem.TAMANHO_PAGINA if len(pedidos) == 1 else 3)

    assert len(n._tabela("t", "id", "tok", pedir)) == nuvem.TAMANHO_PAGINA + 3
    assert "offset=1000" in pedidos[1] and "user_id=eq.u1" in pedidos[0]


def test_marcar_pago_so_grava_se_o_servidor_nao_mudou():
    n = nuvem.Nuvem()
    n._guardar_sessao(RESPOSTA_TOKEN)
    visto = {}

    def pedir(url, corpo=None, cabecalhos=None, metodo=None):
        visto.update(url=url, corpo=corpo, metodo=metodo)
        return [{"updated_at": "novo"}]

    p = dict(lancamento_id=7, pago=1, base_updated_at="2026-10-30T10:00:00+00:00")
    assert n.enviar_pago(p, "tok", pedir) == "novo"
    assert visto["metodo"] == "PATCH" and visto["corpo"] == {"pago": True}
    assert "id=eq.7" in visto["url"] and "user_id=eq.u1" in visto["url"]
    assert "updated_at=eq.2026-10-30T10%3A00%3A00%2B00%3A00" in visto["url"]
    assert n.enviar_pago(p, "tok", lambda *a, **k: []) is None


def test_envio_feito_antes_da_queda_fica_registrado(monkeypatch):
    n = nuvem.Nuvem()
    n._guardar_sessao(RESPOSTA_TOKEN)
    monkeypatch.setattr(n, "conferir_acesso", lambda t: None)
    monkeypatch.setattr(n, "enviar_pago", lambda p, t: "novo")

    def cair():
        raise nuvem.SemConexao("caiu")

    monkeypatch.setattr(n, "baixar_tudo", cair)
    resultados = []
    p = dict(lancamento_id=1, pago=1, base_updated_at="x")
    with pytest.raises(nuvem.SemConexao):
        n.sincronizar([p], resultados)
    assert resultados == [(p, "novo")]


def test_erro_403_nao_derruba_a_sessao():
    def abrir(*a, **k):
        raise urllib.error.HTTPError("http://x", 403, "", {}, None)
    with pytest.raises(nuvem.ErroServidor):
        nuvem._pedir("http://x/rest/v1/t", abrir=abrir)


def test_sem_rede_vira_sem_conexao():
    def abrir(*a, **k):
        raise urllib.error.URLError("sem rota")
    with pytest.raises(nuvem.SemConexao):
        nuvem._pedir("http://x", abrir=abrir)


def test_acesso_desativado(monkeypatch):
    n = nuvem.Nuvem()
    n._guardar_sessao(RESPOSTA_TOKEN)
    monkeypatch.setattr(nuvem, "_pedir", lambda *a, **k: [])
    with pytest.raises(nuvem.AcessoNegado):
        n.conferir_acesso("tok")
