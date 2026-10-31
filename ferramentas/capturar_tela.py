"""Exercita as janelas com dados fictícios, sem mostrar nada na tela.

Uso: python ferramentas/capturar_tela.py saida.png [modo] [--mostrar]

Só com --mostrar a janela aparece e a imagem é salva. Ela surge e some na tela
de quem está usando o computador: só usar com o aviso e o aceito dessa pessoa.
"""
import ctypes
import datetime
import os
import sys
import tempfile
import threading
import traceback

import wx

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ["FINANCEIRO_DADOS"] = tempfile.mkdtemp()

from financeiro import (colunas, config, dialogos, formulario, gerenciar,  # noqa: E402
                        preferencias, regras)
from financeiro import nuvem as nuvem_mod  # noqa: E402
from financeiro.banco import Banco  # noqa: E402
from financeiro.janela import Janela  # noqa: E402
from financeiro.nuvem import converter  # noqa: E402

FORMAS = (("Mercado Central", -312.45, "Cartão Crédito", "Cartão Azul", True),
          ("Salário de outubro", 5400.0, "Transferência", "Banco Alfa", True),
          ("Conta de luz", -187.9, "Boleto", "Banco Alfa", False),
          ("Farmácia", -64.3, "PIX", "Banco Alfa", True),
          ("Feira", -48.0, "Dinheiro", "", True),
          ("Assinatura de música", -21.9, "Cartão de Crédito", "Cartão Azul", False),
          ("Padaria", -15.5, "Cartão Débito", "Banco Alfa", True),
          ("Devolução da loja", 120.0, "PIX", "Banco Alfa", False),
          ("Aluguel", -1450.0, "Débito Automático", "Banco Alfa", True),
          ("Lançamento antigo sem forma", -30.0, None, "Banco Beta", True))


def dados_ficticios():
    lancs = [dict(id=i + 1, valor=v, descricao=d, forma=f, banco=b, pago=p,
                  data_origem="2026-10-%02d" % (28 - i),
                  data_vencimento="2026-11-%02d" % (10 + i))
             for i, (d, v, f, b, p) in enumerate(FORMAS)]
    itens = [dict(id=1, lancamento_id=1, ordem=0, valor=-200.0,
                  descricao="Compras do mês", categoria="Alimentação",
                  data_original="2026-10-28"),
             dict(id=2, lancamento_id=1, ordem=1, valor=-132.45,
                  descricao="Limpeza", categoria="Casa",
                  data_original="2026-10-28"),
             dict(id=3, lancamento_id=1, ordem=2, valor=20.0,
                  descricao="Desconto", categoria="Alimentação",
                  data_original="2026-10-28")]
    return converter(dict(
        lancamentos=lancs, itens=itens, pagamentos=[], documentos=[],
        opcoes=[dict(id=1, campo="banco", valor="Banco Alfa"),
                dict(id=2, campo="banco", valor="Cartão Azul")],
        contas=[dict(id="a", nome_banco="Banco Alfa", tipo="conta_corrente",
                     saldo_inicial=1000.0, limite_total=None),
                dict(id="b", nome_banco="Cartão Azul", tipo="cartao_credito",
                     saldo_inicial=None, limite_total=300.0)]))


def salvar(janela, caminho):
    """PrintWindow captura mesmo com outra janela por cima."""
    w, h = janela.GetSize()
    bmp = wx.Bitmap(w, h)
    dc = wx.MemoryDC(bmp)
    ctypes.windll.user32.PrintWindow(
        ctypes.c_void_p(janela.GetHandle()), ctypes.c_void_p(dc.GetHandle()), 2)
    dc.SelectObject(wx.NullBitmap)
    bmp.SaveFile(caminho, wx.BITMAP_TYPE_PNG)


def exercitar_hora(janela, banco):
    """Conflito com o servidor nas ordens possíveis, sem rede."""
    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    janela._sincronizar = lambda manual: falas.append("<sincroniza de novo>")
    base = datetime.datetime.now(datetime.timezone.utc)

    def rodada(nome, minutos_servidor, defasagem, preparar):
        falas.clear()
        janela.todos = banco.lancamentos()
        dados = dados_ficticios()
        linha = next(l for l in dados["lancamentos"] if l["id"] == 3)
        linha["descricao"] = "Mudou no site"
        linha["updated_at"] = (base + datetime.timedelta(
            minutes=minutos_servidor)).isoformat()
        nuvem_mod._defasagem = defasagem
        preparar()
        envio = banco.envios()
        pagos = banco.pendencias()
        janela._sincronizado(
            dados, None, False, [(p, None) for p in pagos],
            [(e, {"status": "conflito"}) for e in envio])
        estado = next((l for l in banco.lancamentos() if l["id"] == 3), None)
        print(nome, "->", estado and estado["descricao"], "| fila:",
              banco.qtd_pendentes(), "| conflitos:", len(banco.conflitos()),
              "|", falas)
        for c in banco.conflitos():
            banco.usar_versao_do_servidor(c)
        for p in banco.pendencias():
            banco.descartar_pendencia(p["lancamento_id"])
        for e in banco.envios():
            banco.confirmar_exclusao(e)
        banco.substituir_tudo(dados_ficticios(), "t")

    editar = lambda: banco.salvar_lancamento(
        dict(data_origem="2026-10-26", valor=-187.9, descricao="Mudou aqui"),
        [], 3)
    rodada("servidor mais antigo", -30, 0, editar)
    rodada("servidor mais recente", 30 - 45, 0, lambda: (
        editar(), banco._con.execute(
            "UPDATE pendencias_lancamento SET editado_em = ?",
            ((base - datetime.timedelta(minutes=60)).isoformat(),)),
        banco._con.commit()))
    rodada("relógio errado", -30, 900, editar)
    rodada("excluiu aqui, mudou antes lá", -30, 0,
           lambda: banco.excluir_lancamento(3))
    rodada("pago aqui, mudou antes lá", -30, 0,
           lambda: banco.marcar_pago(3, True))
    rodada("pago aqui, mudou depois lá", 5, 0,
           lambda: banco.marcar_pago(3, True))


def exercitar_parcelar(janela, banco):
    avisos = []
    original = wx.MessageBox
    wx.MessageBox = lambda *a, **k: avisos.append(a[0])
    form = formulario.Formulario(janela, banco)
    form.valor.SetValue("100")
    form.descricao.SetValue("Televisão")
    form.campo_banco.SetValue("Cartão Azul")
    form.vencimento.SetValue("31/01/2027")
    dlg = formulario.DialogoParcelar(form, "100", False, "2027-01-31")
    print("sem quantidade:", dlg.resumo.GetValue(), dlg.parcelas)
    dlg.EndModal = lambda r: print("não devia fechar", r)
    dlg._criar(None)
    print("aviso:", avisos)
    dlg.quantidade.SetValue("3")
    print("resumo:", dlg.resumo.GetValue())
    for n in range(dlg.lista.GetItemCount()):
        print(" ", lista_texto(dlg.lista, n))
    dlg.diferenca.SetSelection(1)
    dlg.atualizar()
    print("na primeira:", dlg.resumo.GetValue())
    dlg.quantidade.SetValue("1")
    print("uma só:", dlg.resumo.GetValue())
    dlg.quantidade.SetValue("3")
    dlg.tipo.SetSelection(1)
    dlg.atualizar()
    print("entrada:", dlg.parcelas[0])
    dlg.tipo.SetSelection(0)
    dlg.atualizar()
    parcelas = form._montar_parcelas(dlg.parcelas, "2026-10-30")
    print("parcela 2:", parcelas[1])
    janela._sincronizar = lambda manual: print("sincroniza")
    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    antes = len(banco.lancamentos())
    janela._gravar_parcelas(parcelas)
    print("lançamentos:", antes, "->", len(banco.lancamentos()), falas,
          "fila:", banco.qtd_pendentes())
    print("na tabela:", lista_texto(janela.lista, janela.navegacao.atual()))
    form2 = formulario.Formulario(janela, banco)
    form2.itens += [dict(valor=100.0, categoria="Casa", descricao="Mesa",
                         data_original=None),
                    dict(valor=50.01, categoria="Casa", descricao="Cadeira",
                         data_original=None),
                    dict(valor=-10.0, categoria="", descricao="Desconto",
                         data_original=None)]
    form2._preencher_itens(0)
    dlg2 = formulario.DialogoParcelar(form2, form2.valor.GetValue(), False,
                                      "2027-01-15", len(form2.itens))
    dlg2.quantidade.SetValue("3")
    print("com itens:", dlg2.resumo.GetValue(), "editável:", dlg2.valor.IsEditable())
    parcelas2 = form2._montar_parcelas(dlg2.parcelas, "2026-10-30")
    for p in parcelas2:
        print(" ", p["parcela"], p["valor"], [(i["descricao"], i["valor"])
                                              for i in p["itens"]])
    janela._gravar_parcelas(parcelas2, "doc-x")
    print("tabela:", lista_texto(janela.lista, janela.navegacao.atual()))
    print("fila com itens:", [(e["dados"]["parcela"], len(e["itens"]))
                              for e in banco.envios() if e["itens"]])
    form2.dividir.SetValue(True)
    avisos.clear()
    form2._parcelar(None)
    print("dividido:", avisos)
    wx.MessageBox = original


def exercitar_completo(janela, banco):
    """Formulário e janelas de gerenciamento, sem abrir nada na tela."""
    avisos = []
    original = wx.MessageBox
    wx.MessageBox = lambda *a, **k: (avisos.append(a[0]), wx.YES)[1]
    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    janela._sincronizar = lambda manual: None
    janela.nuvem = None

    # Pagamento dividido
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    form.EndModal = lambda r: print("  fechou", r)
    form.valor.SetValue("100")
    form.dividir.SetValue(True)
    form._preencher_pagamentos(0)
    print("banco desligado ao dividir:", not form.campo_banco.IsEnabled())
    form._salvar(None)
    print("sem pagamentos:", avisos[-1][:40])
    form.pagamentos = [dict(banco="Banco Alfa", forma="PIX", valor=60.0),
                       dict(banco="Cartão Azul", forma=None, valor=30.0)]
    form._preencher_pagamentos(0)
    form._salvar(None)
    print("soma errada:", avisos[-1][:60])
    form.pagamentos[1]["valor"] = 40.0
    form._salvar(None)
    print("dividido:", form.resultado["banco"], form.resultado["valor"],
          [lista_texto(form.lista_pag, n) for n in range(form.lista_pag.GetItemCount())])
    janela._gravar_formulario(form, None)
    salvo = banco.lancamentos()
    print("gravou:", [l["pagamentos"] for l in salvo if l["pagamentos"]][0][0]["banco"])

    # Fatura vinculada
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    form.EndModal = lambda r: None
    print("cartão desligado sem categoria:", not form.cartao.IsEnabled())
    form.campo_banco.SetValue("Banco Alfa")
    form.categoria.SetValue("Cartão de crédito")
    print("cartão pré-escolhido:", form.cartao_escolhido())
    form.valor.SetValue("50")
    form._salvar(None)
    falas.clear()
    janela._gravar_formulario(form, None)
    print("fatura:", falas, [op["tipo"] for op in banco.operacoes()])

    # Importar lançamentos como itens
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    form.EndModal = lambda r: None
    dlg = formulario.ImportarLancamentos(form, janela.todos, set())
    print("pendentes para importar:", dlg.lista.GetItemCount())
    dlg.busca.SetValue("luz")
    print("busca luz:", [lista_texto(dlg.lista, n)[0]
                         for n in range(dlg.lista.GetItemCount())])
    dlg._alternar()
    print("selecionados:", dlg.total.GetValue())
    dlg.EndModal = lambda r: None
    dlg._importar(None)
    form.receber_importados(dlg.selecionados)
    dlg.Destroy()
    print("itens importados:", [(i["valor"], i["descricao"]) for i in form.itens],
          "ids:", form.ids_importados(), "valor:", form.valor.GetValue())
    form._salvar(None)
    janela._gravar_formulario(form, None)
    print("origem excluída:", 3 not in {l["id"] for l in banco.lancamentos()},
          "fila:", [e["operacao"] for e in banco.envios()])

    # IA, sem rede
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    form.aplicar_ia(dict(data="2026-10-20", tipo_transacao="credito", valor=33.5,
                         banco="BANCO ALFA", credor="Pessoa Nova", descricao="Nota",
                         categoria_sugerida="Xpto"))
    print("IA:", form.tipo.GetStringSelection(), form.valor.GetValue(),
          form.campo_banco.GetValue(), form.data_origem.GetValue(),
          form.descricao.GetValue(), "| credor:", form.credor.GetValue())
    print("mensagens da IA:", [a[:60] for a in avisos[-3:]])
    form2 = formulario.Formulario(janela, banco, todos=janela.todos)
    form2.aplicar_ia(dict(valor=30, itens=[dict(valor=10, descricao="a"),
                                           dict(valor=20, descricao="b")],
                          pagamentos=[dict(valor=10, banco="Banco Alfa"),
                                      dict(valor=20, banco="Cartão Azul")]))
    print("IA itens:", form2._soma_itens(), form2.dividir.GetValue(),
          form2.valor_documento)
    # Janelas de gerenciamento
    listas = gerenciar.Listas(janela, banco)
    print("listas:", listas.lista.GetItemCount(), listas.campo.GetStringSelection())
    listas.Destroy()
    contas = dialogos.Contas(janela, janela._situacoes(), janela._configurar_conta,
                             janela._remover_conta)
    print("contas:", [lista_texto(contas.lista, n)[:2]
                      for n in range(contas.lista.GetItemCount())])
    contas.Destroy()
    banco.registrar_operacao("doc_guardar", doc_id="d", nome="n.pdf", mime=None,
                             tamanho=2048, caminho="c", arquivo_local="x",
                             criado_em="2026-10-30T00:00:00+00:00")
    docs = gerenciar.Documentos(janela, banco, None, janela.todos)
    print("documentos:", lista_texto(docs.lista, 0))
    docs.Destroy()
    banco.registrar_operacao("correcao_add", campo="banco", valor_errado="a",
                             valor_correto="b", contexto=None)
    corr = gerenciar.Correcoes(janela, banco)
    print("correções:", lista_texto(corr.lista, 0))
    corr.Destroy()
    pend = gerenciar.Pendencias(janela, banco)
    print("pendências:", [lista_texto(pend.lista, n)[0]
                          for n in range(pend.lista.GetItemCount())][:9])
    pend.Destroy()
    # Download não desfaz o que está na fila
    antes = {o["tipo"] for o in banco.operacoes()}
    banco.substituir_tudo(dados_ficticios(), "t")
    print("depois do download:", len(banco.lancamentos()), "lançamentos,",
          "documento:", [d["nome_arquivo"] for d in banco.documentos()],
          "fila igual:", antes == {o["tipo"] for o in banco.operacoes()})
    print("erros:", [a for a in avisos if "erro" in a.lower()])
    wx.MessageBox = original


def exercitar_inicial(janela, banco):
    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    painel = janela.cx_ordem.GetParent()
    print("ordem do Tab:", [
        f.GetLabel() if isinstance(f, (wx.StaticText, wx.Button))
        else "%s: %s" % (type(f).__name__, f.GetStringSelection())
        if isinstance(f, wx.Choice) else type(f).__name__
        for f in painel.GetChildren()])

    def primeira():
        return lista_texto(janela.lista, 0)[1:2], janela.lista.GetItemCount()

    print("recentes:", primeira())
    janela.cx_ordem.SetSelection(1)
    janela._ao_mudar_ordem(None)
    print("antigos:", primeira(), "guardada:", preferencias.ler_ordem())
    janela.cx_tipo.SetSelection(1)
    janela._ao_filtro(None)
    print("entradas:", primeira(), falas[-1:], janela.resumo.GetLabel()[:40])
    janela.cx_tipo.SetSelection(2)
    janela.cx_situacao.SetSelection(1)
    janela._ao_filtro(None)
    print("saídas pendentes:", primeira())
    # Janela Filtrar
    dlg = dialogos.JanelaFiltrar(janela, banco, janela.filtros)
    print("foco inicial é a busca:", [type(f).__name__ for f in dlg.GetChildren()][:2])
    dlg.busca.SetValue("luz")
    dlg.EndModal = lambda r: None
    dlg._aplicar(None)
    dlg.Destroy()
    janela.aplicar_filtros()
    print("busca:", primeira(), janela.resumo.GetLabel()[:40])
    falas.clear()
    janela._limpar_filtros(None)
    print("limpou:", primeira(), falas, janela.resumo.GetLabel()[:30],
          "ordem mantida:", janela.filtros.ordem,
          "caixas:", janela.cx_tipo.GetStringSelection(),
          janela.cx_situacao.GetStringSelection())
    janela._redefinir_filtros(banco="Banco Alfa")
    print("conta:", primeira(), janela.resumo.GetLabel()[:30])


def exercitar_datas(janela, banco):
    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    wx.GetApp().SetTopWindow(janela)
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    campo = form.vencimento
    print("tipo:", type(campo).__name__, type(form.data_origem).__name__,
          "hoje:", form.data_origem.GetValue())

    def tecla(codigo):
        evt = wx.KeyEvent(wx.wxEVT_KEY_DOWN)
        evt.SetKeyCode(codigo)
        evt.SetEventObject(campo)
        campo.GetEventHandler().ProcessEvent(evt)

    for nome, codigo in (("cima, vazio", wx.WXK_UP), ("cima", wx.WXK_UP),
                         ("baixo", wx.WXK_DOWN), ("direita", wx.WXK_RIGHT),
                         ("cima no mês", wx.WXK_UP), ("direita", wx.WXK_RIGHT),
                         ("direita de novo", wx.WXK_RIGHT), ("cima no ano", wx.WXK_UP),
                         ("esquerda", wx.WXK_LEFT)):
        falas.clear()
        tecla(codigo)
        print(nome, "->", campo.GetValue(), "parte", campo.parte,
              "seleção", campo.GetSelection(), falas)
    # Digitação, número por número
    campo.ChangeValue("")
    campo._tamanho = 0
    falas.clear()
    for n in "25102026":
        campo.AppendText(n)
    print("digitou:", campo.GetValue(), falas, "lê:", regras.ler_data(campo.GetValue()))
    # Apagar não briga com a barra
    campo.ChangeValue("25/")
    campo._tamanho = 3
    campo.SetValue("25")
    print("apagou a barra:", campo.GetValue())
    campo.AppendText("1")
    print("seguiu:", campo.GetValue())
    # Data que não existe
    campo.ChangeValue("")
    campo._tamanho = 0
    falas.clear()
    for n in "31022026":
        campo.AppendText(n)
    print("inválida:", campo.GetValue(), falas)
    campo.ChangeValue("")
    campo._tamanho = 0
    falas.clear()
    tecla(wx.WXK_RIGHT)
    print("seta para o lado, vazio:", falas)
    form.Destroy()
    # Filtros reagem à seta
    form = formulario.Formulario(janela, banco, todos=janela.todos)
    dlg = formulario.ImportarLancamentos(form, janela.todos, set())
    antes = dlg.lista.GetItemCount()
    dlg.origem_de.SetValue("27/10/2026")
    print("filtro:", antes, "->", dlg.lista.GetItemCount())
    dlg.Destroy()
    crit = dialogos.JanelaFiltrar(janela, banco, regras.Filtros())
    print("critérios:", type(crit.datas["origem_de"]).__name__)
    crit.Destroy()
    docs = gerenciar.Documentos(janela, banco, None, janela.todos)
    print("documentos:", type(docs.de).__name__)
    docs.Destroy()


def exercitar_restante(janela, banco):
    avisos, falas = [], []
    original = wx.MessageBox
    wx.MessageBox = lambda *a, **k: (avisos.append(a[0]), wx.YES)[1]
    janela.fala.falar = lambda t, i=False: falas.append(t)
    janela._sincronizar = lambda manual: None

    class NuvemFalsa:
        permissoes = None
        email = "conta@exemplo.com"

        def caminho_novo_documento(self, nome):
            return "u/1-" + nome

        def ler_documento(self, *a):
            return dict(valor=25, descricao="Lido")

    janela.nuvem = NuvemFalsa()
    print("menu:", [(i.GetItemLabelText(), i.IsSubMenu())
                    for i in janela.GetMenuBar().GetMenu(0).GetMenuItems()
                    if not i.IsSeparator()])
    for item in janela.GetMenuBar().GetMenu(0).GetMenuItems():
        if item.IsSubMenu():
            print(" ", item.GetItemLabelText(), [
                i.GetItemLabelText() for i in item.GetSubMenu().GetMenuItems()])
    # Janelas de valores, sem exibir e sem mexer na área de transferência
    mostrados = []
    janela._mostrar_texto = lambda t, x: mostrados.append((t, x))
    janela._ver_resumo(None)
    janela._ver_ultima(None)
    janela._sobre(None)
    print("janelas de texto:", mostrados)
    del janela._mostrar_texto
    copiado = []
    dialogos.copiar_para_area_de_transferencia = lambda t: (copiado.append(t), True)[1]
    wx.GetApp().SetTopWindow(janela)
    falas.clear()
    dlg_texto = dialogos.JanelaDeTexto(janela, "Resumo", "linha 1\nlinha 2")
    dlg_texto._copiar(None)
    print("copiar:", copiado, falas, "somente leitura:", not dlg_texto.texto.IsEditable(),
          "botões:", [f.GetLabel() for f in dlg_texto.GetChildren()
                      if isinstance(f, wx.Button)])
    dlg_texto.Destroy()
    det = dialogos.Detalhes(janela, janela.visiveis[0], janela.todos)
    print("detalhes:", type(det).__mro__[1].__name__, det.texto.GetValue()[:30])
    det.Destroy()
    print("desconectar:", janela.texto_desconectar())
    avisos.clear()
    # Fala vinda do menu espera; fora do menu é na hora
    falas.clear()
    janela._falar("agora")
    janela._ao_fechar_menu(wx.MenuEvent())
    janela._falar("depois")
    print("na hora:", falas)
    wx.CallLater(800, lambda: print("após a espera:", falas))
    janela._menu_fechou_em = -9

    # Parcelar com pagamento dividido e itens
    form = formulario.Formulario(janela, banco, nuvem=janela.nuvem, todos=janela.todos)
    form.EndModal = lambda r: None
    form.itens += [dict(valor=60.0, categoria="A", descricao="x", data_original=None),
                   dict(valor=40.01, categoria="B", descricao="y", data_original=None)]
    form._preencher_itens(0)
    form.dividir.SetValue(True)
    form.pagamentos = [dict(banco="Banco Alfa", forma="PIX", valor=70.0),
                       dict(banco="Cartão Azul", forma=None, valor=30.01)]
    form._preencher_pagamentos(0)
    dlg = formulario.DialogoParcelar(form, form.valor.GetValue(), False, "2027-01-31",
                                     2, 2)
    dlg.quantidade.SetValue("3")
    print("resumo:", dlg.resumo.GetValue())
    parcelas = form._montar_parcelas(dlg.parcelas, "2026-10-30")
    for p in parcelas:
        print(" ", p["parcela"], p["valor"], p["banco"],
              [i["valor"] for i in p["itens"]], [x["valor"] for x in p["pagamentos"]])
    janela._gravar_parcelas(parcelas)
    dlg.Destroy()

    # Parcelar com itens importados exclui os originais
    form = formulario.Formulario(janela, banco, nuvem=janela.nuvem, todos=janela.todos)
    form.EndModal = lambda r: None
    origem = next(l for l in janela.todos if l["id"] == 3)
    form.receber_importados([origem])
    print("confirma importados:", form._confirmar_importados(), form.ids_importados())
    dlg = formulario.DialogoParcelar(form, form.valor.GetValue(), False, "2027-01-31", 1)
    dlg.quantidade.SetValue("2")
    janela._gravar_parcelas(form._montar_parcelas(dlg.parcelas, "2026-10-30"), None,
                            form.ids_importados())
    print("original excluído:", 3 not in {l["id"] for l in banco.lancamentos()})
    dlg.Destroy()

    # Cadastrar textos novos
    form = formulario.Formulario(janela, banco, nuvem=janela.nuvem, todos=janela.todos)
    form.credor.SetValue("Credor Novo")
    form.categoria.SetValue("Categoria Nova")
    form.campo_banco.SetValue("Banco Alfa")
    avisos.clear()
    form._cadastrar_novos(None)
    print("cadastrar:", avisos, banco.opcoes("credor"), banco.opcoes("categoria"))
    falas.clear()
    form._cadastrar_novos(None)
    print("de novo:", falas)

    # Vínculo de documento: desfazer antes de salvar
    arquivo = os.path.join(config.pasta_dados(), "nota.txt")
    with open(arquivo, "w", encoding="utf-8") as f:
        f.write("nota")
    form.ler_caminho(arquivo, guardar=True)
    print("documento:", bool(form.documento_id), form.botao_nao_vincular.IsEnabled(),
          "valor:", form.valor.GetValue())
    form._nao_vincular(None)
    print("sem vínculo:", form.documento_id, form.botao_nao_vincular.IsEnabled(),
          "guardado:", [d["nome_arquivo"] for d in banco.documentos()])

    # Colar
    chamados = []
    print("colar é seguro de consultar:", isinstance(
        formulario.arquivo_colado.__name__, str))
    form._colar = lambda c: chamados.append(c)

    # Ir ao vinculado e ao documento
    a, b = banco.salvar_parcelas([
        dict(data_origem="2026-10-01", pago=False, valor=-1.0, descricao="Um"),
        dict(data_origem="2026-10-01", pago=False, valor=1.0, descricao="Dois")])
    banco.registrar_operacao("vincular", ids=[a, b])
    janela.recarregar()
    janela.filtros = regras.Filtros(busca="Outra coisa")
    janela.aplicar_filtros()
    falas.clear()
    print("foi:", janela.ir_ao_lancamento(a), falas)
    janela._ir_vinculado(None)
    print("vinculado:", falas[-1])
    print("inexistente:", janela.ir_ao_lancamento(99999), falas[-1])
    doc = banco.documentos()[0]
    banco.registrar_operacao("doc_vincular", doc_id=doc["id"], ids=[a])
    janela.recarregar()
    docs = gerenciar.Documentos(janela, banco, janela.nuvem, janela.todos, a)
    print("documento selecionado:", lista_texto(docs.lista, docs.navegacao.atual()))
    docs.EndModal = lambda r: None
    docs.ir_ao_lancamento()
    print("destino do documento:", docs.ir_para_lancamento == a)
    docs.Destroy()

    # Guia
    guia = gerenciar.Guia(janela, "Guia de uso", "guia.txt")
    print("assuntos:", guia.lista.GetCount(), "| primeiro:", guia.lista.GetString(0))
    guia.lista.SetSelection(3)
    guia._mostrar()
    print("texto:", guia.texto.GetValue()[:60])
    guia.Destroy()
    nov = gerenciar.Guia(janela, "Novidades", "novidades.txt")
    print("novidades:", nov.lista.GetCount())
    nov.Destroy()
    print("erros:", [a for a in avisos if "rro" in a])
    wx.MessageBox = original


def exercitar_permissoes(janela, banco):
    class NuvemFalsa:
        permissoes = None

    def rotulos(janela_alvo):
        return [f.GetLabel() for f in _descendentes(janela_alvo)
                if isinstance(f, (wx.Button, wx.CheckBox))]

    falas = []
    janela.fala.falar = lambda t, i=False: falas.append(t)
    janela._sincronizar = lambda manual: None
    janela.nuvem = NuvemFalsa()
    for ia, docs in ((True, True), (False, True), (True, False), (False, False)):
        banco.salvar_permissoes(dict(ia=ia, documentos=docs))
        form = formulario.Formulario(janela, banco, nuvem=janela.nuvem,
                                     todos=janela.todos)
        docs_dlg = gerenciar.Documentos(janela, banco, janela.nuvem, janela.todos)
        print("ia", ia, "docs", docs,
              "| form:", [r for r in rotulos(form) if "IA" in r or "Guardar" in r],
              "| docs:", [r for r in rotulos(docs_dlg)
                          if "IA" in r or "Guardar" in r])
        form.Destroy()
        docs_dlg.Destroy()
    banco.salvar_permissoes(dict(ia=True, documentos=True))
    janela.nuvem.permissoes = dict(ia=False, documentos=False)
    banco.registrar_operacao("doc_guardar", doc_id="d", nome="n.pdf", mime=None,
                             tamanho=1, caminho="c", arquivo_local="x",
                             criado_em="2026-10-30T00:00:00+00:00")
    recusadas = banco.operacoes()
    falas.clear()
    janela._sincronizado(dados_ficticios(), None, False, [], [], [], recusadas)
    print("mudança:", falas)
    print("fila:", banco.qtd_pendentes(), "documentos:", len(banco.documentos()),
          "guardadas:", banco.permissoes())
    falas.clear()
    janela._sincronizado(dados_ficticios(), None, False)
    print("sem mudança:", falas)


def _descendentes(janela):
    for filho in janela.GetChildren():
        yield filho
        yield from _descendentes(filho)


def lista_texto(lista, linha):
    return [lista.GetItemText(linha, c) for c in range(lista.GetColumnCount())]


def main():
    mostrar = "--mostrar" in sys.argv
    argumentos = [a for a in sys.argv[1:] if a != "--mostrar"]
    saida = argumentos[0]
    modo = argumentos[1] if len(argumentos) > 1 else "principal"
    app = wx.App(False)
    banco = Banco(config.caminho_banco())
    banco.substituir_tudo(dados_ficticios(), "30/10/2026 às 12:00")
    janela = Janela(banco, None)
    alvo = janela
    if modo == "contas":
        alvo = dialogos.Contas(janela, regras.situacao_contas(
            banco.contas(), janela.todos))
    elif modo == "login":
        alvo = dialogos.Login(janela)
    elif modo == "aguardando":
        alvo = dialogos.Aguardando(janela)
    elif modo == "detalhes":
        alvo = dialogos.Detalhes(janela, janela.visiveis[0], janela.todos)
    elif modo == "criterios":
        alvo = dialogos.JanelaFiltrar(janela, banco, janela.filtros)
    elif modo == "itens":
        alvo = dialogos.Itens(janela, janela.visiveis[0], janela.todos)
    elif modo == "formulario":
        alvo = formulario.Formulario(janela, banco, janela.visiveis[0])
    elif modo == "novo":
        alvo = formulario.Formulario(janela, banco)
    elif modo == "conflito":
        alvo = formulario.ResolverConflito(
            janela, [("Valor", "-20,00", "-30,00")],
            "Este lançamento mudou aqui e também no servidor.",
            "Manter a versão deste computador")
    elif modo == "config":
        alvo = dialogos.Configuracoes(janela, colunas.padrao(), janela.fala)
    elif modo == "trocadas":
        # Valor primeiro, sem Tipo nem Forma, com Categoria no fim
        janela._trocar_colunas(colunas.visiveis(colunas.normalizar(
            [["valor", True], ["descricao", True], ["categoria", True]])))
    if mostrar:
        janela.ShowWithoutActivating()
        if alvo is not janela:
            alvo.ShowWithoutActivating()

    def terminar():
        try:
            if mostrar:
                salvar(alvo, saida)
                print("visível:", janela.IsShown())
            print("janela na tela:", janela.IsShownOnScreen())
            if modo == "itens":
                print("tabela de itens:", [lista_texto(alvo.lista, n)
                                           for n in range(alvo.lista.GetItemCount())])
            if modo == "formulario":
                print("itens no formulário:", [i["valor"] for i in alvo.itens])
            if modo == "hora":
                exercitar_hora(janela, banco)
            if modo == "parcelar":
                exercitar_parcelar(janela, banco)
            if modo == "completo":
                exercitar_completo(janela, banco)
            if modo == "permissoes":
                exercitar_permissoes(janela, banco)
            if modo == "restante":
                exercitar_restante(janela, banco)
            if modo == "datas":
                exercitar_datas(janela, banco)
            if modo == "inicial":
                exercitar_inicial(janela, banco)
            if modo == "formulario":
                # Lançamento de 3 itens: valor calculado, campos desligados
                print("form valor:", alvo.valor.GetValue(),
                      "ligado:", alvo.valor.IsEnabled(),
                      "tipo:", alvo.tipo.GetStringSelection())
                alvo.EndModal = lambda r: print("fechou com", r)
                alvo._salvar(None)
                print("form resultado:", alvo.resultado)
                alvo._remover_item()
                print("sem um item:", alvo.valor.GetValue(), len(alvo.itens))
            if modo == "novo":
                alvo.EndModal = lambda r: print("fechou com", r)
                wx.MessageBox = lambda *a, **k: print("erro:", a[0][:30])
                alvo._salvar(None)
                alvo.valor.SetValue("12,50")
                alvo.descricao.SetValue("Teste")
                alvo.campo_banco.SetValue("Texto avulso")
                alvo._salvar(None)
                print("novo resultado:", alvo.resultado)
                novo = banco.salvar_lancamento(alvo.resultado, alvo.itens)
                janela.recarregar()
                print("selecionado:", janela._selecionar_id(novo),
                      janela.navegacao.atual(),
                      lista_texto(janela.lista, janela.navegacao.atual()))
            lista = janela.lista
            colunas_n = lista.GetColumnCount()
            nav = janela.navegacao

            def tecla(codigo):
                evt = wx.KeyEvent(wx.wxEVT_KEY_DOWN)
                evt.SetKeyCode(codigo)
                evt.SetEventObject(lista)
                lista.GetEventHandler().ProcessEvent(evt)

            falas = []
            original = janela.fala.falar
            janela.fala.falar = lambda t, i=False: falas.append(t)
            for nome, codigo in (
                    ("direita", wx.WXK_RIGHT), ("direita", wx.WXK_RIGHT),
                    ("baixo", wx.WXK_DOWN), ("baixo", wx.WXK_DOWN),
                    ("cima", wx.WXK_UP), ("fim", wx.WXK_END),
                    ("baixo", wx.WXK_DOWN), ("início", wx.WXK_HOME),
                    ("baixo", wx.WXK_DOWN), ("espaço", wx.WXK_SPACE)):
                falas.clear()
                tecla(codigo)
                print(nome, "coluna", nav.coluna, "cursor", nav.linha,
                      "nativa", lista.GetFirstSelected(), falas)
            for nome, codigo in (("esquerda", wx.WXK_LEFT),
                                 ("esquerda", wx.WXK_LEFT),
                                 ("esquerda", wx.WXK_LEFT)):
                falas.clear()
                tecla(codigo)
                print(nome, "coluna", nav.coluna, "cursor", nav.linha,
                      "nativa", lista.GetFirstSelected(), falas)
            print("pendências após espaço:", banco.pendencias())
            # Na primeira coluna: espaço só marca, Enter só abre
            abertos = []
            abrir_original = janela._abrir
            janela._abrir = lambda e: abertos.append(nav.atual())
            antes = janela.visiveis[nav.atual()]["pago"]
            falas.clear()
            tecla(wx.WXK_SPACE)
            print("espaço na coluna", nav.coluna, "pago", antes, "->",
                  janela.visiveis[nav.atual()]["pago"], "abertos", abertos, falas)
            tecla(wx.WXK_SPACE)
            print("segundo espaço: pago", janela.visiveis[nav.atual()]["pago"],
                  "abertos", abertos)
            janela.navegacao.mover_coluna(1)
            tecla(wx.WXK_RETURN)
            print("Enter fora da primeira coluna abre:", abertos)
            janela.navegacao.mover_coluna(-1)
            janela._abrir = abrir_original
            for qtd, total in ((True, False), (True, True),
                               (False, True), (False, False)):
                janela.falar_quantidade, janela.falar_total = qtd, total
                falas.clear()
                janela.cx_situacao.SetSelection(1)
                janela._ao_filtro(None)
                janela._avisar_quantidade("Filtros limpos. ")
                print("quantidade", qtd, "total", total, "->", falas,
                      "| status:", janela.GetStatusBar().GetStatusText())
            janela.cx_situacao.SetSelection(0)
            janela._ao_filtro(None)
            falas.clear()
            janela.fala.falar = original
            lista.CheckItem(1, not lista.IsItemChecked(1))
            banco.descartar_pendencia(1)
            if modo in ("itens", "contas"):
                print("tabela do diálogo:", alvo.navegacao.mover_coluna(1),
                      "|", alvo.navegacao.mover_linha(1))
            print("itens:", lista.GetItemCount())
            print("linha 0:", [lista.GetItemText(0, c) for c in range(colunas_n)])
            print("resumo:", janela.resumo.GetLabel())
            print("linha igual ao esperado:", tuple(
                lista.GetItemText(0, c) for c in range(colunas_n))
                == colunas.linha(janela.visiveis[0], janela.colunas))
            # Ordem real de criação dos filtros: rótulo logo antes do controle
            filhos = janela.cx_ordem.GetParent().GetChildren()
            print("ordem:", [f.GetLabel() if isinstance(f, wx.StaticText)
                             else type(f).__name__ for f in filhos][:8])
            # Marcar e desmarcar como faz a barra de espaço
            antes = lista.IsItemChecked(2), janela.visiveis[2]["pago"]
            lista.CheckItem(2, not antes[0])
            depois = lista.IsItemChecked(2), janela.visiveis[2]["pago"]
            print("caixa antes/depois:", antes, depois,
                  "linha:", [lista.GetItemText(2, c) for c in range(colunas_n)],
                  "pendências:", banco.pendencias())
            if modo == "config":
                alvo._mover(1)
                print("depois de mover:", alvo.escolha[:3])
                alvo.lista.CheckItem(1, False)
                print("depois de desmarcar:", alvo.escolha[2])
        except Exception:
            traceback.print_exc()
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(0)

    # Se algo travar a janela, o programa fecha do mesmo jeito.
    threading.Timer(20, lambda: os._exit(3)).start()
    wx.CallLater(900, terminar)
    app.MainLoop()


main()
