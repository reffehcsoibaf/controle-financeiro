"""Fila de alterações: o que acontece com os dados se a rede falha no meio."""
import pytest

from financeiro import nuvem, regras, sincro
from financeiro.banco import Banco
from financeiro.nuvem import converter

DADOS = dict(data_origem="2026-10-01", data_vencimento=None, valor=-10.0,
             banco="Itaú", estabelecimento=None, forma="PIX",
             descricao="Mercado", categoria=None, credor=None, devedor=None,
             pago=False)
ITEM = dict(valor=-4.0, categoria="Comida", descricao="Pão", data_original=None)


def servidor(*lancamentos, itens=(), pagamentos=()):
    return converter(dict(lancamentos=list(lancamentos), itens=list(itens),
                          pagamentos=list(pagamentos), contas=[], documentos=[],
                          opcoes=[]))


def linha(i=1, versao="v1", **campos):
    return dict(DADOS, id=i, updated_at=versao, **campos)


@pytest.fixture
def banco(tmp_path):
    b = Banco(str(tmp_path / "f.db"))
    b.substituir_tudo(servidor(linha()), "t")
    yield b
    b.fechar()


def test_criacao_offline_tem_id_temporario_e_sobrevive_ao_download(banco):
    novo = banco.salvar_lancamento(DADOS, [ITEM])
    assert novo < 0
    banco.substituir_tudo(servidor(linha()), "t2")
    por_id = {l["id"]: l for l in banco.lancamentos()}
    assert set(por_id) == {1, novo}
    assert por_id[novo]["itens"][0]["descricao"] == "Pão"
    envio = banco.envios()[0]
    assert envio["client_uuid"] and envio["base_updated_at"] is None
    assert envio["itens"] == [ITEM]


def test_confirmacao_troca_o_id_temporario_nos_itens(banco):
    novo = banco.salvar_lancamento(DADOS, [ITEM])
    banco.confirmar_lancamento(banco.envios()[0], 50, "v9")
    assert banco.envios() == []
    l = {x["id"]: x for x in banco.lancamentos()}[50]
    assert l["updated_at"] == "v9" and len(l["itens"]) == 1
    assert novo not in {x["id"] for x in banco.lancamentos()}


def test_edicao_durante_o_envio_continua_na_fila_com_id_definitivo(banco):
    banco.salvar_lancamento(DADOS, [])
    enviado = banco.envios()[0]
    banco.salvar_lancamento(dict(DADOS, descricao="Outra"), [], enviado["lancamento_id"])
    banco.confirmar_lancamento(enviado, 50, "v9")
    fila = banco.envios()
    assert len(fila) == 1 and fila[0]["lancamento_id"] == 50
    assert fila[0]["base_updated_at"] == "v9" and fila[0]["client_uuid"] is None
    assert fila[0]["dados"]["descricao"] == "Outra"


def test_edicao_usa_a_versao_lida_do_servidor(banco):
    banco.salvar_lancamento(dict(DADOS, valor=-20.0), [ITEM], 1)
    envio = banco.envios()[0]
    assert envio["base_updated_at"] == "v1" and envio["lancamento_id"] == 1
    # O download não pode desfazer a edição que ainda não subiu
    banco.substituir_tudo(servidor(linha(versao="v2")), "t2")
    l = banco.lancamentos()[0]
    assert l["valor"] == -20.0 and l["itens"][0]["descricao"] == "Pão"
    assert banco.envios()[0]["base_updated_at"] == "v1"


def test_edicao_preserva_pagamentos_e_parcela(banco):
    banco.substituir_tudo(servidor(
        linha(parcela="3/12"),
        pagamentos=[dict(id=9, lancamento_id=1, ordem=0, banco="A", forma="PIX",
                         valor=10.0)]), "t")
    banco.salvar_lancamento(dict(DADOS, descricao="Nova"), [], 1)
    envio = banco.envios()[0]
    assert envio["dados"]["parcela"] == "3/12"
    assert envio["pagamentos"] == [dict(banco="A", forma="PIX", valor=10.0)]


def test_marcar_pago_em_lancamento_na_fila_vai_junto(banco):
    banco.salvar_lancamento(DADOS, [], 1)
    banco.marcar_pago(1, True)
    assert banco.pendencias() == []
    assert banco.envios()[0]["dados"]["pago"] is True


def test_editar_depois_de_marcar_pago_herda_a_versao(banco):
    banco.marcar_pago(1, True)
    banco.salvar_lancamento(dict(DADOS, pago=True), [], 1)
    assert banco.pendencias() == []
    assert banco.envios()[0]["base_updated_at"] == "v1"


def test_excluir_some_da_tela_e_nao_volta_no_download(banco):
    banco.excluir_lancamento(1)
    assert banco.lancamentos() == []
    banco.substituir_tudo(servidor(linha()), "t2")
    assert banco.lancamentos() == []
    assert banco.envios()[0]["operacao"] == "excluir"
    banco.confirmar_exclusao(banco.envios()[0])
    assert banco.qtd_pendentes() == 0


def test_excluir_criacao_que_nunca_subiu_nao_envia_nada(banco):
    novo = banco.salvar_lancamento(DADOS, [])
    banco.excluir_lancamento(novo)
    assert banco.envios() == []
    assert banco.qtd_pendentes() == 0


def test_excluir_um_desfaz_o_vinculo_do_outro(banco):
    banco.substituir_tudo(servidor(
        linha(1, lancamento_vinculado_id=2), linha(2, lancamento_vinculado_id=1)), "t")
    banco.excluir_lancamento(1)
    assert banco.lancamentos()[0]["lancamento_vinculado_id"] is None


def test_conflito_para_de_enviar_e_pode_manter_a_versao_local(banco):
    banco.salvar_lancamento(dict(DADOS, valor=-20.0), [], 1)
    no_servidor = sincro.versao_do_servidor(
        servidor(linha(versao="v2", valor=-30.0)), 1)
    banco.marcar_conflito(1, no_servidor)
    assert banco.envios() == [] and banco.qtd_pendentes() == 1
    conflito = banco.conflitos()[0]
    assert sincro.diferencas(conflito["local"], conflito["servidor"]) == [
        ("Valor", "-20,00", "-30,00")]
    banco.manter_versao_local(conflito)
    assert banco.envios()[0]["base_updated_at"] == "v2"


def test_conflito_pode_usar_a_versao_do_servidor(banco):
    banco.salvar_lancamento(dict(DADOS, valor=-20.0), [], 1)
    dados = servidor(linha(versao="v2", valor=-30.0))
    banco.marcar_conflito(1, sincro.versao_do_servidor(dados, 1))
    banco.usar_versao_do_servidor(banco.conflitos()[0])
    banco.substituir_tudo(dados, "t2")
    assert banco.lancamentos()[0]["valor"] == -30.0
    assert banco.qtd_pendentes() == 0


def test_alterado_aqui_e_apagado_la_volta_como_novo(banco):
    banco.salvar_lancamento(DADOS, [ITEM], 1)
    banco.marcar_conflito(1, None)
    banco.manter_versao_local(banco.conflitos()[0])
    envio = banco.envios()[0]
    assert envio["lancamento_id"] < 0 and envio["client_uuid"]
    assert envio["base_updated_at"] is None and envio["itens"] == [ITEM]


def test_mesmo_conteudo_ignora_ids():
    a = dict(DADOS, id=-1, itens=[dict(ITEM, id=-5, ordem=0)], pagamentos=[])
    b = dict(DADOS, id=7, updated_at="x", itens=[dict(ITEM, id=3, ordem=0)],
             pagamentos=[])
    assert sincro.assinatura(a) == sincro.assinatura(b)
    assert sincro.assinatura(a) != sincro.assinatura(dict(b, valor=-11.0))


@pytest.mark.parametrize("texto,valor", [
    ("1.234,50", 1234.5), ("12,5", 12.5), ("12.50", 12.5), ("1.234", 1234.0),
    ("R$ 10", 10.0), ("-3,20", -3.2), ("+7", 7.0), ("1.234.567,89", 1234567.89)])
def test_ler_valor(texto, valor):
    assert regras.ler_valor(texto) == valor


@pytest.mark.parametrize("texto", ["", "abc", "nan", "inf", "1,2,3"])
def test_ler_valor_invalido(texto):
    with pytest.raises(ValueError):
        regras.ler_valor(texto)


def _nuvem(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    n = nuvem.Nuvem()
    n._guardar_sessao({"access_token": "a", "refresh_token": "r",
                       "expires_in": 3600, "user": {"id": "u1", "email": "e"}})
    return n


def test_envio_de_criacao_chama_a_funcao_sem_id(tmp_path, monkeypatch, banco):
    n = _nuvem(tmp_path, monkeypatch)
    banco.salvar_lancamento(DADOS, [ITEM])
    visto = {}

    def pedir(url, corpo=None, cabecalhos=None):
        visto.update(url=url, corpo=corpo)
        return {"status": "ok", "id": 5, "updated_at": "v"}

    assert n.enviar_lancamento(banco.envios()[0], "t", pedir)["id"] == 5
    assert visto["url"].endswith("/rpc/financeiro_salvar_lancamento")
    assert visto["corpo"]["p_id"] is None and visto["corpo"]["p_client_uuid"]
    assert "id" not in visto["corpo"]["p_dados"]
    assert visto["corpo"]["p_itens"] == [ITEM]


def test_exclusao_usa_a_versao_e_detecta_nada_apagado(tmp_path, monkeypatch, banco):
    n = _nuvem(tmp_path, monkeypatch)
    banco.excluir_lancamento(1)
    envio = banco.envios()[0]
    urls = []

    def pedir(url, cabecalhos=None, metodo=None):
        urls.append((url, metodo))
        return []

    assert n.enviar_lancamento(envio, "t", pedir) == {"status": "conflito"}
    assert urls[0][1] == "DELETE" and "updated_at=eq.v1" in urls[0][0]


def test_servidor_recusando_nao_impede_o_download(tmp_path, monkeypatch, banco):
    n = _nuvem(tmp_path, monkeypatch)
    banco.salvar_lancamento(DADOS, [])

    def falhar(envio, token):
        raise nuvem.ErroServidor("404")

    monkeypatch.setattr(n, "conferir_acesso", lambda t: None)
    monkeypatch.setattr(n, "enviar_lancamento", falhar)
    monkeypatch.setattr(n, "baixar_tudo", lambda: "baixado")
    resultados = []
    assert n.sincronizar([], [], banco.envios(), resultados) == "baixado"
    assert resultados[0][1] == {"status": "erro"}
