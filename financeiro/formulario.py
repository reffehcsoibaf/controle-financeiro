"""Formulário de lançamento e de item. O rótulo nasce antes do controle, porque
o leitor de tela usa o texto que vem logo antes na ordem de criação."""
import datetime

import wx

from financeiro import regras
from financeiro.navegacao import NavegacaoDeColunas

TIPOS = ("Saída", "Entrada")
SEM_DATA = "—"


def _combo(pai, grade, rotulo, opcoes, valor=""):
    """Caixa com as listas cadastradas, em que também se pode digitar um texto
    avulso; esse texto não entra nas listas, como no "Outro" do site."""
    grade.Add(wx.StaticText(pai, label=rotulo), 0, wx.ALIGN_CENTER_VERTICAL)
    escolhas = list(opcoes)
    if valor and valor not in escolhas:
        escolhas.append(valor)
    combo = wx.ComboBox(pai, name=rotulo, choices=escolhas, value=valor or "",
                        style=wx.CB_DROPDOWN)
    grade.Add(combo, 1, wx.EXPAND)
    return combo


def _texto(pai, grade, rotulo, valor=""):
    grade.Add(wx.StaticText(pai, label=rotulo), 0, wx.ALIGN_CENTER_VERTICAL)
    campo = wx.TextCtrl(pai, name=rotulo, value=valor)
    grade.Add(campo, 1, wx.EXPAND)
    return campo


def _data_ou_vazio(iso):
    texto = regras.fmt_data(iso)
    return "" if texto == SEM_DATA else texto


def _ler_data(texto):
    texto = texto.strip()
    if not texto:
        return None
    return datetime.datetime.strptime(texto, "%d/%m/%Y").date().isoformat()


class DialogoItem(wx.Dialog):
    def __init__(self, pai, categorias, item=None):
        super().__init__(pai, title="Editar item" if item else "Novo item")
        item = item or {}
        self.resultado = None
        self.data_original = item.get("data_original")
        grade = wx.FlexGridSizer(cols=2, vgap=6, hgap=8)
        grade.AddGrowableCol(1)
        valor = regras.fmt_valor(item["valor"]) if "valor" in item else ""
        self.valor = _texto(self, grade, "Valor (negativo para desconto)", valor)
        self.categoria = _combo(self, grade, "Categoria", categorias,
                                item.get("categoria", ""))
        self.descricao = _texto(self, grade, "Descrição", item.get("descricao", ""))
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        ok = wx.Button(self, wx.ID_OK, "Salvar item")
        ok.SetDefault()
        ok.Bind(wx.EVT_BUTTON, self._salvar)
        botoes.Add(ok, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.SetSize((max(self.GetSize().width, 460), -1))
        self.valor.SetFocus()

    def _salvar(self, _evt):
        try:
            valor = regras.ler_valor(self.valor.GetValue())
        except ValueError:
            wx.MessageBox("Informe o valor do item em números, por exemplo "
                          "12,50.", "Item", wx.OK | wx.ICON_ERROR, self)
            self.valor.SetFocus()
            return
        self.resultado = dict(
            valor=valor, categoria=self.categoria.GetValue().strip(),
            descricao=self.descricao.GetValue().strip(),
            data_original=self.data_original)
        self.EndModal(wx.ID_OK)


class DialogoParcelar(wx.Dialog):
    """Valor total, número de parcelas e prévia. Quem cria é quem chamou."""

    MAXIMO = 360

    def __init__(self, pai, valor, entrada, vencimento_base):
        super().__init__(pai, title="Parcelar", size=(620, 560),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.vencimento_base = vencimento_base
        self.parcelas = None      # [(rótulo, vencimento, valor com sinal)]
        grade = wx.FlexGridSizer(cols=2, vgap=6, hgap=8)
        grade.AddGrowableCol(1)
        grade.Add(wx.StaticText(self, label="Tipo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.tipo = wx.Choice(self, name="Tipo", choices=list(TIPOS))
        self.tipo.SetSelection(1 if entrada else 0)
        grade.Add(self.tipo, 1, wx.EXPAND)
        self.valor = _texto(self, grade, "Valor total", valor)
        self.quantidade = _texto(self, grade, "Número de parcelas")
        grade.Add(wx.StaticText(self, label="Diferença de centavos"), 0,
                  wx.ALIGN_CENTER_VERTICAL)
        self.diferenca = wx.Choice(
            self, name="Diferença de centavos",
            choices=["Na última parcela", "Na primeira parcela"])
        self.diferenca.SetSelection(0)
        grade.Add(self.diferenca, 1, wx.EXPAND)
        # Campo de leitura, e não texto fixo, para o Tab chegar nele
        grade.Add(wx.StaticText(self, label="Resumo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.resumo = wx.TextCtrl(self, name="Resumo", style=wx.TE_READONLY)
        grade.Add(self.resumo, 1, wx.EXPAND)

        rotulo = wx.StaticText(self, label="Prévia das parcelas")
        self.lista = wx.ListCtrl(self, name="Prévia das parcelas",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        for col, (titulo, largura, formato) in enumerate((
                ("Parcela", 80, wx.LIST_FORMAT_LEFT),
                ("Vencimento", 110, wx.LIST_FORMAT_LEFT),
                ("Valor", 120, wx.LIST_FORMAT_RIGHT),
                ("Observação", 180, wx.LIST_FORMAT_LEFT))):
            self.lista.InsertColumn(col, titulo, width=largura, format=formato)
        self.navegacao = NavegacaoDeColunas(self.lista, pai.fala)

        botoes = wx.BoxSizer(wx.HORIZONTAL)
        criar = wx.Button(self, wx.ID_OK, "Criar lançamentos")
        criar.SetDefault()
        criar.Bind(wx.EVT_BUTTON, self._criar)
        botoes.Add(criar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(rotulo, 0, wx.LEFT, 12)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 12)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizer(caixa)
        self.Layout()
        for controle in (self.valor, self.quantidade):
            controle.Bind(wx.EVT_TEXT, lambda e: self.atualizar())
        for controle in (self.tipo, self.diferenca):
            controle.Bind(wx.EVT_CHOICE, lambda e: self.atualizar())
        self.atualizar()
        (self.quantidade if valor else self.valor).SetFocus()

    def _ler(self):
        """(total com sinal, quantidade) ou levanta ValueError com a mensagem."""
        try:
            total = abs(regras.ler_valor(self.valor.GetValue()))
        except ValueError:
            raise ValueError("Informe o valor total em números, por exemplo "
                             "1.200,00.") from None
        if not total:
            raise ValueError("O valor total precisa ser maior que zero.")
        try:
            quantidade = int(self.quantidade.GetValue().strip())
        except ValueError:
            raise ValueError("Informe o número de parcelas.") from None
        if not 2 <= quantidade <= self.MAXIMO:
            raise ValueError("O número de parcelas vai de 2 a %d." % self.MAXIMO)
        return total, quantidade

    def atualizar(self):
        self.lista.DeleteAllItems()
        self.navegacao.definir_linha(-1)
        self.parcelas = None
        try:
            total, quantidade = self._ler()
        except ValueError as e:
            self.resumo.ChangeValue(str(e))
            return
        na_primeira = self.diferenca.GetSelection() == 1
        entrada = self.tipo.GetSelection() == 1
        valores = regras.calcular_parcelas(total, quantidade, na_primeira)
        self.parcelas = [
            ("%d/%d" % (n + 1, quantidade),
             regras.avancar_meses(self.vencimento_base, n),
             regras.valor_com_tipo(v, entrada))
            for n, v in enumerate(valores)]
        ajuste = 0 if na_primeira else quantidade - 1
        base = valores[1 if na_primeira else 0]
        for n, (rotulo, vencimento, valor) in enumerate(self.parcelas):
            self.lista.InsertItem(n, rotulo)
            self.lista.SetItem(n, 1, regras.fmt_data(vencimento))
            self.lista.SetItem(n, 2, regras.fmt_valor_com_sinal(valor))
            if n == ajuste and valores[n] != base:
                self.lista.SetItem(n, 3, "Ajuste de centavos")
        texto = "%d parcelas de %s, total %s." % (
            quantidade, regras.fmt_valor(base), regras.fmt_valor(total))
        if valores[ajuste] != base:
            texto += " %s parcela: %s." % (
                "Primeira" if na_primeira else "Última",
                regras.fmt_valor(valores[ajuste]))
        self.resumo.ChangeValue(texto)
        self.lista.Select(0)
        self.lista.Focus(0)
        self.navegacao.definir_linha(0)

    def _criar(self, _evt):
        try:
            self._ler()
        except ValueError as e:
            wx.MessageBox(str(e), "Parcelar", wx.OK | wx.ICON_ERROR, self)
            self.valor.SetFocus()
            return
        self.EndModal(wx.ID_OK)


class Formulario(wx.Dialog):
    def __init__(self, pai, banco, lanc=None):
        super().__init__(pai, title="Editar lançamento" if lanc
                         else "Novo lançamento",
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.lanc = lanc
        self.fala = pai.fala
        self.resultado = None
        self.parcelas = None
        self.categorias = banco.opcoes("categoria")
        # Salvar um lançamento antigo grava os itens já no formato do site; o
        # total não muda, porque o sinal dele vem de Entrada ou Saída
        self.itens = [dict(valor=v, categoria=i["categoria"],
                           descricao=i["descricao"],
                           data_original=i["data_original"])
                      for i, v in zip(lanc["itens"], regras.itens_orientados(lanc))
                      ] if lanc else []
        dividido = bool(lanc and lanc["pagamentos"])

        grade = wx.FlexGridSizer(cols=2, vgap=6, hgap=8)
        grade.AddGrowableCol(1)
        l = lanc or {}
        grade.Add(wx.StaticText(self, label="Tipo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.tipo = wx.Choice(self, name="Tipo", choices=list(TIPOS))
        self.tipo.SetSelection(1 if lanc and lanc["valor"] >= 0 else 0)
        grade.Add(self.tipo, 1, wx.EXPAND)
        self.valor = _texto(self, grade, "Valor", regras.fmt_valor(
            abs(lanc["valor"])) if lanc else "")
        hoje = datetime.date.today().strftime("%d/%m/%Y")
        self.data_origem = _texto(
            self, grade, "Data de origem (dia/mês/ano)",
            _data_ou_vazio(l.get("data_origem")) if lanc else hoje)
        self.vencimento = _texto(self, grade, "Vencimento (dia/mês/ano)",
                                 _data_ou_vazio(l.get("data_vencimento")))
        self.banco = _combo(self, grade, "Banco ou cartão", banco.opcoes("banco"),
                            l.get("banco", ""))
        self.forma = _combo(self, grade, "Forma de pagamento",
                            banco.opcoes("forma"), l.get("forma", ""))
        self.estabelecimento = _combo(
            self, grade, "Estabelecimento", banco.opcoes("estabelecimento"),
            l.get("estabelecimento", ""))
        self.categoria = _combo(self, grade, "Categoria", self.categorias,
                                l.get("categoria", ""))
        self.credor = _combo(self, grade, "Credor", banco.opcoes("credor"),
                             l.get("credor", ""))
        self.devedor = _combo(self, grade, "Devedor", banco.opcoes("devedor"),
                              l.get("devedor", ""))
        grade.Add(wx.StaticText(self, label="Descrição"), 0, wx.ALIGN_TOP)
        self.descricao = wx.TextCtrl(self, name="Descrição", size=(-1, 54),
                                     value=l.get("descricao", ""),
                                     style=wx.TE_MULTILINE)
        grade.Add(self.descricao, 1, wx.EXPAND)
        grade.AddSpacer(0)
        self.pago = wx.CheckBox(self, label="Pago")
        self.pago.SetValue(bool(l.get("pago")))
        grade.Add(self.pago)
        if dividido:
            # Pagamento dividido ainda não se edita aqui, mas é preservado
            self.banco.Disable()
            self.forma.Disable()

        rotulo_itens = wx.StaticText(self, label="Itens do lançamento")
        self.lista = wx.ListCtrl(self, name="Itens do lançamento", size=(-1, 130),
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        for col, (titulo, largura, formato) in enumerate((
                ("Descrição", 250, wx.LIST_FORMAT_LEFT),
                ("Categoria", 150, wx.LIST_FORMAT_LEFT),
                ("Valor", 100, wx.LIST_FORMAT_RIGHT))):
            self.lista.InsertColumn(col, titulo, width=largura, format=formato)
        self.navegacao = NavegacaoDeColunas(
            self.lista, self.fala, ao_ativar=self._editar_item,
            ao_excluir=self._remover_item)
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED,
                        lambda e: self._editar_item())
        linha_itens = wx.BoxSizer(wx.HORIZONTAL)
        for rotulo, acao in (("Adicionar item", self._adicionar_item),
                             ("Editar item", self._editar_item),
                             ("Remover item", self._remover_item)):
            botao = wx.Button(self, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            linha_itens.Add(botao, 0, wx.RIGHT, 8)

        botoes = wx.BoxSizer(wx.HORIZONTAL)
        salvar = wx.Button(self, wx.ID_OK, "Salvar")
        salvar.Bind(wx.EVT_BUTTON, self._salvar)
        botoes.Add(salvar, 0, wx.RIGHT, 8)
        if not lanc:    # como no site, parcelar é só para lançamento novo
            parcelar = wx.Button(self, label="Parcelar")
            parcelar.Bind(wx.EVT_BUTTON, self._parcelar)
            botoes.Add(parcelar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))

        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(rotulo_itens, 0, wx.LEFT, 12)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT | wx.TOP, 12)
        caixa.Add(linha_itens, 0, wx.ALL, 12)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.SetSize((max(self.GetSize().width, 620), -1))
        self._preencher_itens(0)
        self.tipo.SetFocus()

    # ---- itens ----
    def _preencher_itens(self, selecionar=None):
        self.lista.DeleteAllItems()
        for n, item in enumerate(self.itens):
            self.lista.InsertItem(n, item["descricao"] or "Sem descrição")
            self.lista.SetItem(n, 1, item["categoria"] or "Sem categoria")
            self.lista.SetItem(n, 2, regras.fmt_valor(item["valor"]))
        self.navegacao.definir_linha(-1)
        if self.itens and selecionar is not None:
            n = min(selecionar, len(self.itens) - 1)
            self.lista.Select(n)
            self.lista.Focus(n)
            self.navegacao.definir_linha(n)
        # Com itens, o valor vem da soma, e descrição e categoria vêm deles
        tem_itens = bool(self.itens)
        self.valor.Enable(not tem_itens)
        self.descricao.Enable(not tem_itens)
        self.categoria.Enable(not tem_itens)
        if tem_itens:
            self.valor.ChangeValue(regras.fmt_valor(abs(self._soma_itens())))

    def _soma_itens(self):
        return round(sum(i["valor"] for i in self.itens), 2)

    def _falar_total(self, texto):
        self.fala.falar("%s Total dos itens: %s." % (
            texto, regras.fmt_valor(self._soma_itens())), True)

    def _adicionar_item(self):
        dlg = DialogoItem(self, self.categorias)
        if dlg.ShowModal() == wx.ID_OK:
            self.itens.append(dlg.resultado)
            self._preencher_itens(len(self.itens) - 1)
            self._falar_total("Item adicionado.")
        dlg.Destroy()

    def _editar_item(self):
        n = self.navegacao.atual()
        if not 0 <= n < len(self.itens):
            self.fala.falar("Nenhum item selecionado.", True)
            return
        dlg = DialogoItem(self, self.categorias, self.itens[n])
        if dlg.ShowModal() == wx.ID_OK:
            self.itens[n] = dlg.resultado
            self._preencher_itens(n)
            self._falar_total("Item alterado.")
        dlg.Destroy()

    def _remover_item(self):
        n = self.navegacao.atual()
        if not 0 <= n < len(self.itens):
            self.fala.falar("Nenhum item selecionado.", True)
            return
        del self.itens[n]
        self._preencher_itens(n)
        self._falar_total("Item removido.")

    # ---- salvar ----
    def _erro(self, texto, campo):
        wx.MessageBox(texto, self.GetTitle(), wx.OK | wx.ICON_ERROR, self)
        campo.SetFocus()

    def _ler_datas(self):
        """(origem, vencimento) ou None depois de avisar do erro."""
        try:
            origem = _ler_data(self.data_origem.GetValue())
        except ValueError:
            origem = ""
        if not origem:
            self._erro("Preencha a data de origem no formato dia/mês/ano, por "
                       "exemplo 25/10/2026.", self.data_origem)
            return None
        try:
            vencimento = _ler_data(self.vencimento.GetValue())
        except ValueError:
            self._erro("Vencimento inválido. Use dia/mês/ano ou deixe em branco.",
                       self.vencimento)
            return None
        return origem, vencimento

    def _parcelar(self, _evt):
        if self.itens:
            # As parcelas não levam itens: parcelar agora os perderia sem aviso
            self._erro("Um lançamento com itens não pode ser parcelado. Remova "
                       "os itens ou salve sem parcelar.", self.lista)
            return
        datas = self._ler_datas()
        if not datas:
            return
        origem, vencimento = datas
        base = vencimento or datetime.date.today().isoformat()
        dlg = DialogoParcelar(self, self.valor.GetValue().strip(),
                              self.tipo.GetSelection() == 1, base)
        if dlg.ShowModal() == wx.ID_OK:
            self.parcelas = self._montar_parcelas(dlg.parcelas, origem)
            self.EndModal(wx.ID_OK)
        dlg.Destroy()

    def _montar_parcelas(self, parcelas, origem):
        def texto(controle):
            return controle.GetValue().strip() or None

        descricao = texto(self.descricao)
        return [dict(
            data_origem=origem, data_vencimento=vencimento, valor=valor,
            banco=texto(self.banco), forma=texto(self.forma),
            estabelecimento=texto(self.estabelecimento),
            categoria=texto(self.categoria), credor=texto(self.credor),
            devedor=texto(self.devedor), parcela=rotulo, pago=False,
            descricao="%s — %s" % (descricao, rotulo) if descricao else rotulo)
            for rotulo, vencimento, valor in parcelas]

    def _salvar(self, _evt):
        datas = self._ler_datas()
        if not datas:
            return
        origem, vencimento = datas
        if self.itens:
            bruto = self._soma_itens()
        else:
            try:
                bruto = regras.ler_valor(self.valor.GetValue())
            except ValueError:
                self._erro("Informe o valor em números, por exemplo 12,50, ou "
                           "adicione itens.", self.valor)
                return
        entrada = self.tipo.GetSelection() == 1
        valor = regras.valor_com_tipo(bruto, entrada)
        if self.lanc and self.lanc["pagamentos"]:
            soma = sum(p["valor"] for p in self.lanc["pagamentos"])
            if abs(soma - abs(valor)) > 0.005:
                self._erro(
                    "Este lançamento tem pagamento dividido somando %s. O valor "
                    "precisa continuar igual a essa soma." % regras.fmt_valor(soma),
                    self.valor if not self.itens else self.lista)
                return

        def texto(controle):
            return controle.GetValue().strip() or None

        dividido = bool(self.lanc and self.lanc["pagamentos"])
        self.resultado = dict(
            data_origem=origem, data_vencimento=vencimento, valor=valor,
            banco=None if dividido else texto(self.banco),
            forma=None if dividido else texto(self.forma),
            estabelecimento=texto(self.estabelecimento),
            descricao=None if self.itens else texto(self.descricao),
            categoria=None if self.itens else texto(self.categoria),
            credor=texto(self.credor), devedor=texto(self.devedor),
            pago=self.pago.GetValue())
        self.EndModal(wx.ID_OK)


class ResolverConflito(wx.Dialog):
    """Mostra só o que difere. Fecha com ID_YES (manter a versão deste
    computador), ID_NO (usar a do servidor) ou ID_CANCEL (decidir depois)."""

    def __init__(self, pai, linhas, explicacao, rotulo_manter):
        super().__init__(pai, title="Conflito de sincronização", size=(760, 420),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(self, label=explicacao), 0, wx.ALL, 8)
        caixa.Add(wx.StaticText(self, label="Diferenças"), 0, wx.LEFT, 8)
        self.lista = wx.ListCtrl(self, name="Diferenças",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        for col, (titulo, largura) in enumerate((
                ("Campo", 150), ("Neste computador", 280), ("No servidor", 280))):
            self.lista.InsertColumn(col, titulo, width=largura)
        for n, linha in enumerate(linhas):
            self.lista.InsertItem(n, linha[0])
            self.lista.SetItem(n, 1, linha[1])
            self.lista.SetItem(n, 2, linha[2])
        self.navegacao = NavegacaoDeColunas(self.lista, pai.fala)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        for ident, rotulo in ((wx.ID_YES, rotulo_manter),
                              (wx.ID_NO, "Usar a versão do servidor")):
            botao = wx.Button(self, ident, rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, i=ident: self.EndModal(i))
            botoes.Add(botao, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Decidir depois"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.lista.SetFocus()
        if linhas:
            self.lista.Select(0)
            self.lista.Focus(0)
