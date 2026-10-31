from financeiro import regras
from financeiro.banco import Banco
from financeiro.nuvem import converter


def lanc(id, valor, **campos):
    base = dict(id=id, valor=valor, data_origem="2026-10-01",
                data_vencimento="2026-10-10", banco="", estabelecimento="",
                forma="", parcela="", descricao="", categoria="", credor="",
                devedor="", pago=False, lancamento_vinculado_id=None,
                updated_at=None, itens=[], pagamentos=[], documentos=[])
    base.update(campos)
    return base


def test_grafias_da_mesma_forma_dao_o_mesmo_icone():
    assert regras.classificar_forma("Cartão Crédito") == regras.CARTAO_CREDITO
    assert regras.classificar_forma("Cartão de Crédito") == regras.CARTAO_CREDITO
    assert regras.classificar_forma("Cartão Débito") == regras.CARTAO_DEBITO
    assert regras.classificar_forma("Cartão de Débito") == regras.CARTAO_DEBITO
    assert regras.classificar_forma("TED/DOC") == regras.TRANSFERENCIA
    assert regras.classificar_forma("Crédito Direto") == regras.OUTRA_FORMA
    assert regras.classificar_forma("Débito Automático") == regras.OUTRA_FORMA
    assert regras.classificar_forma(None) == regras.SEM_FORMA


def _com_itens(total, *valores):
    return lanc(1, total, itens=[dict(valor=v) for v in valores])


def test_itens_normais_ficam_positivos_e_desconto_negativo():
    # Gravados como manda o site
    assert regras.itens_orientados(_com_itens(-80.0, 100.0, -20.0)) == [100.0, -20.0]
    # Gravados negativos em lançamentos antigos
    assert regras.itens_orientados(_com_itens(-30.0, -10.0, -20.0)) == [10.0, 20.0]
    # Antigo com desconto gravado ao contrário
    assert regras.itens_orientados(_com_itens(-80.0, -100.0, 20.0)) == [100.0, -20.0]
    # Entrada
    assert regras.itens_orientados(_com_itens(30.0, 10.0, 20.0)) == [10.0, 20.0]
    assert regras.itens_orientados(lanc(1, -5.0)) == []


def test_orientar_nao_muda_o_total():
    l = _com_itens(-30.0, -10.0, -20.0)
    assert regras.valor_com_tipo(sum(regras.itens_orientados(l)), False) == -30.0


def test_formatacao_brasileira():
    assert regras.fmt_valor(1234.5) == "1.234,50"
    assert regras.fmt_valor(-0.5) == "-0,50"
    assert regras.fmt_valor_com_sinal(10) == "+10,00"
    assert regras.fmt_valor_com_sinal(-10) == "-10,00"
    assert regras.fmt_data("2026-10-25") == "25/10/2026"
    assert regras.fmt_data(None) == "—"


def test_filtro_de_banco_alcanca_pagamento_dividido():
    a = lanc(1, -10, banco="Nubank")
    b = lanc(2, -20, pagamentos=[dict(banco="Nubank", forma="PIX", valor=20.0)])
    c = lanc(3, -30, banco="Itaú")
    assert {l["id"] for l in regras.filtrar(
        [a, b, c], regras.Filtros(banco="Nubank"))} == {1, 2}


def test_vencimento_vazio_passa_no_filtro_como_no_site():
    a = lanc(1, -10, data_vencimento=None)
    assert regras.filtrar([a], regras.Filtros(venc_de="2026-11-01")) == [a]


def test_busca_ignora_acentos_e_olha_os_itens():
    a = lanc(1, -10, itens=[dict(descricao="Pão", categoria="Padaria",
                                 valor=-10.0)])
    assert regras.filtrar([a], regras.Filtros(busca="PAO")) == [a]
    assert regras.filtrar([a], regras.Filtros(busca="carne")) == []


def test_ordem_mais_recente_primeiro():
    a = lanc(1, -1, data_origem="2026-01-01")
    b = lanc(2, -1, data_origem="2026-02-01")
    assert [l["id"] for l in regras.filtrar([a, b], regras.Filtros())] == [2, 1]


def test_ordem_antigos_primeiro_mantem_o_dia_em_ordem_de_criacao():
    a = lanc(5, -1, data_origem="2026-02-01")
    b = lanc(6, -1, data_origem="2026-02-01")
    c = lanc(1, -1, data_origem="2026-01-01")
    assert [l["id"] for l in regras.filtrar(
        [b, a, c], regras.Filtros(ordem="antigos"))] == [1, 5, 6]
    assert [l["id"] for l in regras.filtrar([b, a, c], regras.Filtros())] == [5, 6, 1]


def test_filtro_de_tipo():
    saida, entrada = lanc(1, -5.0), lanc(2, 5.0)
    assert regras.filtrar([saida, entrada], regras.Filtros(tipo="saida")) == [saida]
    assert regras.filtrar([saida, entrada], regras.Filtros(tipo="entrada")) == [entrada]
    assert len(regras.filtrar([saida, entrada], regras.Filtros())) == 2


def test_so_a_janela_conta_como_filtro_escondido():
    assert not regras.tem_filtro_da_janela(
        regras.Filtros(ordem="antigos", tipo="saida", status="pago"))
    assert regras.tem_filtro_da_janela(regras.Filtros(busca="pão"))
    assert regras.tem_filtro_da_janela(regras.Filtros(banco="Itaú"))
    assert not regras.tem_filtro_da_janela(regras.Filtros(busca="   "))


def test_resumo():
    r = regras.resumir([lanc(1, 100.0, pago=True), lanc(2, -30.0)])
    assert (r.entradas, r.saidas, r.total, r.pago, r.pendente) == (
        100.0, -30.0, 70.0, 100.0, -30.0)


def test_conta_corrente_so_conta_o_que_esta_pago():
    conta = dict(nome_banco="Itaú", tipo="conta_corrente", saldo_inicial=100.0,
                 limite_total=None)
    s = regras.situacao_contas([conta], [
        lanc(1, -30.0, banco="Itaú", pago=True),
        lanc(2, -500.0, banco="Itaú")])[0]
    assert s.saldo == 70.0 and not s.alerta


def test_cartao_conta_pendente_e_avisa_estouro():
    cartao = dict(nome_banco="Nu", tipo="cartao_credito", saldo_inicial=None,
                  limite_total=100.0)
    s = regras.situacao_contas([cartao], [lanc(1, -150.0, banco="Nu")])[0]
    assert s.limite_usado == 150.0 and s.limite_restante == -50.0 and s.alerta


def test_banco_das_listas_sem_configuracao_aparece_como_no_site():
    conta = dict(nome_banco="Itaú", tipo="conta_corrente", saldo_inicial=10.0,
                 limite_total=None)
    nomes = regras.situacao_contas([conta], [], ["Itaú", "Nubank"])
    assert [(s.nome, s.tipo, s.detalhe) for s in nomes] == [
        ("Itaú", "conta_corrente", "Saldo atual: 10,00"), ("Nubank", "", "")]


def test_pagamento_dividido_usa_o_sinal_do_lancamento():
    l = lanc(1, -100.0, pagamentos=[dict(banco="A", forma="", valor=60.0),
                                    dict(banco="B", forma="", valor=40.0)])
    assert regras.valor_para_banco(l, "A") == -60.0
    assert regras.valor_para_banco(l, "C") == 0


def _banco_com_um(tmp_path):
    banco = Banco(str(tmp_path / "p.db"))
    banco.substituir_tudo(converter(dict(
        lancamentos=[dict(id=1, valor=-5, pago=False, updated_at="v1")],
        itens=[], pagamentos=[], contas=[], documentos=[], opcoes=[])), "t")
    return banco


def test_marcacao_sobrevive_ao_download(tmp_path):
    banco = _banco_com_um(tmp_path)
    banco.marcar_pago(1, True)
    banco.substituir_tudo(converter(dict(
        lancamentos=[dict(id=1, valor=-5, pago=False, updated_at="v1")],
        itens=[], pagamentos=[], contas=[], documentos=[], opcoes=[])), "t2")
    assert banco.lancamentos()[0]["pago"] is True
    assert banco.pendencias()[0]["base_updated_at"] == "v1"
    banco.fechar()


def test_segunda_marcacao_mantem_a_versao_base(tmp_path):
    banco = _banco_com_um(tmp_path)
    banco.marcar_pago(1, True)
    banco.marcar_pago(1, False)
    p = banco.pendencias()[0]
    assert (p["pago"], p["base_updated_at"]) == (0, "v1") and p["editado_em"]
    banco.fechar()


def test_confirmacao_tira_da_fila(tmp_path):
    banco = _banco_com_um(tmp_path)
    banco.marcar_pago(1, True)
    banco.confirmar_envio(1, True, "v2")
    assert banco.pendencias() == []
    banco.fechar()


def test_mudou_de_ideia_durante_o_envio_continua_na_fila(tmp_path):
    banco = _banco_com_um(tmp_path)
    banco.marcar_pago(1, True)
    banco.marcar_pago(1, False)
    banco.confirmar_envio(1, True, "v2")
    p = banco.pendencias()[0]
    assert (p["pago"], p["base_updated_at"]) == (0, "v2")
    banco.fechar()


def test_sair_da_conta_apaga_a_fila(tmp_path):
    banco = _banco_com_um(tmp_path)
    banco.marcar_pago(1, True)
    banco.apagar_tudo()
    assert banco.pendencias() == []
    banco.fechar()


def test_banco_guarda_e_troca_tudo(tmp_path):
    banco = Banco(str(tmp_path / "t.db"))
    dados = converter(dict(
        lancamentos=[dict(id=1, valor="-12.34", pago=True, banco=None,
                          descricao="Mercado", data_origem="2026-10-01")],
        itens=[dict(id=1, lancamento_id=1, ordem=0, valor="-12.34",
                    descricao="Arroz", categoria="Comida")],
        pagamentos=[], contas=[], documentos=[],
        opcoes=[dict(id=1, campo="banco", valor="Itaú")]))
    banco.substituir_tudo(dados, "agora")
    l = banco.lancamentos()[0]
    assert l["valor"] == -12.34 and l["pago"] is True and l["banco"] == ""
    assert l["itens"][0]["descricao"] == "Arroz"
    assert banco.opcoes("banco") == ["Itaú"]
    assert banco.ultima_sincronizacao() == "agora"
    banco.substituir_tudo(converter(dict(
        lancamentos=[], itens=[], pagamentos=[], contas=[], documentos=[],
        opcoes=[])), "depois")
    assert banco.lancamentos() == []
    banco.apagar_tudo()
    assert banco.ultima_sincronizacao() == ""
    banco.fechar()
