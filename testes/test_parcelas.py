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
