"""Janelas auxiliares. Janela aberta de dentro de um diálogo tem esse diálogo
como dona; senão o leitor de tela diz "janela indisponível" ao fechar."""
import datetime

import wx

from financeiro import colunas, icones, regras
from financeiro.navegacao import NavegacaoDeColunas

CAMPOS_LISTA = (("banco", "Banco ou cartão"), ("forma", "Forma de pagamento"),
                ("estabelecimento", "Estabelecimento"),
                ("categoria", "Categoria"), ("credor", "Credor"),
                ("devedor", "Devedor"))
CAMPOS_DATA = (("origem_de", "Data de origem, de"),
               ("origem_ate", "Data de origem, até"),
               ("venc_de", "Vencimento, de"), ("venc_ate", "Vencimento, até"))
SITUACOES = (("", "Todos"), ("pendente", "Pendentes"), ("pago", "Pagos"))
TODOS = "Todos"


def data_para_iso(texto):
    """dd/mm/aaaa -> aaaa-mm-dd; vazio vale vazio; inválida levanta ValueError."""
    texto = texto.strip()
    if not texto:
        return ""
    return datetime.datetime.strptime(texto, "%d/%m/%Y").date().isoformat()


class Login(wx.Dialog):
    """Primeira tela sem sessão: entrar ou fechar o programa."""

    def __init__(self, pai):
        super().__init__(pai, title="Entrar no Controle Financeiro")
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(
            self, label="O login com Google abre no navegador do Windows."),
            0, wx.ALL, 12)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        entrar = wx.Button(self, wx.ID_OK, "Entrar com Google")
        entrar.SetDefault()
        botoes.Add(entrar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        entrar.SetFocus()


class Aguardando(wx.Dialog):
    """Fica aberta enquanto o navegador cuida do login."""

    def __init__(self, pai):
        super().__init__(pai, title="Aguardando o login no navegador")
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(
            self, label="Conclua o login no navegador. O programa continua "
                        "sozinho."), 0, wx.ALL, 12)
        cancelar = wx.Button(self, wx.ID_CANCEL, "Cancelar")
        caixa.Add(cancelar, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        cancelar.SetFocus()


class Detalhes(wx.Dialog):
    def __init__(self, pai, lanc, todos):
        super().__init__(pai, title="Detalhes: " + regras.descricao_de(lanc),
                         size=(560, 460),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        caixa = wx.BoxSizer(wx.VERTICAL)
        texto = wx.TextCtrl(self, value=texto_de_detalhes(lanc, todos),
                            name="Detalhes do lançamento",
                            style=wx.TE_MULTILINE | wx.TE_READONLY)
        caixa.Add(texto, 1, wx.EXPAND | wx.ALL, 8)
        fechar = wx.Button(self, wx.ID_CANCEL, "Fechar")
        caixa.Add(fechar, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        texto.SetFocus()


def texto_de_detalhes(lanc, todos):
    linhas = [
        "Tipo: " + ("Entrada" if regras.eh_entrada(lanc) else "Saída"),
        "Valor: " + regras.fmt_valor(lanc["valor"]),
        "Situação: " + ("Pago" if lanc["pago"] else "Pendente"),
        "Descrição: " + regras.descricao_de(lanc),
        "Data de origem: " + regras.fmt_data(lanc["data_origem"]),
        "Vencimento: " + regras.fmt_data(lanc["data_vencimento"]),
        "Banco ou cartão: " + (lanc["banco"] or "—"),
        "Forma de pagamento: " + (lanc["forma"] or "—"),
        "Parcela: " + (lanc["parcela"] or "À vista"),
        "Estabelecimento: " + (lanc["estabelecimento"] or "—"),
        "Categoria: " + (regras.categorias_de(lanc) or "—"),
        "Credor: " + (lanc["credor"] or "—"),
        "Devedor: " + (lanc["devedor"] or "—"),
    ]
    valores = regras.itens_orientados(lanc)
    for n, (item, valor) in enumerate(zip(lanc["itens"], valores), 1):
        linhas.append("Item %d de %d: %s, %s, %s" % (
            n, len(lanc["itens"]), item["descricao"] or "sem descrição",
            regras.fmt_valor(valor), item["categoria"] or "sem categoria"))
    for n, pag in enumerate(lanc["pagamentos"], 1):
        linhas.append("Pagamento %d de %d: %s, %s, %s" % (
            n, len(lanc["pagamentos"]), pag["banco"] or "sem banco",
            pag["forma"] or "sem forma", regras.fmt_valor(pag["valor"])))
    vinculado = lanc["lancamento_vinculado_id"]
    if vinculado:
        outro = next((l for l in todos if l["id"] == vinculado), None)
        if outro:
            linhas.append("Lançamento vinculado: %s, %s" % (
                regras.fmt_valor(outro["valor"]), outro["banco"] or "sem banco"))
    for doc in lanc["documentos"]:
        linhas.append("Documento: " + doc["nome_arquivo"])
    return "\n".join(linhas)


class Itens(wx.Dialog):
    """Lançamento com vários itens: uma tabela só dele."""

    def __init__(self, pai, lanc, todos):
        super().__init__(pai, title="Itens: " + cabecalho_itens(lanc),
                         size=(760, 460),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.lanc, self.todos = lanc, todos
        caixa = wx.BoxSizer(wx.VERTICAL)
        total = wx.StaticText(self, label=texto_total_itens(lanc))
        fonte = total.GetFont()
        fonte.MakeBold()
        total.SetFont(fonte)
        caixa.Add(total, 0, wx.ALL, 8)
        caixa.Add(wx.StaticText(self, label="Itens do lançamento"), 0, wx.LEFT, 8)
        self.lista = wx.ListCtrl(self, name="Itens do lançamento",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        self.imagens, indices = icones.lista_de_imagens()
        self.lista.SetImageList(self.imagens, wx.IMAGE_LIST_SMALL)
        for col, (titulo, largura, formato) in enumerate((
                ("Descrição", 320, wx.LIST_FORMAT_LEFT),
                ("Categoria", 150, wx.LIST_FORMAT_LEFT),
                ("Valor", 125, wx.LIST_FORMAT_RIGHT),
                ("Data original", 100, wx.LIST_FORMAT_LEFT))):
            self.lista.InsertColumn(col, titulo, width=largura, format=formato)
        contraste = icones.alto_contraste()
        valores = regras.itens_orientados(lanc)
        for n, (item, valor) in enumerate(zip(lanc["itens"], valores)):
            # Desconto vai na direção contrária à do lançamento
            entrada = (valor >= 0) == regras.eh_entrada(lanc)
            self.lista.InsertItem(n, item["descricao"] or "Sem descrição")
            self.lista.SetItem(n, 1, item["categoria"] or "Sem categoria")
            self.lista.SetItem(n, 2, regras.fmt_valor(valor),
                               indices["entrada" if entrada else "saida"])
            self.lista.SetItem(n, 3, regras.fmt_data(item["data_original"]))
            if not contraste:
                self.lista.SetItemTextColour(
                    n, icones.COR_ENTRADA if entrada else icones.COR_SAIDA)
        self.navegacao = NavegacaoDeColunas(self.lista, pai.fala)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        detalhes = wx.Button(self, label="Detalhes do lançamento")
        detalhes.Bind(wx.EVT_BUTTON, self._detalhes)
        botoes.Add(detalhes, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.lista.SetFocus()
        if lanc["itens"]:
            self.lista.Select(0)
            self.lista.Focus(0)

    def _detalhes(self, _evt):
        dlg = Detalhes(self, self.lanc, self.todos)
        dlg.ShowModal()
        dlg.Destroy()


def cabecalho_itens(lanc):
    return lanc["estabelecimento"] or lanc["descricao"] or (
        regras.fmt_data(lanc["data_origem"]))


def texto_total_itens(lanc):
    n = len(lanc["itens"])
    return "%d %s. Total do lançamento: %s. Soma dos itens: %s." % (
        n, "item" if n == 1 else "itens", regras.fmt_valor(lanc["valor"]),
        regras.fmt_valor(sum(regras.itens_orientados(lanc))))


class Configuracoes(wx.Dialog):
    """Cada assunto é uma aba; Ctrl+Tab passa de uma para outra."""

    def __init__(self, pai, escolha, fala, falar_quantidade=True,
                 falar_total=False):
        super().__init__(pai, title="Configurações", size=(520, 520),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.fala = fala
        # Situação é fixa na primeira coluna, por isso não entra nesta lista
        self.editaveis = [list(p) for p in escolha if p[0] != colunas.FIXA]
        self._preenchendo = False
        abas = wx.Notebook(self, name="Configurações")
        pagina = wx.Panel(abas)
        abas.AddPage(pagina, "Colunas da tabela")

        corpo = wx.BoxSizer(wx.VERTICAL)
        corpo.Add(wx.StaticText(pagina, label="Colunas a exibir"), 0,
                  wx.LEFT | wx.TOP, 8)
        self.lista = wx.ListCtrl(pagina, name="Colunas a exibir",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL
                                 | wx.LC_NO_HEADER)
        self.lista.EnableCheckBoxes(True)
        self.lista.InsertColumn(0, "", width=400)
        corpo.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        linha = wx.BoxSizer(wx.HORIZONTAL)
        for rotulo, acao in (("Mover para cima", lambda e: self._mover(-1)),
                             ("Mover para baixo", lambda e: self._mover(1)),
                             ("Restaurar padrão", self._restaurar)):
            botao = wx.Button(pagina, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, acao)
            linha.Add(botao, 0, wx.RIGHT, 8)
        corpo.Add(linha, 0, wx.LEFT | wx.BOTTOM, 8)
        pagina.SetSizer(corpo)

        pagina_filtros = wx.Panel(abas)
        abas.AddPage(pagina_filtros, "Filtros")
        self.falar_quantidade = wx.CheckBox(
            pagina_filtros,
            label="Falar a quantidade de lançamentos ao aplicar um filtro")
        self.falar_quantidade.SetValue(falar_quantidade)
        self.falar_total = wx.CheckBox(
            pagina_filtros, label="Falar o total em valores ao aplicar um filtro")
        self.falar_total.SetValue(falar_total)
        corpo_filtros = wx.BoxSizer(wx.VERTICAL)
        corpo_filtros.Add(self.falar_quantidade, 0, wx.ALL, 8)
        corpo_filtros.Add(self.falar_total, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM, 8)
        pagina_filtros.SetSizer(corpo_filtros)

        botoes = wx.BoxSizer(wx.HORIZONTAL)
        salvar = wx.Button(self, wx.ID_OK, "Salvar")
        salvar.SetDefault()
        salvar.Bind(wx.EVT_BUTTON, self._salvar)
        botoes.Add(salvar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(abas, 1, wx.EXPAND | wx.ALL, 8)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()

        self.lista.Bind(wx.EVT_LIST_ITEM_CHECKED, self._ao_marcar)
        self.lista.Bind(wx.EVT_LIST_ITEM_UNCHECKED, self._ao_marcar)
        self.Bind(wx.EVT_CHAR_HOOK, self._tecla)
        self._preencher(0)
        self.lista.SetFocus()

    @property
    def escolha(self):
        return [[colunas.FIXA, True]] + self.editaveis

    def _preencher(self, selecionar):
        self._preenchendo = True
        self.lista.DeleteAllItems()
        for n, (chave, visivel) in enumerate(self.editaveis):
            self.lista.InsertItem(n, colunas.POR_CHAVE[chave].titulo)
            self.lista.CheckItem(n, visivel)
        self.lista.Select(selecionar)
        self.lista.Focus(selecionar)
        self.lista.EnsureVisible(selecionar)
        self._preenchendo = False

    def _ao_marcar(self, evt):
        if not self._preenchendo:
            n = evt.GetIndex()
            self.editaveis[n][1] = self.lista.IsItemChecked(n)

    def _tecla(self, evt):
        """Alt com seta para cima ou para baixo move a coluna selecionada."""
        tecla = evt.GetKeyCode()
        if (evt.AltDown() and tecla in (wx.WXK_UP, wx.WXK_DOWN)
                and self.FindFocus() is self.lista):
            self._mover(-1 if tecla == wx.WXK_UP else 1)
        else:
            evt.Skip()

    def _mover(self, direcao):
        n = self.lista.GetFirstSelected()
        destino = n + direcao
        if n < 0:
            return
        titulo = colunas.POR_CHAVE[self.editaveis[n][0]].titulo
        if not 0 <= destino < len(self.editaveis):
            self.fala.falar("%s já está %s." % (
                titulo, "em primeiro" if direcao < 0 else "por último"), True)
            return
        self.editaveis[n], self.editaveis[destino] = (self.editaveis[destino],
                                                      self.editaveis[n])
        self._preencher(destino)
        self.fala.falar("%s, posição %d de %d." % (
            titulo, destino + 1, len(self.editaveis)), True)

    def _restaurar(self, _evt):
        self.editaveis = [p for p in colunas.padrao() if p[0] != colunas.FIXA]
        self._preencher(0)
        self.fala.falar("Colunas padrão restauradas. Salve para aplicar.", True)
        self.lista.SetFocus()

    def _salvar(self, _evt):
        if not any(v for _, v in self.editaveis):
            wx.MessageBox("Deixe pelo menos uma coluna marcada.",
                          "Configurações", wx.OK | wx.ICON_ERROR, self)
            self.lista.SetFocus()
            return
        self.EndModal(wx.ID_OK)


class BuscaPorCriterios(wx.Dialog):
    def __init__(self, pai, banco, filtros):
        super().__init__(pai, title="Busca por critérios")
        self.filtros = filtros
        grade = wx.FlexGridSizer(cols=2, vgap=6, hgap=8)
        grade.AddGrowableCol(1)
        self.datas, self.listas = {}, {}
        for chave, rotulo in CAMPOS_DATA:
            grade.Add(wx.StaticText(self, label=rotulo + " (dia/mês/ano)"),
                      0, wx.ALIGN_CENTER_VERTICAL)
            valor = regras.fmt_data(getattr(filtros, chave))
            campo = wx.TextCtrl(self, name=rotulo + " (dia/mês/ano)",
                                value="" if valor == "—" else valor)
            grade.Add(campo, 1, wx.EXPAND)
            self.datas[chave] = campo
        grade.Add(wx.StaticText(self, label="Situação"), 0,
                  wx.ALIGN_CENTER_VERTICAL)
        self.situacao = wx.Choice(self, name="Situação",
                                  choices=[r for _, r in SITUACOES])
        self.situacao.SetSelection(
            [k for k, _ in SITUACOES].index(filtros.status))
        grade.Add(self.situacao, 1, wx.EXPAND)
        for chave, rotulo in CAMPOS_LISTA:
            grade.Add(wx.StaticText(self, label=rotulo), 0,
                      wx.ALIGN_CENTER_VERTICAL)
            opcoes = banco.opcoes(chave)
            atual = getattr(filtros, chave)
            if atual and atual not in opcoes:
                opcoes.append(atual)
            escolha = wx.Choice(self, name=rotulo, choices=[TODOS] + opcoes)
            escolha.SetStringSelection(atual or TODOS)
            grade.Add(escolha, 1, wx.EXPAND)
            self.listas[chave] = escolha
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        aplicar = wx.Button(self, wx.ID_OK, "Aplicar")
        aplicar.SetDefault()
        limpar = wx.Button(self, label="Limpar critérios")
        botoes.Add(aplicar, 0, wx.RIGHT, 8)
        botoes.Add(limpar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))
        aplicar.Bind(wx.EVT_BUTTON, self._aplicar)
        limpar.Bind(wx.EVT_BUTTON, self._limpar)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.datas["origem_de"].SetFocus()

    def _limpar(self, _evt):
        for campo in self.datas.values():
            campo.Clear()
        self.situacao.SetSelection(0)
        for escolha in self.listas.values():
            escolha.SetSelection(0)
        self.datas["origem_de"].SetFocus()

    def _aplicar(self, _evt):
        novos = {}
        for chave, campo in self.datas.items():
            try:
                novos[chave] = data_para_iso(campo.GetValue())
            except ValueError:
                wx.MessageBox("Data inválida. Use dia/mês/ano, por exemplo "
                              "25/10/2026.", "Busca por critérios",
                              wx.OK | wx.ICON_ERROR, self)
                campo.SetFocus()
                return
        self.filtros.status = SITUACOES[self.situacao.GetSelection()][0]
        for chave, valor in novos.items():
            setattr(self.filtros, chave, valor)
        for chave, escolha in self.listas.items():
            valor = escolha.GetStringSelection()
            setattr(self.filtros, chave, "" if valor == TODOS else valor)
        self.EndModal(wx.ID_OK)


class Contas(wx.Dialog):
    """Saldos e limites. Enter mostra os lançamentos da conta na lista
    principal: é o mesmo conjunto usado no cálculo."""

    def __init__(self, pai, situacoes, ao_configurar=None, ao_remover=None):
        super().__init__(pai, title="Contas e cartões", size=(720, 380),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.situacoes = situacoes
        self.escolhida = None
        self.ao_configurar, self.ao_remover = ao_configurar, ao_remover
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(self, label="Contas e cartões"), 0,
                  wx.LEFT | wx.TOP, 8)
        self.lista = wx.ListCtrl(self, name="Contas e cartões",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        self.imagens, self.indices = icones.lista_de_imagens()
        self.lista.SetImageList(self.imagens, wx.IMAGE_LIST_SMALL)
        for col, (titulo, largura) in enumerate((
                ("Conta", 180), ("Tipo", 140), ("Situação", 370))):
            self.lista.InsertColumn(col, titulo, width=largura)
        self.navegacao = NavegacaoDeColunas(self.lista, pai.fala,
                                            ao_ativar=self._ver)
        self._preencher(situacoes)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        for rotulo, acao in (("Ver lançamentos desta conta", self._ver),
                             ("Configurar", self._configurar),
                             ("Remover configuração", self._remover)):
            botao = wx.Button(self, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            botoes.Add(botao, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, lambda e: self._ver())
        self.lista.SetFocus()
        if situacoes:
            self.lista.Select(0)
            self.lista.Focus(0)

    def _preencher(self, situacoes, selecionar=0):
        self.situacoes = situacoes
        self.lista.DeleteAllItems()
        contraste = icones.alto_contraste()
        for n, s in enumerate(situacoes):
            if s.tipo == "cartao_credito":
                tipo, img = "Cartão de crédito", self.indices[regras.CARTAO_CREDITO]
            elif s.tipo == "conta_corrente":
                tipo, img = "Conta corrente", self.indices["conta"]
            else:
                tipo, img = "Não configurada", self.indices[regras.SEM_FORMA]
            self.lista.InsertItem(n, s.nome, img)
            self.lista.SetItem(n, 1, tipo)
            self.lista.SetItem(n, 2, s.detalhe or "—")
            if s.alerta and not contraste:
                self.lista.SetItemTextColour(n, icones.COR_SAIDA)
        self.navegacao.definir_linha(-1)
        if situacoes:
            n = min(selecionar, len(situacoes) - 1)
            self.lista.Select(n)
            self.lista.Focus(n)
            self.navegacao.definir_linha(n)

    def _configurar(self):
        n = self.navegacao.atual()
        if n >= 0 and self.ao_configurar:
            self._preencher(self.ao_configurar(self, self.situacoes[n].nome), n)

    def _remover(self):
        n = self.navegacao.atual()
        if n >= 0 and self.ao_remover:
            self._preencher(self.ao_remover(self, self.situacoes[n]), n)

    def _ver(self):
        n = self.navegacao.atual()
        if n >= 0:
            self.escolhida = self.situacoes[n].nome
            self.EndModal(wx.ID_OK)
