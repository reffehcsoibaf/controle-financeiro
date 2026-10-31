"""Janela principal: filtros, resumo e lista de lançamentos.

Uma entrada só na barra ("Menu", com os assuntos em submenus). A barra de
ferramentas é para quem usa mouse: não entra na ordem do Tab.
"""
import datetime
import functools
import logging
import os
import sys
import threading
import time
import uuid

import wx

from financeiro import (DESENVOLVEDOR, __version__, atualizacao,
                        banco as banco_mod, colunas, config, dialogos,
                        documentos, formulario, gerenciar, icones,
                        nuvem as nuvem_mod, planilha, preferencias, regras, sincro)
from financeiro.fala import Fala
from financeiro.navegacao import NavegacaoDeColunas

log = logging.getLogger("financeiro.janela")

INTERVALO_SYNC_MS = 5 * 60 * 1000
ESPERA_MENU_MS = 600
JANELA_MENU_S = 1.0
ESPERA_ATUALIZACAO_MS = 10 * 1000
ORDENS = (("recentes", "Recentes primeiro"), ("antigos", "Antigos primeiro"))
TIPOS = (("", "Entradas e saídas"), ("entrada", "Entradas"), ("saida", "Saídas"))
SITUACOES = (("", "Todos"), ("pendente", "Pendentes"), ("pago", "Pagos"))


def seguro(funcao):
    """Erro num evento vai para o log e para a fala, sem derrubar o programa."""
    @functools.wraps(funcao)
    def envolvida(self, *args, **kwargs):
        try:
            return funcao(self, *args, **kwargs)
        except Exception:
            log.exception("erro em %s", funcao.__name__)
            self.avisar("Ocorreu um erro inesperado. Detalhes no log.", True)
    return envolvida


def texto_resumo(r):
    return "%d lançamento%s. Entradas %s. Saídas %s. Saldo %s. Pendente %s." % (
        r.quantidade, "" if r.quantidade == 1 else "s",
        regras.fmt_valor(r.entradas), regras.fmt_valor(r.saidas),
        regras.fmt_valor(r.total), regras.fmt_valor(r.pendente))


class Janela(wx.Frame):
    def __init__(self, banco, nuvem):
        super().__init__(None, title="Controle Financeiro", size=(1120, 680))
        self.banco, self.nuvem = banco, nuvem
        if getattr(sys, "frozen", False):
            self.SetIcon(wx.Icon(sys.executable, wx.BITMAP_TYPE_ICO))
        self.fala = Fala()
        self.filtros = regras.Filtros(ordem=preferencias.ler_ordem(),
                                      tipo=preferencias.ler_tipo(),
                                      status=preferencias.ler_situacao())
        self.todos, self.visiveis, self._linhas = [], [], None
        self._sincronizando = False
        self._sincronizar_de_novo = False
        self._avisou_relogio = False
        self._menu_aberto = False
        self._menu_fechou_em = -JANELA_MENU_S
        self._preenchendo = False
        self.colunas = colunas.visiveis(preferencias.ler_colunas())
        self.falar_quantidade = preferencias.ler_falar_quantidade()
        self.falar_total = preferencias.ler_falar_total()
        self._criar_menu()
        self._criar_barra()
        self._criar_controles()
        self.CreateStatusBar()
        self._relogio = wx.Timer(self)
        self.Bind(wx.EVT_TIMER, self._ao_relogio, self._relogio)
        self.Bind(wx.EVT_CLOSE, self._ao_fechar)
        self.Bind(wx.EVT_MENU_OPEN, self._ao_abrir_menu)
        self.Bind(wx.EVT_MENU_CLOSE, self._ao_fechar_menu)
        self.recarregar()
        lancamento, linha, self._coluna_guardada = preferencias.ler_posicao()
        self._restaurar_foco(lancamento, linha)
        self.lista.SetFocus()

    # ---- montagem ----
    def _item(self, menu, rotulo, acao):
        item = menu.Append(wx.ID_ANY, rotulo)
        self.Bind(wx.EVT_MENU, acao, item)
        return item

    def _criar_menu(self):
        principal = wx.Menu()
        lanc = wx.Menu()
        self._item(lanc, "Novo\tCtrl+N", self._novo)
        self._item(lanc, "Editar\tCtrl+E", self._editar)
        self._item(lanc, "Excluir (tecla Delete na tabela)", self._excluir)
        self._item(lanc, "Abrir\tCtrl+O", self._abrir)
        self._item(lanc, "Ver itens\tCtrl+I", self._ver_itens)
        self._item(lanc, "Ver detalhes\tCtrl+D", self._ver_detalhes)
        self._item(lanc, "Marcar como pago ou pendente\tCtrl+P",
                   self._alternar_pago)
        self._item(lanc, "Ir ao lançamento vinculado\tCtrl+Shift+V",
                   self._ir_vinculado)
        self._item(lanc, "Ver documento vinculado\tCtrl+Shift+O",
                   self._ver_documento_vinculado)
        self._item(lanc, "Filtrar\tCtrl+F", self._abrir_filtrar)
        self._item(lanc, "Limpar filtros\tCtrl+L", self._limpar_filtros)
        self._item(lanc, "Ver resumo\tCtrl+T", self._ver_resumo)
        principal.AppendSubMenu(lanc, "Lançamentos")
        sinc = wx.Menu()
        self._item(sinc, "Sincronizar agora\tF5", self._sincronizar_agora)
        self._item(sinc, "Última sincronização", self._ver_ultima)
        self._item(sinc, "Resolver conflitos", self._resolver_conflitos)
        self._item(sinc, "Operações pendentes", self._pendencias)
        principal.AppendSubMenu(sinc, "Sincronização")
        ferramentas = wx.Menu()
        self._item(ferramentas, "Saldos e limites\tCtrl+B", self._contas)
        self._item(ferramentas, "Documentos\tCtrl+Shift+D", self._documentos)
        self._item(ferramentas, "Configurações\tCtrl+,", self._configuracoes)
        self._item(ferramentas, "Gerenciar listas\tCtrl+Shift+L", self._listas)
        self._item(ferramentas, "Correções aprendidas pela IA", self._correcoes)
        self._item(ferramentas, "Exportar para Excel\tCtrl+Shift+E", self._exportar)
        self._item(ferramentas, "Importar do Excel\tCtrl+Shift+I", self._importar_excel)
        self._item(ferramentas, "Limpar lançamentos duplicados",
                   self._limpar_duplicatas)
        principal.AppendSubMenu(ferramentas, "Ferramentas")
        ajuda = wx.Menu()
        self._item(ajuda, "Guia de uso\tF1", self._guia)
        self._item(ajuda, "Novidades", self._novidades)
        self._item(ajuda, "Verificar atualizações", self._verificar_agora)
        self._item(ajuda, "Sobre", self._sobre)
        principal.AppendSubMenu(ajuda, "Ajuda")
        principal.AppendSeparator()
        self._item(principal, "Desconectar", self._desconectar)
        self._item(principal, "Sair\tCtrl+Q", lambda e: self.Close())
        barra = wx.MenuBar()
        barra.Append(principal, "Menu")
        self.SetMenuBar(barra)

    def _criar_barra(self):
        barra = self.CreateToolBar(wx.TB_HORIZONTAL | wx.TB_TEXT | wx.TB_FLAT)
        tamanho = barra.GetToolBitmapSize()
        for rotulo, arte, acao in (
                ("Novo", wx.ART_NEW, self._novo),
                ("Editar", wx.ART_EDIT, self._editar),
                ("Sincronizar", wx.ART_REFRESH, self._sincronizar_agora),
                ("Filtrar", wx.ART_FIND, self._abrir_filtrar),
                ("Limpar filtros", wx.ART_DELETE, self._limpar_filtros),
                ("Contas e cartões", wx.ART_LIST_VIEW, self._contas),
                ("Detalhes", wx.ART_INFORMATION, self._ver_detalhes)):
            ferramenta = barra.AddTool(
                wx.ID_ANY, rotulo,
                wx.ArtProvider.GetBitmap(arte, wx.ART_TOOLBAR, tamanho),
                rotulo)
            self.Bind(wx.EVT_TOOL, acao, ferramenta)
        barra.Realize()

    def _criar_controles(self):
        p = wx.Panel(self)
        filtros = wx.BoxSizer(wx.HORIZONTAL)

        def rotulado(rotulo, criar, prop=0):
            # O rótulo nasce antes do controle: o leitor de tela usa o texto
            # que vem logo antes dele na ordem de criação.
            filtros.Add(wx.StaticText(p, label=rotulo), 0,
                        wx.ALIGN_CENTER_VERTICAL | wx.LEFT, 8)
            controle = criar()
            filtros.Add(controle, prop, wx.ALIGN_CENTER_VERTICAL | wx.LEFT, 4)
            return controle

        def caixa_de_escolha(rotulo, opcoes):
            return rotulado(rotulo, lambda: wx.Choice(
                p, name=rotulo, choices=[r for _, r in opcoes]))

        self.cx_ordem = caixa_de_escolha("Ordem", ORDENS)
        self.cx_ordem.SetSelection(
            [k for k, _ in ORDENS].index(self.filtros.ordem))
        self.cx_tipo = caixa_de_escolha("Tipo", TIPOS)
        self.cx_tipo.SetSelection([k for k, _ in TIPOS].index(self.filtros.tipo))
        self.cx_situacao = caixa_de_escolha("Situação", SITUACOES)
        self.cx_situacao.SetSelection(
            [k for k, _ in SITUACOES].index(self.filtros.status))
        filtros.AddStretchSpacer()
        self.botao_filtrar = wx.Button(p, label="Filtrar")
        filtros.Add(self.botao_filtrar, 0, wx.LEFT, 8)
        self.botao_fechar = wx.Button(p, label="Fechar")
        filtros.Add(self.botao_fechar, 0, wx.LEFT | wx.RIGHT, 8)

        self.resumo = wx.StaticText(p, label="")
        fonte = self.resumo.GetFont()
        fonte.MakeBold()
        self.resumo.SetFont(fonte)

        rot_lista = wx.StaticText(p, label="Lançamentos")
        self.lista = wx.ListCtrl(p, name="Lançamentos",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        self.lista.EnableCheckBoxes(True)
        self.imagens, self.indices = icones.lista_de_imagens()
        self.lista.SetImageList(self.imagens, wx.IMAGE_LIST_SMALL)
        self._criar_colunas()

        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(filtros, 0, wx.EXPAND | wx.TOP | wx.BOTTOM, 8)
        caixa.Add(self.resumo, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM, 8)
        caixa.Add(rot_lista, 0, wx.LEFT, 8)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        p.SetSizer(caixa)

        self.cx_ordem.Bind(wx.EVT_CHOICE, self._ao_mudar_ordem)
        self.cx_tipo.Bind(wx.EVT_CHOICE, self._ao_filtro)
        self.cx_situacao.Bind(wx.EVT_CHOICE, self._ao_filtro)
        self.botao_filtrar.Bind(wx.EVT_BUTTON, self._abrir_filtrar)
        self.botao_fechar.Bind(wx.EVT_BUTTON, lambda e: self.Close())
        self.navegacao = NavegacaoDeColunas(
            self.lista, self.fala, ao_ativar=lambda: self._abrir(None),
            ao_espaco=lambda: self._alternar_pago(None),
            ao_excluir=lambda: self._excluir(None))
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, self._abrir)
        self.lista.Bind(wx.EVT_LIST_ITEM_CHECKED, self._ao_marcar)
        self.lista.Bind(wx.EVT_LIST_ITEM_UNCHECKED, self._ao_marcar)
        self.lista.Bind(wx.EVT_CONTEXT_MENU, self._menu_contexto)

    def _criar_colunas(self):
        for col, c in enumerate(self.colunas):
            self.lista.InsertColumn(
                col, c.titulo, width=c.largura,
                format=wx.LIST_FORMAT_RIGHT if c.direita
                else wx.LIST_FORMAT_LEFT)

    @seguro
    def _configuracoes(self, _evt):
        dlg = dialogos.Configuracoes(self, preferencias.ler_colunas(), self.fala,
                                     self.falar_quantidade, self.falar_total)
        if dlg.ShowModal() == wx.ID_OK:
            preferencias.salvar_colunas(dlg.escolha)
            self.falar_quantidade = dlg.falar_quantidade.GetValue()
            preferencias.salvar_falar_quantidade(self.falar_quantidade)
            self.falar_total = dlg.falar_total.GetValue()
            preferencias.salvar_falar_total(self.falar_total)
            self._trocar_colunas(colunas.visiveis(dlg.escolha))
            self.avisar("Configurações salvas.", True)
        dlg.Destroy()
        self.lista.SetFocus()

    def _trocar_colunas(self, novas):
        escolhido = self._linha_em_foco_id
        self._preenchendo = True
        self.lista.DeleteAllItems()
        self.lista.DeleteAllColumns()
        self.colunas = novas
        self._criar_colunas()
        self._preenchendo = False
        self._linhas = None
        self._ids_desenhados = ()
        self.aplicar_filtros()
        self._restaurar_foco(escolhido)

    # ---- dados na tela ----
    def avisar(self, texto, interromper=False):
        self.SetStatusText(texto)
        self._falar(texto, interromper)

    def _ao_abrir_menu(self, evt):
        self._menu_aberto = True
        evt.Skip()

    def _ao_fechar_menu(self, evt):
        self._menu_aberto = False
        self._menu_fechou_em = time.monotonic()
        evt.Skip()

    def _falar(self, texto, interromper=False):
        """Quando o menu fecha, o leitor de tela anuncia o foco que volta à tabela
        e corta o que estiver sendo falado. A fala que vem do menu espera isso."""
        recente = time.monotonic() - self._menu_fechou_em < JANELA_MENU_S
        if self._menu_aberto or recente:
            wx.CallLater(ESPERA_MENU_MS, self.fala.falar, texto, interromper)
        else:
            self.fala.falar(texto, interromper)

    def recarregar(self):
        """Lê a cópia local e redesenha só se algo mudou, para não tirar a
        posição de quem está percorrendo a lista."""
        self.todos = self.banco.lancamentos()
        self.aplicar_filtros()

    def aplicar_filtros(self):
        self.visiveis = regras.filtrar(self.todos, self.filtros)
        resumo = texto_resumo(regras.resumir(self.visiveis))
        # Os filtros da janela não aparecem na tela inicial: sem isto, lançamentos
        # poderiam parecer sumidos
        if regras.tem_filtro_da_janela(self.filtros):
            resumo = "Com filtros. " + resumo
        self.resumo.SetLabel(resumo)
        linhas = [colunas.linha(l, self.colunas) for l in self.visiveis]
        if linhas != self._linhas:
            self._redesenhar(linhas)
        else:
            self._ids_desenhados = [l["id"] for l in self.visiveis]
        return resumo

    def _redesenhar(self, linhas):
        escolhido = self._linha_em_foco_id
        posicao = self.navegacao.atual()
        self._preenchendo = True
        self.lista.Freeze()
        try:
            self.lista.DeleteAllItems()
            contraste = icones.alto_contraste()
            for n, (l, textos) in enumerate(zip(self.visiveis, linhas)):
                entrada = regras.eh_entrada(l)
                imagens = [self.indices.get(colunas.imagem(c, l), -1)
                           for c in self.colunas]
                self.lista.InsertItem(n, textos[0], imagens[0])
                for col, texto in enumerate(textos[1:], 1):
                    self.lista.SetItem(n, col, texto, imagens[col])
                self.lista.CheckItem(n, l["pago"])
                if not contraste:
                    self.lista.SetItemTextColour(
                        n, icones.COR_ENTRADA if entrada else icones.COR_SAIDA)
        finally:
            self.lista.Thaw()
            self._preenchendo = False
        self._linhas = linhas
        self._restaurar_foco(escolhido, posicao)

    @property
    def _linha_em_foco_id(self):
        n = self.navegacao.atual()
        if self._linhas is None or not 0 <= n < len(self._ids_desenhados):
            return None
        return self._ids_desenhados[n]

    def _restaurar_foco(self, escolhido, posicao=0):
        """Se o lançamento saiu da lista (por exemplo, marcado como pago no
        filtro de pendentes), fica no que tomou o lugar dele."""
        self._ids_desenhados = [l["id"] for l in self.visiveis]
        if not self.visiveis:
            return
        n = (self._ids_desenhados.index(escolhido)
             if escolhido in self._ids_desenhados
             else min(max(posicao, 0), len(self.visiveis) - 1))
        self.lista.Select(n)
        self.lista.Focus(n)
        self.lista.EnsureVisible(n)
        self.navegacao.definir_linha(n)

    _ids_desenhados = ()

    # ---- filtros ----
    @seguro
    def _ao_filtro(self, _evt):
        self.filtros.tipo = TIPOS[self.cx_tipo.GetSelection()][0]
        self.filtros.status = SITUACOES[self.cx_situacao.GetSelection()][0]
        self.aplicar_filtros()
        self._avisar_quantidade(interromper=False)

    def _qtd_texto(self):
        n = len(self.visiveis)
        return "%d lançamento%s." % (n, "" if n == 1 else "s")

    def _avisar_quantidade(self, prefixo="", interromper=True):
        """A barra de status sempre mostra quantidade e total; a fala é opcional."""
        quantidade = self._qtd_texto()
        total = "Total %s." % regras.fmt_valor(
            regras.resumir(self.visiveis).total)
        self.SetStatusText("%s%s %s" % (prefixo, quantidade, total))
        partes = [prefixo.strip()] if prefixo else []
        if self.falar_quantidade:
            partes.append(quantidade)
        if self.falar_total:
            partes.append(total)
        self._falar(" ".join(partes), interromper)

    @seguro
    def _ao_mudar_ordem(self, _evt):
        """A ordem não esconde nada: fica guardada e não conta como filtro."""
        self.filtros.ordem = ORDENS[self.cx_ordem.GetSelection()][0]
        preferencias.salvar_ordem(self.filtros.ordem)
        self.aplicar_filtros()

    @seguro
    def _abrir_filtrar(self, _evt):
        dlg = dialogos.JanelaFiltrar(self, self.banco, self.filtros)
        aplicou = dlg.ShowModal() == wx.ID_OK
        dlg.Destroy()
        if aplicou:
            self.aplicar_filtros()
            self.lista.SetFocus()
            self._avisar_quantidade()

    def _redefinir_filtros(self, banco=""):
        """Tudo volta ao início, menos a ordem."""
        self.filtros = regras.Filtros(ordem=self.filtros.ordem, banco=banco)
        self.cx_tipo.SetSelection(0)
        self.cx_situacao.SetSelection(0)
        self.aplicar_filtros()

    @seguro
    def _limpar_filtros(self, _evt):
        self._redefinir_filtros()
        self._avisar_quantidade("Filtros limpos. ")

    def _ver_resumo(self, _evt):
        """Em janela própria: fica aberta até fechar, e nada corta a leitura."""
        self._mostrar_texto("Resumo", self.resumo.GetLabel().replace(". ", ".\n"))

    def _mostrar_texto(self, titulo, texto):
        dlg = dialogos.JanelaDeTexto(self, titulo, texto)
        dlg.ShowModal()
        dlg.Destroy()
        self.lista.SetFocus()

    # ---- ações ----
    def _selecionado(self):
        n = self.navegacao.atual()
        if 0 <= n < len(self.visiveis):
            return self.visiveis[n]
        self.avisar("Nenhum lançamento selecionado.", True)
        return None

    def _selecionar_id(self, lancamento_id):
        if lancamento_id in self._ids_desenhados:
            self._restaurar_foco(lancamento_id)
            return True
        return False

    @seguro
    def _novo(self, _evt):
        self._abrir_formulario_novo()

    def _abrir_formulario_novo(self, documento=None):
        dlg = formulario.Formulario(self, self.banco, nuvem=self.nuvem,
                                    todos=self.todos)
        if documento:
            d, caminho = documento
            wx.CallAfter(dlg.ler_caminho, caminho, False, d["id"])
        if dlg.ShowModal() == wx.ID_OK:
            if dlg.parcelas:
                self._gravar_parcelas(dlg.parcelas, dlg.documento_id,
                                      dlg.ids_importados())
            else:
                self._gravar_formulario(dlg, None)
        dlg.Destroy()
        self.lista.SetFocus()

    def lancamento_atual(self):
        n = self.navegacao.atual()
        return self.visiveis[n] if 0 <= n < len(self.visiveis) else None

    def guardar_documento(self, caminho):
        """Fica na fila com uma cópia local, para subir mesmo sem internet agora.
        Devolve o id do documento."""
        doc_id = str(uuid.uuid4())
        nome = os.path.basename(caminho)
        local = documentos.guardar_copia(caminho, doc_id, nome)
        self.banco.registrar_operacao(
            "doc_guardar", doc_id=doc_id, nome=nome, arquivo_local=local,
            mime=documentos.tipo_do_arquivo(caminho) or None,
            tamanho=os.path.getsize(caminho),
            caminho=self.nuvem.caminho_novo_documento(nome),
            criado_em=banco_mod.agora_iso())
        self._sincronizar(manual=False)
        return doc_id

    def _gravar_parcelas(self, parcelas, documento_id=None, importados=()):
        ids = self.banco.salvar_parcelas(parcelas)
        if documento_id:    # um documento só tem um lançamento: fica na primeira
            self.banco.registrar_operacao("doc_vincular", doc_id=documento_id,
                                          ids=[ids[0]])
        if importados:
            self.banco.registrar_operacao("docs_mover", ids=[ids[0]] + list(importados))
            for i in importados:
                self.banco.excluir_lancamento(i)
        self.recarregar()
        visivel = self._selecionar_id(ids[0])
        texto = "%d parcelas criadas, total %s." % (
            len(ids), regras.fmt_valor_com_sinal(
                round(sum(p["valor"] for p in parcelas), 2)))
        if not visivel:
            texto += " Elas não aparecem com os filtros atuais."
        self.avisar(texto, True)
        self._sincronizar(manual=False)

    @seguro
    def _editar(self, _evt):
        l = self._selecionado()
        if not l:
            return
        dlg = formulario.Formulario(self, self.banco, l, nuvem=self.nuvem,
                                    todos=self.todos)
        if dlg.ShowModal() == wx.ID_OK:
            self._gravar_formulario(dlg, l["id"])
        dlg.Destroy()
        self.lista.SetFocus()

    def _gravar_formulario(self, dlg, lancamento_id):
        dados = dlg.resultado
        novo = self.banco.salvar_lancamento(dados, dlg.itens, lancamento_id,
                                            dlg.pagamentos)
        texto = "Lançamento salvo: %s." % regras.fmt_valor_com_sinal(dados["valor"])
        cartao = dlg.cartao_escolhido()
        if cartao:
            espelho = self.banco.salvar_parcelas(
                [regras.lancamento_espelho_fatura(dados, cartao)])[0]
            self.banco.registrar_operacao("vincular", ids=[novo, espelho])
            texto += " Entrada criada no cartão %s para recuperar o limite." % cartao
        importados = dlg.ids_importados()
        if importados:
            # Os documentos mudam de dono antes de os originais sumirem
            self.banco.registrar_operacao("docs_mover", ids=[novo] + importados)
            for i in importados:
                self.banco.excluir_lancamento(i)
            texto += " %d lançamento%s antigo%s consolidado%s." % (
                (len(importados),) + (("", "", "") if len(importados) == 1
                                      else ("s", "s", "s")))
        if dlg.documento_id:
            self.banco.registrar_operacao("doc_vincular", doc_id=dlg.documento_id,
                                          ids=[novo])
            texto += " Documento vinculado."
        if dlg.extracao_ia:
            for correcao in regras.correcoes_aprendidas(
                    dlg.extracao_ia, dados, bool(dlg.itens)):
                self.banco.registrar_operacao("correcao_add", **correcao)
        self.recarregar()
        visivel = self._selecionar_id(novo)
        if not visivel:
            texto += " Ele não aparece com os filtros atuais."
        self.avisar(texto, True)
        self._sincronizar(manual=False)

    @seguro
    def _excluir(self, _evt):
        l = self._selecionado()
        if not l:
            return
        desc = "%s, %s" % (regras.descricao_de(l),
                           regras.fmt_valor_com_sinal(l["valor"]))
        vinculado = next((x for x in self.todos
                          if x["id"] == l["lancamento_vinculado_id"]), None)
        ids = [l["id"]]
        if vinculado:
            resposta = wx.MessageBox(
                "%s está vinculado a outro lançamento: %s, em %s. Excluir os "
                "dois? Sim exclui os dois; Não exclui só este, e o outro "
                "continua existindo." % (
                    desc, regras.fmt_valor_com_sinal(vinculado["valor"]),
                    vinculado["banco"] or "sem conta"),
                "Excluir", wx.YES_NO | wx.CANCEL | wx.CANCEL_DEFAULT
                | wx.ICON_QUESTION, self)
            if resposta == wx.CANCEL:
                return
            if resposta == wx.YES:
                ids.append(vinculado["id"])
        elif wx.MessageBox("Excluir o lançamento %s?" % desc, "Excluir",
                           wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION,
                           self) != wx.YES:
            return
        for i in ids:
            self.banco.excluir_lancamento(i)
        self.recarregar()
        self.avisar("Excluídos os dois lançamentos." if len(ids) > 1
                    else "Lançamento excluído.", True)
        self._sincronizar(manual=False)

    # ---- gerenciamento ----
    @seguro
    def _listas(self, _evt):
        dlg = gerenciar.Listas(self, self.banco)
        dlg.ShowModal()
        dlg.Destroy()
        self.recarregar()
        self.lista.SetFocus()
        self._sincronizar(manual=False)

    @seguro
    def _correcoes(self, _evt):
        dlg = gerenciar.Correcoes(self, self.banco)
        dlg.ShowModal()
        dlg.Destroy()
        self.lista.SetFocus()
        self._sincronizar(manual=False)

    @seguro
    def _pendencias(self, _evt):
        dlg = gerenciar.Pendencias(self, self.banco)
        dlg.ShowModal()
        dlg.Destroy()
        self.lista.SetFocus()
        self._sincronizar(manual=False)

    @seguro
    def _documentos(self, _evt, do_lancamento=None):
        dlg = gerenciar.Documentos(self, self.banco, self.nuvem, self.todos,
                                   do_lancamento)
        dlg.ShowModal()
        documento, destino = dlg.analisar_doc, dlg.ir_para_lancamento
        dlg.Destroy()
        self.recarregar()
        self._sincronizar(manual=False)
        if documento:
            self._abrir_formulario_novo(documento)
        if destino:
            self.ir_ao_lancamento(destino)
        self.lista.SetFocus()

    def ir_ao_lancamento(self, lancamento_id):
        """Limpa os filtros, porque o lançamento pode estar oculto por eles."""
        self._redefinir_filtros()
        if self._selecionar_id(lancamento_id):
            l = self.lancamento_atual()
            self.avisar("%s, %s." % (regras.descricao_de(l),
                                     regras.fmt_valor_com_sinal(l["valor"])), True)
            return True
        self.avisar("Lançamento não encontrado. Ele pode ter sido excluído.", True)
        return False

    @seguro
    def _ir_vinculado(self, _evt):
        l = self._selecionado()
        if not l:
            return
        if l["lancamento_vinculado_id"]:
            self.ir_ao_lancamento(l["lancamento_vinculado_id"])
        else:
            self.avisar("Este lançamento não tem outro vinculado.", True)

    @seguro
    def _ver_documento_vinculado(self, _evt):
        l = self._selecionado()
        if not l:
            return
        if l["documentos"]:
            self._documentos(None, l["id"])
        else:
            self.avisar("Este lançamento não tem documento vinculado.", True)

    @seguro
    def _guia(self, _evt):
        self._mostrar_guia("Guia de uso", "guia.txt")

    @seguro
    def _novidades(self, _evt):
        self._mostrar_guia("Novidades", "novidades.txt")

    def _mostrar_guia(self, titulo, arquivo):
        dlg = gerenciar.Guia(self, titulo, arquivo)
        dlg.ShowModal()
        dlg.Destroy()
        self.lista.SetFocus()

    def _situacoes(self):
        return regras.situacao_contas(self.banco.contas(), self.todos,
                                      self.banco.opcoes("banco"))

    def _configurar_conta(self, pai, nome):
        atual = next((c for c in self.banco.contas() if c["nome_banco"] == nome), None)
        dlg = gerenciar.ConfigurarConta(pai, nome, atual)
        if dlg.ShowModal() == wx.ID_OK:
            self.banco.registrar_operacao("conta_salvar", nome=nome, **dlg.resultado)
            self.fala.falar("%s configurada." % nome, True)
            self._sincronizar(manual=False)
        dlg.Destroy()
        return self._situacoes()

    def _remover_conta(self, pai, situacao):
        if not situacao.tipo:
            self.fala.falar("Esta conta não tem configuração.", True)
        elif wx.MessageBox(
                "Remover a configuração de %s? O nome continua nas listas, só deixa "
                "de ter saldo ou limite calculado." % situacao.nome,
                "Remover configuração", wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION,
                pai) == wx.YES:
            self.banco.registrar_operacao("conta_excluir", nome=situacao.nome)
            self.fala.falar("Configuração removida.", True)
            self._sincronizar(manual=False)
        return self._situacoes()

    @seguro
    def _exportar(self, _evt):
        if not self.visiveis:
            self.avisar("Não há dados para exportar.", True)
            return
        nome = "controle_financeiro_%s.xlsx" % datetime.datetime.now().strftime(
            "%Y%m%d_%H%M%S")
        with wx.FileDialog(self, "Exportar para Excel", defaultFile=nome,
                           wildcard="Planilha do Excel (*.xlsx)|*.xlsx",
                           style=wx.FD_SAVE | wx.FD_OVERWRITE_PROMPT) as dlg:
            if dlg.ShowModal() != wx.ID_OK:
                return
            caminho = dlg.GetPath()
        planilha.exportar(self.visiveis, caminho)
        self.avisar("Planilha exportada com %d lançamentos." % len(self.visiveis), True)

    @seguro
    def _importar_excel(self, _evt):
        with wx.FileDialog(self, "Importar do Excel",
                           wildcard="Planilhas do Excel (*.xlsx;*.xls)|*.xlsx;*.xls",
                           style=wx.FD_OPEN | wx.FD_FILE_MUST_EXIST) as dlg:
            if dlg.ShowModal() != wx.ID_OK:
                return
            caminho = dlg.GetPath()
        try:
            lidos = planilha.ler(caminho)
        except Exception as e:
            log.warning("planilha: %r", e)
            wx.MessageBox("Não consegui ler a planilha. %s" % (
                e if isinstance(e, ValueError) else ""), "Importar",
                wx.OK | wx.ICON_ERROR, self)
            return
        novos, repetidos = planilha.novos_e_repetidos(lidos, self.todos)
        if not novos:
            self.avisar("Nenhum lançamento novo: os %d já existiam." % len(lidos), True)
            return
        if wx.MessageBox(
                "Importar %d lançamento%s novo%s? %d já existia%s e será ignorado." % (
                    len(novos), "" if len(novos) == 1 else "s",
                    "" if len(novos) == 1 else "s", repetidos,
                    "" if repetidos == 1 else "m"),
                "Importar", wx.YES_NO | wx.ICON_QUESTION, self) != wx.YES:
            return
        self.banco.salvar_importados(novos)
        for campo, valores in planilha.opcoes_de(novos).items():
            cadastradas = set(self.banco.opcoes(campo))
            for valor in sorted(valores - cadastradas):
                self.banco.registrar_operacao("opcao_add", campo=campo, valor=valor)
        self.recarregar()
        self.avisar("%d importado%s, %d já existia%s." % (
            len(novos), "" if len(novos) == 1 else "s", repetidos,
            "" if repetidos == 1 else "m"), True)
        self._sincronizar(manual=False)

    @seguro
    def _limpar_duplicatas(self, _evt):
        repetidos = planilha.ids_duplicados(self.todos)
        if not repetidos:
            self.avisar("Nenhum lançamento duplicado encontrado.", True)
            return
        if wx.MessageBox(
                "Encontrei %d lançamento%s duplicado%s. Excluir as cópias, mantendo o "
                "mais antigo de cada um?" % (
                    len(repetidos), "" if len(repetidos) == 1 else "s",
                    "" if len(repetidos) == 1 else "s"),
                "Limpar duplicados", wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION,
                self) != wx.YES:
            return
        for i in repetidos:
            self.banco.excluir_lancamento(i)
        self.recarregar()
        self.avisar("%d duplicata%s removida%s." % (
            len(repetidos), "" if len(repetidos) == 1 else "s",
            "" if len(repetidos) == 1 else "s"), True)
        self._sincronizar(manual=False)

    @seguro
    def _resolver_conflitos(self, _evt):
        conflitos = self.banco.conflitos()
        if not conflitos:
            self.avisar("Não há conflitos.", True)
            return
        for c in conflitos:
            if not self._resolver_um(c):
                break
        self.recarregar()
        self.lista.SetFocus()
        self._sincronizar(manual=False)

    def _resolver_um(self, c):
        """Devolve False se a pessoa deixou para decidir depois."""
        local, servidor = c["local"], c["servidor"]
        rotulo = "Manter a versão deste computador"
        if c["operacao"] == "excluir":
            explicacao = ("Você excluiu este lançamento, mas ele mudou no "
                          "servidor.")
            linhas = [("Situação neste computador", "Excluído",
                       regras.descricao_de(dict(
                           servidor, descricao=servidor.get("descricao") or "",
                           estabelecimento=servidor.get("estabelecimento") or "")))]
            rotulo = "Excluir mesmo assim"
        elif servidor is None:
            explicacao = ("Você alterou este lançamento, mas ele foi excluído "
                          "no servidor.")
            linhas = [("Lançamento", regras.descricao_de(local), "Excluído")]
            rotulo = "Manter, criando de novo"
        else:
            explicacao = "Este lançamento mudou aqui e também no servidor."
            linhas = sincro.diferencas(local, servidor)
        dlg = formulario.ResolverConflito(self, linhas, explicacao, rotulo)
        resposta = dlg.ShowModal()
        dlg.Destroy()
        if resposta == wx.ID_YES:
            self.banco.manter_versao_local(c)
        elif resposta == wx.ID_NO:
            self.banco.usar_versao_do_servidor(c)
        else:
            return False
        return True

    @seguro
    def _abrir(self, _evt):
        """Com itens, abre a tabela deles; sem itens, os detalhes."""
        l = self._selecionado()
        if l:
            self._mostrar_itens(l) if l["itens"] else self._mostrar_detalhes(l)

    @seguro
    def _ver_itens(self, _evt):
        l = self._selecionado()
        if l and l["itens"]:
            self._mostrar_itens(l)
        elif l:
            self.avisar("Este lançamento não tem itens.", True)

    @seguro
    def _ver_detalhes(self, _evt):
        l = self._selecionado()
        if l:
            self._mostrar_detalhes(l)

    def _mostrar_itens(self, l):
        dlg = dialogos.Itens(self, l, self.todos)
        dlg.ShowModal()
        dlg.Destroy()

    def _mostrar_detalhes(self, l):
        dlg = dialogos.Detalhes(self, l, self.todos)
        dlg.ShowModal()
        dlg.Destroy()

    @seguro
    def _ao_marcar(self, evt):
        if self._preenchendo:
            return
        n = evt.GetIndex()
        if 0 <= n < len(self.visiveis):
            self._definir_pago(n, self.lista.IsItemChecked(n))

    @seguro
    def _alternar_pago(self, _evt):
        n = self.navegacao.atual()
        if not 0 <= n < len(self.visiveis):
            self.avisar("Nenhum lançamento selecionado.", True)
            return
        self._preenchendo = True
        self.lista.CheckItem(n, not self.visiveis[n]["pago"])
        self._preenchendo = False
        self._definir_pago(n, not self.visiveis[n]["pago"])

    def _definir_pago(self, n, pago):
        """Grava primeiro aqui, para valer sem internet, e depois tenta enviar."""
        l = self.visiveis[n]
        if l["pago"] == pago:
            return
        self.banco.marcar_pago(l["id"], pago)
        l["pago"] = pago
        # Diz qual lançamento mudou: Ctrl+Alt do leitor de tela anda pelas linhas
        # sem mudar a linha atual, que é a que recebe a marcação.
        self.avisar("%s: %s, %s." % (
            "Pago" if pago else "Pendente", regras.descricao_de(l),
            regras.fmt_valor(l["valor"])), True)
        ids = [x["id"] for x in regras.filtrar(self.todos, self.filtros)]
        if ids == self._ids_desenhados:
            for col, c in enumerate(self.colunas):
                if c.chave == "situacao":
                    self.lista.SetItem(n, col, c.valor(l))
            self._linhas[n] = colunas.linha(l, self.colunas)
            self.resumo.SetLabel(texto_resumo(regras.resumir(self.visiveis)))
        else:
            self.aplicar_filtros()
        self._sincronizar(manual=False)

    def _menu_contexto(self, _evt):
        menu = wx.Menu()
        self._item(menu, "Abrir", self._abrir)
        self._item(menu, "Editar", self._editar)
        self._item(menu, "Excluir", self._excluir)
        self._item(menu, "Ver detalhes", self._ver_detalhes)
        self._item(menu, "Marcar como pago ou pendente", self._alternar_pago)
        self._item(menu, "Filtrar", self._abrir_filtrar)
        self._item(menu, "Limpar filtros", self._limpar_filtros)
        self.lista.PopupMenu(menu)
        menu.Destroy()

    @seguro
    def _contas(self, _evt):
        dlg = dialogos.Contas(self, self._situacoes(), self._configurar_conta,
                              self._remover_conta)
        if dlg.ShowModal() == wx.ID_OK and dlg.escolhida:
            self._redefinir_filtros(banco=dlg.escolhida)
            self.lista.SetFocus()
            self._avisar_quantidade("%s: " % dlg.escolhida)
        dlg.Destroy()

    def _sobre(self, _evt):
        self._mostrar_texto("Sobre", "Controle Financeiro para Windows\nVersão %s\n"
                            "Desenvolvedor: %s" % (__version__, DESENVOLVEDOR))

    def _ver_ultima(self, _evt):
        texto = self._texto_ultima()
        pendentes = self.banco.qtd_pendentes()
        if pendentes:
            texto += "\n%d alteração%s aguardando envio." % (
                pendentes, "" if pendentes == 1 else "ões")
        self._mostrar_texto("Última sincronização", texto)

    def _texto_ultima(self):
        quando = self.banco.ultima_sincronizacao()
        return ("Última sincronização: %s." % quando if quando
                else "Ainda não sincronizado.")

    # ---- login e sincronização ----
    def iniciar(self):
        """Com sessão guardada abre direto, mesmo sem internet."""
        # Só agora a janela tem tamanho para rolar a tabela até a coluna
        self.navegacao.definir_coluna(self._coluna_guardada)
        if getattr(sys, "frozen", False):
            wx.CallLater(ESPERA_ATUALIZACAO_MS, self._verificar_atualizacao, False)
        if self.nuvem.sessao:
            self.SetStatusText(self._texto_ultima())
            self._sincronizar(manual=False)
            self._relogio.Start(INTERVALO_SYNC_MS)
        else:
            self._pedir_login()

    def _pedir_login(self):
        dlg = dialogos.Login(self)
        quer = dlg.ShowModal() == wx.ID_OK
        dlg.Destroy()
        if not quer:
            self.Close()
            return
        espera = dialogos.Aguardando(self)
        resultado = {}

        def trabalhar():
            try:
                self.nuvem.entrar()
            except Exception as e:
                log.warning("login: %r", e)
                resultado["erro"] = e
            wx.CallAfter(espera.EndModal, wx.ID_OK)

        threading.Thread(target=trabalhar, daemon=True).start()
        if espera.ShowModal() == wx.ID_CANCEL:
            self.nuvem.cancelar_login()
        espera.Destroy()
        erro = resultado.get("erro")
        if self.nuvem.sessao and not erro:
            self.Raise()
            self.avisar("Login concluído: %s. Baixando os dados."
                        % self.nuvem.email, True)
            self._sincronizar(manual=True)
            self._relogio.Start(INTERVALO_SYNC_MS)
            return
        if isinstance(erro, nuvem_mod.SemConexao):
            texto = "Sem conexão com a internet."
        elif erro and not isinstance(erro, nuvem_mod.LoginCancelado):
            texto = "O login não foi concluído."
        else:
            texto = ""
        if texto:
            wx.MessageBox(texto, "Entrar", wx.OK | wx.ICON_ERROR, self)
        self._pedir_login()

    def _ao_relogio(self, _evt):
        self._sincronizar(manual=False)

    def _sincronizar_agora(self, _evt):
        if self._sincronizando:
            self.avisar("A sincronização já está em andamento.", True)
        else:
            self.avisar("Sincronizando.", True)
            self._sincronizar(manual=True)

    def _sincronizar(self, manual):
        if self.nuvem is None or not self.nuvem.sessao:
            return
        if self._sincronizando:
            # Uma marcação feita agora ficou de fora do envio em andamento
            self._sincronizar_de_novo = True
            return
        self._sincronizando = True
        pendencias = self.banco.pendencias()
        envios = self.banco.envios()
        operacoes = self.banco.operacoes()
        resultados, resultados_lanc, feitas, recusadas = [], [], [], []

        def trabalhar():
            try:
                dados, erro = self.nuvem.sincronizar(
                    pendencias, resultados, envios, resultados_lanc,
                    operacoes, feitas, recusadas), None
            except Exception as e:
                dados, erro = None, e
                if not isinstance(e, nuvem_mod.SemConexao):
                    log.warning("sincronização: %r", e)
            wx.CallAfter(self._sincronizado, dados, erro, manual, resultados,
                         resultados_lanc, feitas, recusadas)

        threading.Thread(target=trabalhar, daemon=True).start()

    def _resolver_pela_hora(self, envio, servidor):
        """Vale a alteração mais recente. Devolve a frase a falar, ou None se
        os horários não permitem decidir e a pessoa precisa escolher."""
        decisao = sincro.decidir(envio["editado_em"], servidor["updated_at"],
                                 nuvem_mod.defasagem())
        if decisao == sincro.PERGUNTAR:
            return None
        nome = regras.descricao_de(dict(
            servidor, descricao=servidor.get("descricao") or "",
            estabelecimento=servidor.get("estabelecimento") or ""))
        conflito = dict(lancamento_id=envio["lancamento_id"],
                        operacao=envio["operacao"], servidor=servidor)
        if decisao == sincro.LOCAL:
            self.banco.manter_versao_local(conflito)
            self._sincronizar_de_novo = True
            return "%s: valeu a alteração deste computador, a mais recente." % nome
        self.banco.usar_versao_do_servidor(conflito)
        return "%s: valeu a alteração do servidor, a mais recente." % nome

    def _aplicar_envios_de_lancamentos(self, resultados, dados):
        """Devolve (quantos a pessoa precisa decidir, quantos falharam, {id
        temporário: definitivo}, frases). Conflito só se decide com o download
        em mãos: sem ele, o envio fica na fila e é tentado de novo."""
        conflitos, falhas, trocas, frases = 0, 0, {}, []
        for envio, resposta in resultados:
            situacao = resposta.get("status")
            if situacao == "erro":
                falhas += 1
                continue
            i = envio["lancamento_id"]
            if situacao == "ok" and envio["operacao"] == "excluir":
                self.banco.confirmar_exclusao(envio)
            elif situacao == "ok":
                self.banco.confirmar_lancamento(envio, resposta["id"],
                                                resposta["updated_at"])
                if resposta["id"] != i:
                    trocas[i] = resposta["id"]
            elif dados is not None:
                servidor = sincro.versao_do_servidor(dados, i)
                if envio["operacao"] == "excluir":
                    if servidor is None:      # já tinha sido apagado lá
                        self.banco.confirmar_exclusao(envio)
                        continue
                elif servidor is not None and sincro.assinatura(
                        servidor) == sincro.assinatura(
                            envio["dados"], envio["itens"], envio["pagamentos"]):
                    # Reenvio de algo que já tinha chegado
                    self.banco.confirmar_lancamento(envio, i,
                                                    servidor["updated_at"])
                    continue
                frase = self._resolver_pela_hora(envio, servidor) \
                    if servidor is not None else None
                if frase:
                    frases.append(frase)
                    continue
                # Apagado lá não tem hora conhecida: quem decide é a pessoa
                self.banco.marcar_conflito(i, servidor)
                conflitos += 1
        return conflitos, falhas, trocas, frases

    def _aplicar_envios(self, resultados, dados):
        """Devolve as frases a falar. Marcação recusada só se decide com o
        download em mãos: sem ele, continua na fila."""
        frases, indecisas = [], 0
        for pendencia, novo_updated_at in resultados:
            i = pendencia["lancamento_id"]
            if novo_updated_at:
                self.banco.confirmar_envio(i, pendencia["pago"], novo_updated_at)
            elif dados is not None:
                servidor = sincro.versao_do_servidor(dados, i)
                # Valor igual: a conexão caiu depois de gravar e o reenvio foi
                # recusado. Apagado lá: não há o que marcar.
                if servidor is None or servidor["pago"] == bool(pendencia["pago"]):
                    self.banco.descartar_pendencia(i)
                    continue
                decisao = sincro.decidir(pendencia["editado_em"],
                                         servidor["updated_at"],
                                         nuvem_mod.defasagem())
                if decisao == sincro.LOCAL:
                    self.banco.rebasear_pago(i, servidor["updated_at"])
                    self._sincronizar_de_novo = True
                    continue
                self.banco.descartar_pendencia(i)
                if decisao == sincro.PERGUNTAR:
                    indecisas += 1
                else:
                    frases.append("Marcação de pago: valeu a alteração do "
                                  "servidor, a mais recente.")
        if indecisas:
            frases.append("%d marcação de pago não aplicada: não foi possível "
                          "comparar os horários. Vale o que está no servidor."
                          % indecisas)
        return frases

    @seguro
    def _atualizar_permissoes(self):
        """Guarda o que veio do painel e diz o que mudou."""
        novas = self.nuvem.permissoes if self.nuvem else None
        if not novas:
            return []
        antes = self.banco.permissoes()
        self.banco.salvar_permissoes(novas)
        frases = []
        for chave, nome in (("ia", "A leitura por IA"),
                            ("documentos", "O envio de documentos")):
            if antes.get(chave, True) != novas[chave]:
                frases.append("%s foi %s para a sua conta." % (
                    nome, "ativada" if novas[chave] and chave == "ia"
                    else "ativado" if novas[chave]
                    else "desativada" if chave == "ia" else "desativado"))
        return frases

    def _sincronizado(self, dados, erro, manual, resultados=(),
                      resultados_lanc=(), feitas=(), recusadas=()):
        self._sincronizando = False
        frases = self._aplicar_envios(resultados, dados)
        conflitos, falhas, trocas, frases_lanc = \
            self._aplicar_envios_de_lancamentos(resultados_lanc, dados)
        frases += frases_lanc
        frases[:0] = self._atualizar_permissoes()
        self.banco.concluir_operacoes([op["id"] for op in feitas])
        if recusadas:
            self.banco.concluir_operacoes([op["id"] for op in recusadas])
            n = len(recusadas)
            frases.append(
                "%d arquivo%s não enviado%s: o envio de documentos está desativado "
                "para a sua conta. A cópia continua na pasta %s." % (
                    n, "" if n == 1 else "s", "" if n == 1 else "s",
                    config.pasta_documentos()))
        self._ids_desenhados = [trocas.get(i, i) for i in self._ids_desenhados]
        if trocas:
            # O id temporário mudou: a cópia em memória precisa acompanhar
            self.todos = self.banco.lancamentos()
            self.visiveis = regras.filtrar(self.todos, self.filtros)
        if self._sincronizar_de_novo:
            self._sincronizar_de_novo = False
            wx.CallAfter(self._sincronizar, manual)
        if isinstance(erro, nuvem_mod.SemConexao):
            pendentes = self.banco.qtd_pendentes()
            texto = "Sem conexão. Mostrando a cópia local. " + self._texto_ultima()
            if pendentes:
                texto += " %d alteração%s aguardando envio." % (
                    pendentes, "" if pendentes == 1 else "ões")
            self.SetStatusText(texto)
            if manual:
                self._falar(texto, True)
            return
        if isinstance(erro, nuvem_mod.AcessoNegado):
            self.banco.apagar_tudo()
            documentos.apagar_copias()
            self.nuvem.sair()
            self.recarregar()
            wx.MessageBox("Seu acesso a este módulo está desativado. Fale com "
                          "o administrador.", "Acesso", wx.OK | wx.ICON_ERROR,
                          self)
            self._pedir_login()
            return
        if isinstance(erro, nuvem_mod.SessaoExpirada):
            self.nuvem.sair()
            self.avisar("A sessão expirou. Entre de novo.", True)
            self._pedir_login()
            return
        if erro:
            texto = "Não consegui sincronizar. Mostrando a cópia local."
            self.SetStatusText(texto)
            if manual:
                self._falar(texto, True)
            return
        antes = {l["id"]: sincro.assinatura(l) for l in self.banco.lancamentos()}
        agora = datetime.datetime.now().strftime("%d/%m/%Y às %H:%M")
        self.banco.substituir_tudo(dados, agora)
        self.recarregar()
        mudou = (len(antes) != len(self.todos) or any(
            antes.get(l["id"]) != sincro.assinatura(l) for l in self.todos))
        defasagem = nuvem_mod.defasagem()
        if defasagem is not None and not sincro.relogio_confiavel(defasagem):
            minutos = max(1, round(abs(defasagem) / 60))
            aviso = ("O relógio do computador está %d minuto%s %s do servidor. "
                     "Conflitos não serão decididos pela hora até ele ser "
                     "acertado." % (minutos, "" if minutos == 1 else "s",
                                    "atrás" if defasagem > 0 else "à frente"))
            if not self._avisou_relogio:
                self._avisou_relogio = True
                frases.insert(0, aviso)
        else:
            self._avisou_relogio = False
        if conflitos:
            frases.append(
                "%d conflito%s para resolver: Menu, Sincronização, Resolver "
                "conflitos." % (conflitos, "" if conflitos == 1 else "s"))
        if frases:
            self.avisar(" ".join(frases), True)
        if conflitos:
            return
        if falhas:
            self.avisar("O servidor recusou %d alteração%s. Ela%s segue%s "
                        "guardada%s neste computador. Veja em Menu, Sincronização, "
                        "Operações pendentes." % (
                            falhas, "" if falhas == 1 else "ões",
                            "" if falhas == 1 else "s",
                            "" if falhas == 1 else "m",
                            "" if falhas == 1 else "s"), manual)
            return
        if frases:
            return
        texto = ("Sincronizado, com novidades. " if mudou
                 else "Sincronizado, nada mudou. ") + self._qtd_texto()
        self.SetStatusText(texto)
        if manual or mudou:
            self._falar(texto, manual)

    def texto_desconectar(self):
        email = self.nuvem.email if self.nuvem else ""
        pendentes = self.banco.qtd_pendentes()
        partes = ["Desconectar a conta %s?" % email if email else "Desconectar?",
                  "Os dados locais serão apagados deste computador, inclusive os "
                  "documentos baixados. Os dados online continuam guardados."]
        if pendentes:
            partes.append("Atenção: %d alteração%s ainda não enviada%s será perdida%s." % (
                pendentes, "" if pendentes == 1 else "ões",
                "" if pendentes == 1 else "s", "" if pendentes == 1 else "s"))
        return "\n".join(partes)

    def _desconectar(self, _evt):
        resposta = wx.MessageBox(
            self.texto_desconectar(), "Desconectar",
            wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION, self)
        if resposta != wx.YES:
            return
        self._relogio.Stop()
        self.banco.apagar_tudo()
        documentos.apagar_copias()
        self.nuvem.sair()
        self.recarregar()
        self._pedir_login()

    # ---- atualização ----
    @seguro
    def _verificar_agora(self, _evt):
        self._verificar_atualizacao(True)

    def _verificar_atualizacao(self, manual):
        def trabalhar():
            try:
                lancamento, erro = atualizacao.consultar(), None
            except atualizacao.ErroAtualizacao as e:
                lancamento, erro = None, str(e)
            wx.CallAfter(self._consultou_atualizacao, lancamento, erro, manual)

        threading.Thread(target=trabalhar, daemon=True).start()

    @seguro
    def _consultou_atualizacao(self, lancamento, erro, manual):
        if manual:
            if erro or not atualizacao.e_mais_nova(lancamento):
                wx.MessageBox(erro or "Você já está com a versão mais recente: %s."
                              % __version__, "Verificar atualizações",
                              wx.OK | (wx.ICON_WARNING if erro
                                       else wx.ICON_INFORMATION), self)
                return
        elif erro or not atualizacao.deve_avisar_ao_abrir(
                lancamento, preferencias.ler_atualizacao_ignorada()):
            return
        self._oferecer_atualizacao(lancamento)

    def _oferecer_atualizacao(self, lancamento):
        # Não abre por cima de outra janela nem de um menu: tenta de novo depois
        if not self.IsEnabled() or self._menu_aberto:
            wx.CallLater(ESPERA_ATUALIZACAO_MS, self._oferecer_atualizacao, lancamento)
            return
        programa = config.pasta_do_programa()
        dlg = dialogos.NovaVersao(self, lancamento, bool(programa))
        quer = dlg.ShowModal() == wx.ID_OK
        if dlg.nao_avisar.GetValue():
            preferencias.salvar_atualizacao_ignorada(lancamento.versao)
        dlg.Destroy()
        if quer and programa:
            self._baixar_atualizacao(lancamento, programa)
        self.lista.SetFocus()

    def _baixar_atualizacao(self, lancamento, programa):
        barra = dialogos.Progresso(self, "Baixando a atualização", pode_cancelar=True)
        espera = atualizacao.pasta_de_espera(programa)

        def trabalho(progresso):
            arquivo = atualizacao.baixar(
                lancamento, espera,
                lambda feito, total: progresso(
                    feito * 100 / total if total else None, "Baixando"),
                cancelado=lambda: barra.cancelado)
            progresso(None, "Preparando")
            return atualizacao.extrair(arquivo, os.path.join(espera, "extraido"))

        nova = barra.executar(trabalho)
        erro, cancelado = barra.erro, barra.cancelado
        barra.Destroy()
        if cancelado:
            self.avisar("Atualização cancelada.", True)
        elif erro:
            wx.MessageBox(erro, "Atualização", wx.OK | wx.ICON_WARNING, self)
        else:
            atualizacao.aplicar(programa, nova)
            self.Close()

    def _ao_fechar(self, evt):
        self._relogio.Stop()
        try:
            preferencias.salvar_tela(self.filtros.tipo, self.filtros.status,
                                     self._linha_em_foco_id,
                                     max(self.navegacao.atual(), 0),
                                     self.navegacao.coluna_atual())
        except OSError as e:     # não pode impedir o programa de fechar
            log.warning("posição não guardada: %r", e)
        evt.Skip()
