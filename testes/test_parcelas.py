"""Parcelas: mesmas contas do site, e cada uma é um lançamento."""
import pytest

from financeiro import regras
from financeiro.banco import Banco


@pytest.mark.parametrize("inicio,meses,esperado", [
    ("2026-01-15", 0, "2026-01-15"),
    ("2026-01-15", 1, "2026-02-15"),
    ("2026-01-31", 1, "2026-02-28"),    # mantém o último dia do mês
    ("2028-01-31", 1, "2028-02-29"),    # ano bissexto
    ("2026-12-10", 1, "2027-01-10"),
    ("2026-11-30", 14, "2028-01-30"),
    ("2026-01-31", 2, "2026-03-31"),    # o dia original volta, não o de fevereiro
])
def test_avancar_meses(inicio, meses, esperado):
    assert regras.avancar_meses(inicio, meses) == esperado


def test_centavos_vao_na_ultima_por_padrao():
    assert regras.calcular_parcelas(100.0, 3) == [33.33, 33.33, 33.34]


def test_centavos_podem_ir_na_primeira():
    assert regras.calcular_parcelas(100.0, 3, True) == [33.34, 33.33, 33.33]


@pytest.mark.parametrize("total,n", [(1234.56, 7), (0.05, 3), (99999.99, 360),
                                     (19.9, 12)])
def test_soma_sempre_bate_com_o_total(total, n):
    valores = regras.calcular_parcelas(total, n)
    assert len(valores) == n
    assert round(sum(round(v * 100) for v in valores)) == round(total * 100)


@pytest.mark.parametrize("valores,total,n", [
    ([100.0, 50.0, 0.01], 150.01, 3),
    ([33.33, 66.67], 100.0, 7),
    ([200.0, 132.45, -20.0], 312.45, 4),    # com desconto
    ([0.01, 0.02], 0.03, 3),
    ([19.9, 10.1, 5.05, 7.77, 1.11], 43.93, 12),
    ([1000.0], 1000.0, 360)])
@pytest.mark.parametrize("na_primeira", [False, True])
def test_rateio_fecha_nas_duas_direcoes(valores, total, n, na_primeira):
    parcelas = regras.calcular_parcelas(total, n, na_primeira)
    tabela = regras.ratear_itens(valores, parcelas)
    centavos = [[round(v * 100) for v in linha] for linha in tabela]
    for linha, parcela in zip(centavos, parcelas):
        assert sum(linha) == round(parcela * 100)
    for i, valor in enumerate(valores):
        assert sum(linha[i] for linha in centavos) == round(valor * 100)


def test_rateio_divide_por_igual_quando_da():
    tabela = regras.ratear_itens([30.0, 60.0], [30.0, 30.0, 30.0])
    assert tabela == [[10.0, 20.0]] * 3


def test_parcelas_gravam_e_enviam_os_itens(tmp_path):
    b = Banco(str(tmp_path / "i.db"))
    item = dict(categoria="Comida", descricao="Pão", data_original=None)
    b.salvar_parcelas([
        dict(data_origem="2026-10-01", pago=False, valor=-5.0, parcela=p,
             itens=[dict(item, valor=5.0)]) for p in ("1/2", "2/2")])
    lista = b.lancamentos()
    assert len(lista) == 2 and all(len(l["itens"]) == 1 for l in lista)
    assert regras.descricao_de(next(l for l in lista if l["parcela"] == "2/2")) == (
        "Pão — 2/2")
    assert b.envios()[0]["itens"][0]["valor"] == 5.0
    b.fechar()


def test_parcelas_gravam_pagamento_dividido(tmp_path):
    b = Banco(str(tmp_path / "d.db"))
    b.salvar_parcelas([dict(
        data_origem="2026-10-01", pago=False, valor=-5.0, parcela="1/2",
        pagamentos=[dict(banco="A", forma=None, valor=3.0),
                    dict(banco="B", forma="Pix", valor=2.0)])])
    assert [p["banco"] for p in b.lancamentos()[0]["pagamentos"]] == ["A", "B"]
    assert b.envios()[0]["pagamentos"][1]["valor"] == 2.0
    b.fechar()


def _parcela(i, rotulo, origem="2026-10-01"):
    return dict(id=i, parcela=rotulo, data_origem=origem, data_vencimento=None,
                pago=False, banco="", forma="", estabelecimento="", categoria="",
                credor="", devedor="", descricao=rotulo, valor=-1.0, itens=[],
                pagamentos=[])


def test_parcelas_do_mesmo_dia_ficam_em_ordem_crescente():
    # Já no servidor, com ids definitivos
    lista = [_parcela(12, "3/3"), _parcela(10, "1/3"), _parcela(11, "2/3")]
    assert [l["parcela"] for l in regras.filtrar(lista, regras.Filtros())] == [
        "1/3", "2/3", "3/3"]
    # Ainda na fila, com ids temporários, depois dos que já subiram
    lista = [_parcela(-3, "3/3"), _parcela(-1, "1/3"), _parcela(-2, "2/3"),
             _parcela(50, "antigo")]
    assert [l["parcela"] for l in regras.filtrar(lista, regras.Filtros())] == [
        "antigo", "1/3", "2/3", "3/3"]


def test_datas_mais_recentes_continuam_primeiro():
    lista = [_parcela(1, "velho", "2026-01-01"), _parcela(2, "novo", "2026-10-01")]
    assert [l["parcela"] for l in regras.filtrar(lista, regras.Filtros())] == [
        "novo", "velho"]


def test_parcelas_sobem_na_ordem_de_criacao(tmp_path):
    b = Banco(str(tmp_path / "o.db"))
    comum = dict(data_origem="2026-10-01", pago=False, valor=-1.0)
    b.salvar_parcelas([dict(comum, parcela=p) for p in ("1/3", "2/3", "3/3")])
    assert [e["dados"]["parcela"] for e in b.envios()] == ["1/3", "2/3", "3/3"]
    b.fechar()


def test_parcelas_viram_lancamentos_na_fila(tmp_path):
    b = Banco(str(tmp_path / "p.db"))
    comum = dict(data_origem="2026-10-01", banco="Itaú", pago=False)
    ids = b.salvar_parcelas([
        dict(comum, valor=-50.0, parcela="1/2", descricao="TV — 1/2",
             data_vencimento="2026-11-10"),
        dict(comum, valor=-50.0, parcela="2/2", descricao="TV — 2/2",
             data_vencimento="2026-12-10")])
    assert len(set(ids)) == 2 and all(i < 0 for i in ids)
    fila = b.envios()
    assert len(fila) == 2 and {e["dados"]["parcela"] for e in fila} == {"1/2", "2/2"}
    assert len({e["client_uuid"] for e in fila}) == 2
    assert all(e["itens"] == [] for e in fila)
    # Editar uma parcela não apaga a etiqueta
    b.salvar_lancamento(dict(comum, valor=-60.0, data_vencimento="2026-11-10"),
                        [], ids[0])
    editada = next(e for e in b.envios() if e["lancamento_id"] == ids[0])
    assert editada["dados"]["parcela"] == "1/2"
    b.fechar()
