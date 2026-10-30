"""Listas, contas, vínculos, documentos e planilha: efeito local e envio."""
import base64
import datetime

import openpyxl
import pytest

from financeiro import documentos, nuvem, planilha, regras
from financeiro.banco import Banco
from financeiro.nuvem import converter


def servidor(lancamentos=(), itens=(), pagamentos=(), contas=(), documentos=(),
             opcoes=(), correcoes_ia=()):
    return converter(dict(
        lancamentos=list(lancamentos), itens=list(itens), pagamentos=list(pagamentos),
        contas=list(contas), documentos=list(documentos), opcoes=list(opcoes),
        correcoes_ia=list(correcoes_ia)))


def lanc(i=1, **campos):
    return dict(dict(id=i, valor=-10.0, pago=False, data_origem="2026-10-01",
                     data_vencimento=None, updated_at="v1"), **campos)


@pytest.fixture
def banco(tmp_path):
    b = Banco(str(tmp_path / "o.db"))
    yield b
    b.fechar()


def test_opcao_nova_vale_ja_e_sobrevive_ao_download(banco):
    banco.registrar_operacao("opcao_add", campo="banco", valor="Nubank")
    assert banco.opcoes("banco") == ["Nubank"]
    banco.substituir_tudo(servidor(opcoes=[dict(id=1, campo="banco", valor="Itaú")]), "t")
    assert banco.opcoes("banco") == ["Itaú", "Nubank"]
    banco.concluir_operacoes([op["id"] for op in banco.operacoes()])
    banco.substituir_tudo(servidor(), "t")
    assert banco.opcoes("banco") == []


def test_renomear_troca_em_todos_os_lugares(banco):
    banco.substituir_tudo(servidor(
        lancamentos=[lanc(banco="Velho")],
        pagamentos=[dict(id=1, lancamento_id=1, ordem=0, banco="Velho", valor=1.0)],
        contas=[dict(id="c", nome_banco="Velho", tipo="conta_corrente")],
        opcoes=[dict(id=1, campo="banco", valor="Velho")]), "t")
    banco.registrar_operacao("opcao_renomear", campo="banco", antigo="Velho", novo="Novo")
    l = banco.lancamentos()[0]
    assert l["banco"] == "Novo" and l["pagamentos"][0]["banco"] == "Novo"
    assert banco.opcoes("banco") == ["Novo"]
    assert banco.contas()[0]["nome_banco"] == "Novo"


def test_excluir_banco_remove_a_configuracao_da_conta(banco):
    banco.substituir_tudo(servidor(
        contas=[dict(id="c", nome_banco="X", tipo="conta_corrente")],
        opcoes=[dict(id=1, campo="banco", valor="X")]), "t")
    banco.registrar_operacao("opcao_del", campo="banco", valor="X")
    assert banco.opcoes("banco") == [] and banco.contas() == []


def test_configurar_conta_substitui_a_anterior(banco):
    banco.substituir_tudo(servidor(
        contas=[dict(id="c", nome_banco="X", tipo="conta_corrente", saldo_inicial=5.0)]),
        "t")
    banco.registrar_operacao("conta_salvar", nome="X", tipo="cartao_credito",
                             saldo_inicial=None, limite_total=100.0)
    conta = banco.contas()[0]
    assert (conta["tipo"], conta["limite_total"]) == ("cartao_credito", 100.0)
    # O download não desfaz enquanto não subiu
    banco.substituir_tudo(servidor(
        contas=[dict(id="c", nome_banco="X", tipo="conta_corrente", saldo_inicial=5.0)]),
        "t2")
    assert banco.contas()[0]["tipo"] == "cartao_credito"


def test_fatura_vinculada_troca_os_ids_temporarios_na_fila(banco):
    comum = dict(data_origem="2026-10-01", data_vencimento=None, valor=-50.0,
                 pago=False, categoria="Cartão de crédito", banco="Conta")
    a = banco.salvar_lancamento(comum, [], None, [])
    b = banco.salvar_parcelas([regras.lancamento_espelho_fatura(comum, "Azul")])[0]
    banco.registrar_operacao("vincular", ids=[a, b])
    l = {x["id"]: x for x in banco.lancamentos()}
    assert l[a]["lancamento_vinculado_id"] == b and l[b]["valor"] == 50.0
    assert l[b]["descricao"] == "Pagamento de fatura — via Conta"
    envio = banco.envios()[0]
    banco.confirmar_lancamento(envio, 100, "v")
    assert banco.operacoes()[0]["dados"]["ids"] == [100, b]


def test_documento_guardado_aparece_ja_e_sobrevive_ao_download(banco):
    banco.registrar_operacao("doc_guardar", doc_id="d1", nome="a.pdf", mime=None,
                             tamanho=3, caminho="u/1-a.pdf", arquivo_local="x",
                             criado_em="2026-10-30T00:00:00+00:00")
    assert banco.documentos()[0]["nome_arquivo"] == "a.pdf"
    banco.substituir_tudo(servidor(), "t")
    assert [d["id"] for d in banco.documentos()] == ["d1"]
    banco.registrar_operacao("doc_excluir", doc_id="d1", caminho="u/1-a.pdf")
    assert banco.documentos() == []


def test_mover_documentos_para_o_lancamento_consolidado(banco):
    banco.substituir_tudo(servidor(
        lancamentos=[lanc(1), lanc(2)],
        documentos=[dict(id="d", lancamento_id=2, nome_arquivo="n", caminho="c")]), "t")
    banco.registrar_operacao("docs_mover", ids=[1, 2])
    banco.excluir_lancamento(2)
    assert banco.documentos()[0]["lancamento_id"] == 1


def test_correcao_nao_repete(banco):
    c = dict(campo="banco", valor_errado="A", valor_correto="B", contexto=None)
    banco.registrar_operacao("correcao_add", **c)
    banco.registrar_operacao("correcao_add", **c)
    assert len(banco.correcoes()) == 1


def test_sair_apaga_a_fila(banco):
    banco.registrar_operacao("opcao_add", campo="banco", valor="X")
    assert banco.qtd_pendentes() == 1
    banco.apagar_tudo()
    assert banco.qtd_pendentes() == 0


# ---- envio ----
@pytest.fixture
def conectado(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    n = nuvem.Nuvem()
    n._guardar_sessao({"access_token": "a", "refresh_token": "r",
                       "expires_in": 3600, "user": {"id": "u1", "email": "e"}})
    pedidos = []

    def pedir(url, corpo=None, cabecalhos=None, metodo=None, abrir=None):
        pedidos.append((metodo, url, corpo))
        return []

    monkeypatch.setattr(nuvem, "_pedir", pedir)
    return n, pedidos


def test_ordem_do_envio_cria_opera_e_so_depois_exclui(conectado, monkeypatch):
    n, _ = conectado
    ordem = []
    monkeypatch.setattr(n, "conferir_acesso", lambda t: None)
    monkeypatch.setattr(n, "baixar_tudo", lambda: "ok")
    monkeypatch.setattr(n, "enviar_lancamento", lambda e, t: (
        ordem.append(e["operacao"]),
        {"status": "ok"} if e["operacao"] == "excluir"
        else {"status": "ok", "id": 9, "updated_at": "v"})[1])
    monkeypatch.setattr(n, "enviar_operacao", lambda op, t, m: ordem.append(
        ("op", m)))
    envios = [dict(lancamento_id=2, operacao="excluir"),
              dict(lancamento_id=-1, operacao="salvar")]
    feitas = []
    n.sincronizar([], [], envios, [], [dict(tipo="docs_mover", dados={})], feitas)
    assert ordem == ["salvar", ("op", {-1: 9}), "excluir"] and len(feitas) == 1


def test_operacao_recusada_para_a_fila_e_nao_perde_nada(conectado, monkeypatch):
    n, _ = conectado
    monkeypatch.setattr(n, "conferir_acesso", lambda t: None)
    monkeypatch.setattr(n, "baixar_tudo", lambda: "ok")

    def recusar(op, t, m):
        raise nuvem.ErroServidor("403")

    monkeypatch.setattr(n, "enviar_operacao", recusar)
    resultados, feitas = [], []
    n.sincronizar([], [], [], resultados, [dict(tipo="a", dados={}),
                                           dict(tipo="b", dados={})], feitas)
    assert feitas == [] and len(resultados) == 1


def test_reenvio_de_arquivo_que_ja_chegou_nao_e_erro(monkeypatch):
    def abrir(*a, **k):
        import io
        raise nuvem.urllib.error.HTTPError(
            "u", 400, "", {}, io.BytesIO(b'{"error":"Duplicate"}'))
    monkeypatch.setattr(nuvem.urllib.request, "urlopen", abrir)
    url = "http://exemplo/arquivo"
    assert nuvem._pedir_bytes(url, "t", "POST", b"x", ignorar_duplicado=True) == b""
    with pytest.raises(nuvem.ErroServidor):
        nuvem._pedir_bytes(url, "t", "POST", b"x")


def test_vincular_usa_os_ids_novos(conectado):
    n, pedidos = conectado
    n.enviar_operacao(dict(tipo="vincular", dados=dict(ids=[-1, -2])), "t",
                      {-1: 5, -2: 6})
    assert [p[2] for p in pedidos] == [{"lancamento_vinculado_id": 6},
                                       {"lancamento_vinculado_id": 5}]
    assert "id=eq.5" in pedidos[0][1] and "user_id=eq.u1" in pedidos[0][1]


def test_vincular_espera_o_lancamento_subir(conectado):
    n, _ = conectado
    with pytest.raises(nuvem.ErroServidor):
        n.enviar_operacao(dict(tipo="vincular", dados=dict(ids=[-1, 6])), "t", {})


def test_renomear_categoria_alcanca_os_itens(conectado):
    n, pedidos = conectado
    n.enviar_operacao(dict(tipo="opcao_renomear", dados=dict(
        campo="categoria", antigo="A", novo="B")), "t", {})
    urls = " ".join(p[1] for p in pedidos)
    assert "financeiro_opcoes_listas" in urls and "financeiro_lancamentos" in urls
    assert "financeiro_itens_lancamento" in urls
    assert "financeiro_contas_config" not in urls


def test_caminho_do_documento_fica_na_pasta_do_usuario(conectado):
    n, _ = conectado
    caminho = n.caminho_novo_documento("nota fiscal é.pdf")
    assert caminho.startswith("u1/") and caminho.endswith("-nota_fiscal__.pdf")


# ---- regras de texto da IA ----
@pytest.mark.parametrize("sugerido,esperado", [
    ("FABIO SCHEFFER MORAES", "Fábio Scheffer Moraes"),
    ("  fábio   scheffer moraes ", "Fábio Scheffer Moraes"),
    ("Outra Pessoa", None), ("", None), (None, None)])
def test_opcao_equivalente(sugerido, esperado):
    assert regras.opcao_equivalente("credor", sugerido,
                                    ["Fábio Scheffer Moraes"]) == esperado


def test_banco_next_aceita_o_nome_do_parceiro():
    assert regras.opcao_equivalente("banco", "Next 237 Bradesco S.A.",
                                    ["Itaú", "Next"]) == "Next"
    assert regras.opcao_equivalente("forma", "Next", ["Pix"]) is None


def test_correcoes_aprendidas():
    ia = dict(banco="Itau", forma_pagamento="Pix", categoria_sugerida="Mercado",
              credor=None)
    dados = dict(banco="Itaú", forma="PIX", categoria="Alimentação",
                 estabelecimento="Loja")
    saida = regras.correcoes_aprendidas(ia, dados, False)
    assert [(c["campo"], c["valor_errado"], c["valor_correto"], c["contexto"])
            for c in saida] == [("banco", "Itau", "Itaú", "Loja"),
                                ("categoria", "Mercado", "Alimentação", "Loja")]
    assert all(c["campo"] != "categoria"
               for c in regras.correcoes_aprendidas(ia, dados, True))


def test_categoria_de_fatura():
    assert regras.categoria_e_fatura(" Cartão de Crédito ")
    assert not regras.categoria_e_fatura("Cartão")


# ---- documentos ----
def test_preparar_para_ia(tmp_path):
    pdf = tmp_path / "a.pdf"
    pdf.write_bytes(b"abc")
    assert documentos.preparar_para_ia(str(pdf)) == (
        "pdf", base64.b64encode(b"abc").decode(), "application/pdf")
    imagem = tmp_path / "a.png"
    imagem.write_bytes(b"x")
    assert documentos.preparar_para_ia(str(imagem))[0] == "imagem"
    texto = tmp_path / "a.txt"
    texto.write_text("olá", encoding="utf-8")
    assert documentos.preparar_para_ia(str(texto))[:2] == ("texto", "olá")
    xml = tmp_path / "a.xml"
    xml.write_text("<a/>", encoding="utf-8")
    assert documentos.preparar_para_ia(str(xml))[0] == "xml"
    with pytest.raises(ValueError):
        documentos.preparar_para_ia(str(tmp_path / "a.exe"))


def test_preparar_planilha_vira_texto(tmp_path):
    caminho = tmp_path / "a.xlsx"
    livro = openpyxl.Workbook()
    livro.active.append(("Item", "Valor"))
    livro.active.append(("Pão", 5))
    livro.save(caminho)
    tipo, conteudo, _ = documentos.preparar_para_ia(str(caminho))
    assert tipo == "texto" and "Pão,5" in conteudo


# ---- planilha ----
def completo(i, **campos):
    return dict(dict(
        id=i, data_origem="2026-10-01", data_vencimento="2026-11-10", valor=-30.0,
        banco="Itaú", estabelecimento="Loja", forma="PIX", parcela="", descricao="Pão",
        categoria="Comida", credor="", devedor="", pago=True, itens=[], pagamentos=[]),
        **campos)


def test_planilha_ida_e_volta_nao_cria_duplicata(tmp_path):
    caminho = str(tmp_path / "p.xlsx")
    item = dict(valor=10.0, categoria="A", descricao="um", data_original="2026-09-01")
    pag = dict(banco="B", forma="Pix", valor=30.0)
    lista = [completo(1), completo(2, itens=[item, dict(item, valor=20.0)],
                                   descricao="", categoria="", banco="", forma="",
                                   pagamentos=[pag])]
    planilha.exportar(lista, caminho)
    lidos = planilha.ler(caminho)
    assert len(lidos) == 2
    assert lidos[1]["itens"][0]["data_original"] == "2026-09-01"
    assert lidos[1]["pagamentos"][0]["banco"] == "B"
    assert lidos[0]["parcela"] is None and lidos[0]["pago"] is True
    novos, repetidos = planilha.novos_e_repetidos(lidos, lista)
    assert novos == [] and repetidos == 2


def test_planilha_tem_as_colunas_do_site(tmp_path):
    caminho = str(tmp_path / "p.xlsx")
    planilha.exportar([completo(1)], caminho)
    livro = openpyxl.load_workbook(caminho)
    assert livro.sheetnames == ["Lançamentos", "Resumo"]
    assert livro.worksheets[0][1][12].value == "Tipo"
    assert livro.worksheets[0][2][6].value == "À vista"
    assert livro.worksheets[1][3][1].value == "-30.00"


def test_planilha_estranha_e_recusada(tmp_path):
    caminho = str(tmp_path / "e.xlsx")
    livro = openpyxl.Workbook()
    livro.active.append(("a", "b"))
    livro.save(caminho)
    with pytest.raises(ValueError):
        planilha.ler(caminho)


def test_datas_da_planilha(tmp_path):
    assert planilha._ler_data("25/10/2026") == "2026-10-25"
    assert planilha._ler_data("2026-10-25") == "2026-10-25"
    assert planilha._ler_data(datetime.datetime(2026, 10, 25)) == "2026-10-25"
    assert planilha._ler_data(46320) == "2026-10-25"
    assert planilha._ler_data("") is None


def test_duplicatas_mantem_o_mais_antigo_e_ignora_o_que_nao_subiu():
    lista = [completo(5), completo(3), completo(-1), completo(7, valor=-1.0)]
    assert planilha.ids_duplicados(lista) == [5]


def test_assinatura_ignora_maiusculas_e_espacos():
    a = completo(1, banco=" ITAÚ ")
    b = completo(2, banco="itaú")
    assert planilha.assinatura(a) == planilha.assinatura(b)
    assert planilha.assinatura(a) != planilha.assinatura(completo(3, valor=-31.0))


def test_opcoes_coletadas_da_planilha():
    l = completo(1, itens=[dict(valor=1.0, categoria="X", descricao="", data_original=None)],
                 pagamentos=[dict(banco="B2", forma="F2", valor=1.0)])
    saida = planilha.opcoes_de([l])
    assert saida["categoria"] == {"Comida", "X"} and saida["banco"] == {"Itaú", "B2"}
