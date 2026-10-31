"""Janelas de verdade, sempre ocultas: nenhuma é mostrada na tela."""
import pytest
import wx

from financeiro import __version__, atualizacao, dialogos, instalador
from financeiro.banco import Banco
from financeiro.janela import Janela
from financeiro.nuvem import converter


@pytest.fixture(scope="module")
def app():
    aplicativo = wx.App(False)
    yield aplicativo
    del aplicativo


def _banco(pasta):
    banco = Banco(str(pasta / "t.db"))
    banco.substituir_tudo(converter(dict(
        lancamentos=[dict(id=i, valor=-1.0 if i % 2 else 1.0, descricao="L%d" % i,
                          pago=False, data_origem="2026-10-%02d" % i)
                     for i in range(1, 11)],
        itens=[], pagamentos=[], contas=[], documentos=[], opcoes=[])), "agora")
    return banco


def test_reabrir_volta_a_tela_e_a_posicao(app, tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    banco = _banco(tmp_path)
    janela = Janela(banco, None)
    janela.cx_tipo.SetSelection(2)             # saídas
    janela.cx_situacao.SetSelection(1)         # pendentes
    janela._ao_filtro(None)
    alvo = janela.visiveis[3]["id"]
    janela._selecionar_id(alvo)
    janela.navegacao.coluna = 2
    janela.Close()
    wx.GetApp().ProcessPendingEvents()

    nova = Janela(banco, None)
    assert nova.filtros.tipo == "saida" and nova.filtros.status == "pendente"
    assert nova.cx_tipo.GetSelection() == 2 and nova.cx_situacao.GetSelection() == 1
    assert nova.lancamento_atual()["id"] == alvo
    nova.navegacao.definir_coluna(nova._coluna_guardada)
    assert nova.navegacao.coluna_atual() == 2
    nova.Destroy()
    banco.fechar()


def test_lancamento_que_sumiu_fica_na_mesma_linha(app, tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    banco = _banco(tmp_path)
    janela = Janela(banco, None)
    janela._selecionar_id(janela.visiveis[4]["id"])
    alvo = janela.lancamento_atual()["id"]
    janela.Close()
    wx.GetApp().ProcessPendingEvents()
    banco.excluir_lancamento(alvo)
    nova = Janela(banco, None)
    assert nova.navegacao.atual() == 4
    nova.Destroy()
    banco.fechar()


def test_coluna_guardada_alem_das_visiveis_nao_quebra(app, tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    from financeiro import preferencias
    preferencias.salvar_tela("", "", None, 999, 999)
    banco = _banco(tmp_path)
    janela = Janela(banco, None)
    janela.navegacao.definir_coluna(janela._coluna_guardada)
    assert janela.navegacao.atual() == 9
    assert janela.navegacao.coluna_atual() == janela.lista.GetColumnCount() - 1
    janela.Destroy()
    banco.fechar()


def test_botoes_do_instalador_e_credito(app, tmp_path):
    dlg = instalador.BoasVindas(str(tmp_path / "pacote.zip"))
    rotulos = [b.GetLabel().replace("&", "") for b in (
        dlg.instalar, dlg.portatil, dlg.guia, dlg.fechar)]
    assert rotulos == ["Instalar", "Cópia portátil", "Guia de uso", "Fechar"]
    assert dlg.fechar.GetId() == wx.ID_CANCEL      # Esc fecha
    assert "Fábio Scheffer Moraes" in instalador.TEXTO and __version__ in instalador.TEXTO
    dlg.Destroy()


@pytest.mark.parametrize("instalar", [True, False])
def test_rotulo_nasce_antes_da_pasta(app, instalar):
    dlg = instalador.DialogoPasta(None, instalar, "C:\\x")
    filhos = list(dlg.GetChildren())
    n = filhos.index(dlg.pasta)
    assert isinstance(filhos[n - 1], wx.StaticText)
    assert (dlg.abrir is not None) == instalar
    dlg.Destroy()


def test_janela_de_versao_nova(app):
    lanc = atualizacao.Lancamento("2027.1.1.0", historico=[("2027.1.1.0", "- novo")])
    dlg = dialogos.NovaVersao(None, lanc)
    assert "- novo" in dlg.texto.GetValue() and dlg.FindWindow(wx.ID_OK)
    dlg.Destroy()
    dlg = dialogos.NovaVersao(None, lanc, pode_atualizar=False)
    assert dlg.FindWindow(wx.ID_OK) is None
    dlg.Destroy()


def _esperar(barra):
    barra._thread.join(5)
    fechou = []
    barra.EndModal = fechou.append
    barra.acompanhar(None)
    return fechou


def test_progresso_entrega_o_resultado_e_o_erro(app):
    barra = dialogos.Progresso(None, "Teste")
    barra.iniciar(lambda p: (p(50, "Metade"), "pronto")[1])
    assert _esperar(barra) == [wx.ID_OK]
    assert barra.resultado == "pronto" and barra.barra.GetValue() == 50
    assert barra.texto.GetLabel() == "Metade"
    barra.Destroy()

    def falha(_):
        raise ValueError("deu errado")

    barra = dialogos.Progresso(None, "Teste", pode_cancelar=True)
    barra.iniciar(falha)
    _esperar(barra)
    assert barra.erro == "deu errado"
    barra._cancelar(None)
    assert barra.cancelado
    barra.Destroy()
