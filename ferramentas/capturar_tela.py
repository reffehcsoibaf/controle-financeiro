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

from financeiro import colunas, config, dialogos, formulario, gerenciar, regras  # noqa: E402
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
    form2.itens.append(dict(valor=5.0, categoria="", descricao="x",
                            data_original=None))
    avisos.clear()
    form2._parcelar(None)
    print("com itens:", avisos)
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
        alvo = dialogos.BuscaPorCriterios(janela, banco, janela.filtros)
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
            filhos = janela.cx_conta.GetParent().GetChildren()
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
