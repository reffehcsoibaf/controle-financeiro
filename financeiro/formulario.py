"""Formulário de lançamento, em abas. O rótulo nasce antes do controle, porque o
leitor de tela usa o texto que vem logo antes na ordem de criação."""
import datetime
import logging
import os
import re
import threading

import wx

from financeiro import config, documentos, nuvem as nuvem_mod, regras
from financeiro.campo_data import CampoData
from financeiro.navegacao import NavegacaoDeColunas

log = logging.getLogger("financeiro.formulario")

TIPOS = ("Saída", "Entrada")
SEM_DATA = "—"
NAO_VINCULAR = "Não vincular"
CAMPOS_IA = (("banco", "banco", "Banco ou cartão"),
             ("estabelecimento", "estabelecimento", "Estabelecimento"),
             ("forma_pagamento", "forma", "Forma de pagamento"),
             ("categoria_sugerida", "categoria", "Categoria"),
             ("credor", "credor", "Credor"), ("devedor", "devedor", "Devedor"))


def executar_com_espera(pai, titulo, funcao):
    """Roda `funcao` fora da janela, com um aviso aberto enquanto isso.
    Devolve (resultado, erro)."""
    espera = wx.Dialog(pai, title=titulo)
    caixa = wx.BoxSizer(wx.VERTICAL)
    caixa.Add(wx.StaticText(espera, label=titulo), 0, wx.ALL, 16)
    espera.SetSizerAndFit(caixa)
    saida = {}

    def trabalhar():
        try:
            saida["resultado"] = funcao()
        except Exception as e:
            saida["erro"] = e
        wx.CallAfter(espera.EndModal, wx.ID_OK)

    threading.Thread(target=trabalhar, daemon=True).start()
    espera.ShowModal()
    espera.Destroy()
    return saida.get("resultado"), saida.get("erro")


def mensagem_de_erro(erro):
    if isinstance(erro, nuvem_mod.SemConexao):
        return "Esta função precisa de internet, e não há conexão agora."
    if isinstance(erro, nuvem_mod.SessaoExpirada):
        return "A sessão expirou. Saia e entre de novo."
    return str(erro) or "Ocorreu um erro."


def arquivo_colado():
    """O arquivo ou a imagem copiados, ou None. Copiar célula de planilha também
    traz imagem junto com o texto: nesse caso vale o texto, como colar normal."""
    if not wx.TheClipboard.Open():
        return None
    try:
        if wx.TheClipboard.IsSupported(wx.DataFormat(wx.DF_FILENAME)):
            dados = wx.FileDataObject()
            if wx.TheClipboard.GetData(dados) and dados.GetFilenames():
                return dados.GetFilenames()[0]
        if (wx.TheClipboard.IsSupported(wx.DataFormat(wx.DF_BITMAP))
                and not wx.TheClipboard.IsSupported(wx.DataFormat(wx.DF_UNICODETEXT))):
            dados = wx.BitmapDataObject()
            if wx.TheClipboard.GetData(dados):
                nome = "colado-%s.png" % datetime.datetime.now().strftime("%Y%m%d%H%M%S")
                caminho = os.path.join(config.pasta_documentos(), nome)
                dados.GetBitmap().SaveFile(caminho, wx.BITMAP_TYPE_PNG)
                return caminho
    finally:
        wx.TheClipboard.Close()
    return None


def ligar_colar(dialogo, ao_colar):
    """Ctrl+V com arquivo copiado vale em qualquer lugar da janela; colar texto
    segue normal."""
    def tecla(evt):
        if evt.GetKeyCode() == ord("V") and evt.GetModifiers() == wx.MOD_CONTROL:
            caminho = arquivo_colado()
            if caminho:
                ao_colar(caminho)
                return
        evt.Skip()
    dialogo.Bind(wx.EVT_CHAR_HOOK, tecla)


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


def _grade():
    grade = wx.FlexGridSizer(cols=2, vgap=6, hgap=8)
    grade.AddGrowableCol(1)
    return grade


def _botoes_ok(dlg, rotulo, acao):
    botoes = wx.BoxSizer(wx.HORIZONTAL)
    ok = wx.Button(dlg, wx.ID_OK, rotulo)
    ok.SetDefault()
    ok.Bind(wx.EVT_BUTTON, acao)
    botoes.Add(ok, 0, wx.RIGHT, 8)
    botoes.Add(wx.Button(dlg, wx.ID_CANCEL, "Cancelar"))
    return botoes


def _campo_data(pai, grade, rotulo, valor=""):
    grade.Add(wx.StaticText(pai, label=rotulo), 0, wx.ALIGN_CENTER_VERTICAL)
    campo = CampoData(pai, rotulo, valor)
    grade.Add(campo, 1, wx.EXPAND)
    return campo


def _data_ou_vazio(iso):
    texto = regras.fmt_data(iso)
    return "" if texto == SEM_DATA else texto


_ler_data = regras.ler_data


class DialogoItem(wx.Dialog):
    def __init__(self, pai, categorias, item=None, limite=None):
        super().__init__(pai, title="Editar item" if item else "Novo item")
        item = item or {}
        self.resultado = None
        self.original = item
        self.limite = limite
        grade = _grade()
        valor = regras.fmt_valor(item["valor"]) if "valor" in item else ""
        self.valor = _texto(self, grade, "Valor (negativo para desconto)", valor)
        self.categoria = _combo(self, grade, "Categoria", categorias,
                                item.get("categoria", ""))
        self.descricao = _texto(self, grade, "Descrição", item.get("descricao", ""))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(_botoes_ok(self, "Salvar item", self._salvar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
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
        # Lançamento vindo de um documento: os itens não passam do valor dele
        if self.limite is not None and abs(valor) > self.limite + 0.005:
            wx.MessageBox("A soma dos itens não pode passar do valor do documento. "
                          "Máximo para este item: %s." % regras.fmt_valor(self.limite),
                          "Item", wx.OK | wx.ICON_ERROR, self)
            self.valor.SetFocus()
            return
        self.resultado = dict(
            self.original, valor=valor,
            categoria=self.categoria.GetValue().strip(),
            descricao=self.descricao.GetValue().strip(),
            data_original=self.original.get("data_original"))
        self.EndModal(wx.ID_OK)


class DialogoPagamento(wx.Dialog):
    """Uma parte de um pagamento dividido."""

    def __init__(self, pai, banco, pagamento=None):
        super().__init__(pai, title="Editar pagamento" if pagamento
                         else "Novo pagamento")
        p = pagamento or {}
        self.resultado = None
        grade = _grade()
        self.banco = _combo(self, grade, "Banco ou cartão", banco.opcoes("banco"),
                            p.get("banco", ""))
        self.forma = _combo(self, grade, "Forma de pagamento",
                            banco.opcoes("forma"), p.get("forma", ""))
        self.valor = _texto(self, grade, "Valor",
                            regras.fmt_valor(p["valor"]) if "valor" in p else "")
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(_botoes_ok(self, "Salvar pagamento", self._salvar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.SetSize((max(self.GetSize().width, 460), -1))
        self.banco.SetFocus()

    def _erro(self, texto, campo):
        wx.MessageBox(texto, "Pagamento", wx.OK | wx.ICON_ERROR, self)
        campo.SetFocus()

    def _salvar(self, _evt):
        banco = self.banco.GetValue().strip()
        if not banco:
            self._erro("Escolha o banco ou cartão.", self.banco)
            return
        try:
            valor = regras.ler_valor(self.valor.GetValue())
        except ValueError:
            valor = 0
        if valor <= 0:
            self._erro("Informe um valor maior que zero.", self.valor)
            return
        self.resultado = dict(banco=banco, forma=self.forma.GetValue().strip() or None,
                              valor=valor)
        self.EndModal(wx.ID_OK)


class DialogoParcelar(wx.Dialog):
    """Valor total, número de parcelas e prévia. Quem cria é quem chamou."""

    MAXIMO = 360

    def __init__(self, pai, valor, entrada, vencimento_base, quantidade_itens=0,
                 quantidade_pagamentos=0):
        super().__init__(pai, title="Parcelar", size=(620, 560),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.vencimento_base = vencimento_base
        self.quantidade_itens = quantidade_itens
        self.quantidade_pagamentos = quantidade_pagamentos
        self.parcelas = None      # [(rótulo, vencimento, valor com sinal)]
        grade = _grade()
        grade.Add(wx.StaticText(self, label="Tipo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.tipo = wx.Choice(self, name="Tipo", choices=list(TIPOS))
        self.tipo.SetSelection(1 if entrada else 0)
        grade.Add(self.tipo, 1, wx.EXPAND)
        self.valor = _texto(self, grade, "Valor total", valor)
        if quantidade_itens or quantidade_pagamentos:
            self.valor.SetEditable(False)   # o total já foi conferido com a soma
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

        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(rotulo, 0, wx.LEFT, 12)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 12)
        caixa.Add(_botoes_ok(self, "Criar lançamentos", self._criar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
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
        if self.quantidade_itens:
            texto += " Cada parcela leva os %d itens, em valores proporcionais." % (
                self.quantidade_itens)
        if self.quantidade_pagamentos:
            texto += " O pagamento segue dividido em %d formas em cada parcela." % (
                self.quantidade_pagamentos)
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


class ImportarLancamentos(wx.Dialog):
    """Escolhe lançamentos que viram itens deste. Só filtra e marca; quem usa o
    resultado é o formulário."""

    def __init__(self, pai, todos, excluir_ids):
        super().__init__(pai, title="Importar lançamentos como itens",
                         size=(760, 620),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.todos = [l for l in todos if l["id"] not in excluir_ids]
        self.visiveis, self.marcados = [], set()
        self.selecionados = []
        self._preenchendo = False
        grade = _grade()
        self.busca = _texto(self, grade, "Buscar")
        self.origem_de = _campo_data(self, grade, "Data de origem, de")
        self.origem_ate = _campo_data(self, grade, "Data de origem, até")
        self.venc_de = _campo_data(self, grade, "Vencimento, de")
        self.venc_ate = _campo_data(self, grade, "Vencimento, até")
        self.credor = _texto(self, grade, "Credor")
        self.devedor = _texto(self, grade, "Devedor")
        grade.AddSpacer(0)
        self.incluir_pagos = wx.CheckBox(self, label="Incluir lançamentos já pagos")
        grade.Add(self.incluir_pagos)
        rotulo = wx.StaticText(self, label="Lançamentos")
        self.lista = wx.ListCtrl(self, name="Lançamentos",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        self.lista.EnableCheckBoxes(True)
        for col, (titulo, largura, formato) in enumerate((
                ("Descrição", 300, wx.LIST_FORMAT_LEFT),
                ("Valor", 110, wx.LIST_FORMAT_RIGHT),
                ("Data de origem", 105, wx.LIST_FORMAT_LEFT),
                ("Situação", 85, wx.LIST_FORMAT_LEFT),
                ("Credor", 130, wx.LIST_FORMAT_LEFT))):
            self.lista.InsertColumn(col, titulo, width=largura, format=formato)
        self.navegacao = NavegacaoDeColunas(self.lista, pai.fala,
                                            ao_espaco=self._alternar)
        self.lista.Bind(wx.EVT_LIST_ITEM_CHECKED, self._ao_marcar)
        self.lista.Bind(wx.EVT_LIST_ITEM_UNCHECKED, self._ao_marcar)
        linha_total = _grade()
        self.total = _texto(self, linha_total, "Selecionados")
        self.total.SetEditable(False)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(rotulo, 0, wx.LEFT, 12)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 12)
        caixa.Add(linha_total, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(_botoes_ok(self, "Importar", self._importar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizer(caixa)
        self.Layout()
        for campo in (self.busca, self.origem_de, self.origem_ate, self.venc_de,
                      self.venc_ate, self.credor, self.devedor):
            campo.Bind(wx.EVT_TEXT, lambda e: self.atualizar())
        self.incluir_pagos.Bind(wx.EVT_CHECKBOX, lambda e: self.atualizar())
        self.atualizar()
        self.busca.SetFocus()

    @staticmethod
    def _data_filtro(campo):
        """Data incompleta enquanto se digita vale como vazia."""
        try:
            return _ler_data(campo.GetValue()) or ""
        except ValueError:
            return ""

    def _filtrar(self):
        termo = regras.sem_acentos(self.busca.GetValue()).strip()
        origem_de, origem_ate = (self._data_filtro(self.origem_de),
                                 self._data_filtro(self.origem_ate))
        venc_de, venc_ate = (self._data_filtro(self.venc_de),
                             self._data_filtro(self.venc_ate))
        credor = regras.sem_acentos(self.credor.GetValue()).strip()
        devedor = regras.sem_acentos(self.devedor.GetValue()).strip()
        saida = []
        for l in self.todos:
            origem, venc = l["data_origem"] or "", l["data_vencimento"] or ""
            if not self.incluir_pagos.GetValue() and l["pago"]:
                continue
            if (origem_de and (not origem or origem < origem_de)) or (
                    origem_ate and (not origem or origem > origem_ate)):
                continue
            if (venc_de and (not venc or venc < venc_de)) or (
                    venc_ate and (not venc or venc > venc_ate)):
                continue
            if credor and credor not in regras.sem_acentos(l["credor"]):
                continue
            if devedor and devedor not in regras.sem_acentos(l["devedor"]):
                continue
            alvo = regras.sem_acentos(" ".join((
                l["credor"], l["devedor"], l["descricao"], l["banco"],
                l["estabelecimento"], l["categoria"])))
            if termo and termo not in alvo:
                continue
            saida.append(l)
        saida.sort(key=lambda l: l["data_origem"] or "", reverse=True)
        return saida

    def atualizar(self):
        self.visiveis = self._filtrar()
        self._preenchendo = True
        self.lista.DeleteAllItems()
        for n, l in enumerate(self.visiveis):
            self.lista.InsertItem(n, regras.descricao_de(l))
            self.lista.SetItem(n, 1, regras.fmt_valor_com_sinal(l["valor"]))
            self.lista.SetItem(n, 2, regras.fmt_data(l["data_origem"]))
            self.lista.SetItem(n, 3, "Pago" if l["pago"] else "Pendente")
            self.lista.SetItem(n, 4, l["credor"] or "—")
            self.lista.CheckItem(n, l["id"] in self.marcados)
        self._preenchendo = False
        self.navegacao.definir_linha(-1)
        if self.visiveis:
            self.lista.Select(0)
            self.lista.Focus(0)
            self.navegacao.definir_linha(0)
        self._atualizar_total()

    def _alternar(self):
        n = self.navegacao.atual()
        if 0 <= n < len(self.visiveis):
            self._preenchendo = True
            self.lista.CheckItem(n, not self.lista.IsItemChecked(n))
            self._preenchendo = False
            self._registrar(n)

    def _ao_marcar(self, evt):
        if not self._preenchendo:
            self._registrar(evt.GetIndex())

    def _registrar(self, n):
        i = self.visiveis[n]["id"]
        if self.lista.IsItemChecked(n):
            self.marcados.add(i)
        else:
            self.marcados.discard(i)
        self._atualizar_total()
        self.navegacao.fala.falar(self.total.GetValue(), True)

    def _atualizar_total(self):
        escolhidos = [l for l in self.todos if l["id"] in self.marcados]
        total = sum(abs(l["valor"]) for l in escolhidos)
        n = len(escolhidos)
        self.total.ChangeValue("%d selecionado%s%s" % (
            n, "" if n == 1 else "s",
            ", total %s." % regras.fmt_valor(total) if n else "."))

    def _importar(self, _evt):
        self.selecionados = [l for l in self.todos if l["id"] in self.marcados]
        if not self.selecionados:
            wx.MessageBox("Marque ao menos um lançamento com a barra de espaço.",
                          self.GetTitle(), wx.OK | wx.ICON_ERROR, self)
            self.lista.SetFocus()
            return
        self.EndModal(wx.ID_OK)


class DialogoTextoIA(wx.Dialog):
    def __init__(self, pai):
        super().__init__(pai, title="Ler texto com IA", size=(560, 360),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(self, label="Texto a analisar"), 0, wx.ALL, 8)
        self.texto = wx.TextCtrl(self, name="Texto a analisar",
                                 style=wx.TE_MULTILINE)
        caixa.Add(self.texto, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 8)
        caixa.Add(_botoes_ok(self, "Analisar", lambda e: self.EndModal(wx.ID_OK)), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.texto.SetFocus()


class Formulario(wx.Dialog):
    def __init__(self, pai, banco, lanc=None, nuvem=None, todos=()):
        super().__init__(pai, title="Editar lançamento" if lanc
                         else "Novo lançamento", size=(700, 680),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.lanc, self.banco_dados, self.nuvem = lanc, banco, nuvem
        self.todos = todos
        self.fala = pai.fala
        self.resultado = None
        self.parcelas = None
        self.documento_id = None
        self.extracao_ia = None
        self.valor_documento = None
        self.categorias = banco.opcoes("categoria")
        # Salvar um lançamento antigo grava os itens já no formato do site; o
        # total não muda, porque o sinal dele vem de Entrada ou Saída
        self.itens = [dict(valor=v, categoria=i["categoria"],
                           descricao=i["descricao"],
                           data_original=i["data_original"])
                      for i, v in zip(lanc["itens"], regras.itens_orientados(lanc))
                      ] if lanc else []
        self.pagamentos = [dict(banco=p["banco"], forma=p["forma"] or None,
                                valor=p["valor"])
                           for p in lanc["pagamentos"]] if lanc else []

        self.abas = wx.Notebook(self, name="Seções do lançamento")
        self._criar_aba_dados(lanc or {})
        self._criar_aba_itens()
        self._criar_aba_pagamentos()

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
        caixa.Add(self.abas, 1, wx.EXPAND | wx.ALL, 8)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.dividir.SetValue(bool(self.pagamentos))
        self._preencher_pagamentos(0)
        self._preencher_itens(0)
        self._atualizar_fatura()
        self.tipo.SetFocus()

    # ---- montagem ----
    def _criar_aba_dados(self, l):
        p = wx.Panel(self.abas)
        self.abas.AddPage(p, "Dados")
        banco = self.banco_dados
        grade = _grade()
        grade.Add(wx.StaticText(p, label="Tipo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.tipo = wx.Choice(p, name="Tipo", choices=list(TIPOS))
        self.tipo.SetSelection(1 if l and l["valor"] >= 0 else 0)
        grade.Add(self.tipo, 1, wx.EXPAND)
        self.valor = _texto(p, grade, "Valor", regras.fmt_valor(
            abs(l["valor"])) if l else "")
        hoje = datetime.date.today().strftime("%d/%m/%Y")
        self.data_origem = _campo_data(
            p, grade, "Data de origem",
            _data_ou_vazio(l.get("data_origem")) if l else hoje)
        self.vencimento = _campo_data(p, grade, "Vencimento",
                                      _data_ou_vazio(l.get("data_vencimento")))
        self.campo_banco = _combo(p, grade, "Banco ou cartão", banco.opcoes("banco"),
                                  l.get("banco", ""))
        self.forma = _combo(p, grade, "Forma de pagamento", banco.opcoes("forma"),
                            l.get("forma", ""))
        self.estabelecimento = _combo(
            p, grade, "Estabelecimento", banco.opcoes("estabelecimento"),
            l.get("estabelecimento", ""))
        self.categoria = _combo(p, grade, "Categoria", self.categorias,
                                l.get("categoria", ""))
        grade.Add(wx.StaticText(p, label="Cartão a quitar"), 0,
                  wx.ALIGN_CENTER_VERTICAL)
        self.cartao = wx.Choice(p, name="Cartão a quitar", choices=[NAO_VINCULAR])
        self.cartao.SetSelection(0)
        grade.Add(self.cartao, 1, wx.EXPAND)
        self.credor = _combo(p, grade, "Credor", banco.opcoes("credor"),
                             l.get("credor", ""))
        self.devedor = _combo(p, grade, "Devedor", banco.opcoes("devedor"),
                              l.get("devedor", ""))
        grade.Add(wx.StaticText(p, label="Descrição"), 0, wx.ALIGN_TOP)
        self.descricao = wx.TextCtrl(p, name="Descrição", size=(-1, 54),
                                     value=l.get("descricao", ""),
                                     style=wx.TE_MULTILINE)
        grade.Add(self.descricao, 1, wx.EXPAND)
        grade.AddSpacer(0)
        self.pago = wx.CheckBox(p, label="Pago")
        self.pago.SetValue(bool(l.get("pago")))
        grade.Add(self.pago)
        linha = wx.BoxSizer(wx.HORIZONTAL)
        permissoes = banco.permissoes()
        self.guardar_arquivo = None
        if self.nuvem and permissoes["ia"]:
            ler = wx.Button(p, label="Ler documento com IA")
            ler.Bind(wx.EVT_BUTTON, self._ler_arquivo)
            linha.Add(ler, 0, wx.RIGHT, 8)
            ler_texto = wx.Button(p, label="Ler texto com IA")
            ler_texto.Bind(wx.EVT_BUTTON, self._ler_texto)
            linha.Add(ler_texto, 0, wx.RIGHT, 8)
            if permissoes["documentos"]:
                self.guardar_arquivo = wx.CheckBox(
                    p, label="Guardar também o arquivo lido")
                self.guardar_arquivo.SetValue(True)
                linha.Add(self.guardar_arquivo, 0, wx.ALIGN_CENTER_VERTICAL | wx.RIGHT, 8)
            self.botao_nao_vincular = wx.Button(p, label="Não vincular o documento")
            self.botao_nao_vincular.Bind(wx.EVT_BUTTON, self._nao_vincular)
            self.botao_nao_vincular.Disable()
            linha.Add(self.botao_nao_vincular)
            ligar_colar(self, self._colar)
        cadastrar = wx.Button(p, label="Cadastrar textos novos nas listas")
        cadastrar.Bind(wx.EVT_BUTTON, self._cadastrar_novos)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 12)
        caixa.Add(linha, 0, wx.LEFT | wx.BOTTOM, 12)
        caixa.Add(cadastrar, 0, wx.LEFT | wx.BOTTOM, 12)
        p.SetSizer(caixa)
        self.categoria.Bind(wx.EVT_TEXT, lambda e: self._atualizar_fatura())
        self.campo_banco.Bind(wx.EVT_TEXT, lambda e: self._atualizar_fatura())

    def _criar_aba_itens(self):
        p = wx.Panel(self.abas)
        self.abas.AddPage(p, "Itens")
        rotulo = wx.StaticText(p, label="Itens do lançamento")
        self.lista = wx.ListCtrl(p, name="Itens do lançamento",
                                 style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        for col, (titulo, largura, formato) in enumerate((
                ("Descrição", 260, wx.LIST_FORMAT_LEFT),
                ("Categoria", 150, wx.LIST_FORMAT_LEFT),
                ("Valor", 100, wx.LIST_FORMAT_RIGHT),
                ("Data original", 105, wx.LIST_FORMAT_LEFT))):
            self.lista.InsertColumn(col, titulo, width=largura, format=formato)
        self.navegacao = NavegacaoDeColunas(
            self.lista, self.fala, ao_ativar=self._editar_item,
            ao_excluir=self._remover_item)
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, lambda e: self._editar_item())
        linha = wx.BoxSizer(wx.HORIZONTAL)
        for texto, acao in (("Adicionar item", self._adicionar_item),
                            ("Editar item", self._editar_item),
                            ("Remover item", self._remover_item),
                            ("Importar lançamentos", self._importar)):
            botao = wx.Button(p, label=texto)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            linha.Add(botao, 0, wx.RIGHT, 8)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(rotulo, 0, wx.ALL, 12)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 12)
        caixa.Add(linha, 0, wx.ALL, 12)
        p.SetSizer(caixa)

    def _criar_aba_pagamentos(self):
        p = wx.Panel(self.abas)
        self.abas.AddPage(p, "Pagamento dividido")
        self.dividir = wx.CheckBox(p, label="Dividir pagamento")
        self.dividir.Bind(wx.EVT_CHECKBOX, lambda e: self._preencher_pagamentos(0))
        rotulo = wx.StaticText(p, label="Formas de pagamento")
        self.lista_pag = wx.ListCtrl(p, name="Formas de pagamento",
                                     style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
        for col, (titulo, largura, formato) in enumerate((
                ("Banco ou cartão", 230, wx.LIST_FORMAT_LEFT),
                ("Forma", 180, wx.LIST_FORMAT_LEFT),
                ("Valor", 100, wx.LIST_FORMAT_RIGHT))):
            self.lista_pag.InsertColumn(col, titulo, width=largura, format=formato)
        self.nav_pag = NavegacaoDeColunas(
            self.lista_pag, self.fala, ao_ativar=self._editar_pagamento,
            ao_excluir=self._remover_pagamento)
        self.lista_pag.Bind(wx.EVT_LIST_ITEM_ACTIVATED,
                            lambda e: self._editar_pagamento())
        self.botoes_pag = []
        linha = wx.BoxSizer(wx.HORIZONTAL)
        for texto, acao in (("Adicionar pagamento", self._adicionar_pagamento),
                            ("Editar pagamento", self._editar_pagamento),
                            ("Remover pagamento", self._remover_pagamento)):
            botao = wx.Button(p, label=texto)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            linha.Add(botao, 0, wx.RIGHT, 8)
            self.botoes_pag.append(botao)
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(self.dividir, 0, wx.ALL, 12)
        caixa.Add(rotulo, 0, wx.LEFT, 12)
        caixa.Add(self.lista_pag, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 12)
        caixa.Add(linha, 0, wx.ALL, 12)
        p.SetSizer(caixa)

    # ---- fatura vinculada ----
    def _atualizar_fatura(self):
        """Categoria "Cartão de crédito" num lançamento novo oferece o cartão a
        quitar; com um só cartão, já vem escolhido, como no site."""
        if self.lanc:
            self.cartao.Disable()
            return
        ativa = regras.categoria_e_fatura(self.categoria.GetValue())
        atual = self.cartao.GetStringSelection()
        principal = self.campo_banco.GetValue().strip()
        cartoes = sorted(c["nome_banco"] for c in self.banco_dados.contas()
                         if c["tipo"] == "cartao_credito"
                         and c["nome_banco"] != principal) if ativa else []
        self.cartao.Set([NAO_VINCULAR] + cartoes)
        if atual in cartoes:
            self.cartao.SetStringSelection(atual)
        elif len(cartoes) == 1:
            self.cartao.SetStringSelection(cartoes[0])
        else:
            self.cartao.SetSelection(0)
        self.cartao.Enable(bool(cartoes))

    def cartao_escolhido(self):
        if self.lanc or not self.cartao.IsEnabled() or self.cartao.GetSelection() <= 0:
            return None
        return self.cartao.GetStringSelection()

    # ---- itens ----
    def _preencher_itens(self, selecionar=None):
        self.lista.DeleteAllItems()
        for n, item in enumerate(self.itens):
            self.lista.InsertItem(n, item["descricao"] or "Sem descrição")
            self.lista.SetItem(n, 1, item["categoria"] or "Sem categoria")
            self.lista.SetItem(n, 2, regras.fmt_valor(item["valor"]))
            self.lista.SetItem(n, 3, regras.fmt_data(item.get("data_original")))
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

    def _limite_para(self, indice=None):
        if self.valor_documento is None:
            return None
        outros = sum(abs(i["valor"]) for n, i in enumerate(self.itens)
                     if n != indice)
        return max(0.0, self.valor_documento - outros)

    def _adicionar_item(self):
        dlg = DialogoItem(self, self.categorias, limite=self._limite_para())
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
        dlg = DialogoItem(self, self.categorias, self.itens[n],
                          limite=self._limite_para(n))
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

    # ---- importar lançamentos como itens ----
    def ids_importados(self):
        return sorted({i["origem_lancamento_id"] for i in self.itens
                       if i.get("origem_lancamento_id")})

    def _importar(self):
        fora = set(self.ids_importados())
        if self.lanc:
            fora.add(self.lanc["id"])
        dlg = ImportarLancamentos(self, self.todos, fora)
        if dlg.ShowModal() == wx.ID_OK:
            self.receber_importados(dlg.selecionados)
        dlg.Destroy()

    def receber_importados(self, selecionados):
        ja_tinha = bool(self.itens)
        for l in selecionados:
            fontes = l["itens"] or [dict(valor=l["valor"], categoria=l["categoria"],
                                         descricao=l["descricao"])]
            for fonte in fontes:
                self.itens.append(dict(
                    valor=abs(fonte["valor"] or 0),
                    categoria=fonte["categoria"] or "",
                    descricao=fonte["descricao"] or "(sem descrição)",
                    data_original=l["data_origem"],
                    origem_lancamento_id=l["id"]))
        if not ja_tinha:
            negativo = any(l["valor"] < 0 for l in selecionados)
            positivo = any(l["valor"] > 0 for l in selecionados)
            if negativo != positivo:
                self.tipo.SetSelection(0 if negativo else 1)
        # Credor e devedor vazios: só preenche se todos combinarem
        for campo, controle in (("credor", self.credor), ("devedor", self.devedor)):
            valores = {l[campo] for l in selecionados if l[campo]}
            if not controle.GetValue().strip() and len(valores) == 1:
                controle.SetValue(valores.pop())
        self._preencher_itens(len(self.itens) - 1)
        self._falar_total("%d lançamento%s importado%s como itens. Serão excluídos "
                          "ao salvar." % (len(selecionados),
                                          "" if len(selecionados) == 1 else "s",
                                          "" if len(selecionados) == 1 else "s"))

    # ---- pagamento dividido ----
    def _preencher_pagamentos(self, selecionar=None):
        ligado = self.dividir.GetValue()
        self.lista_pag.DeleteAllItems()
        self.nav_pag.definir_linha(-1)
        if ligado:
            for n, p in enumerate(self.pagamentos):
                self.lista_pag.InsertItem(n, p["banco"] or "Sem banco")
                self.lista_pag.SetItem(n, 1, p["forma"] or "Sem forma")
                self.lista_pag.SetItem(n, 2, regras.fmt_valor(p["valor"]))
            if self.pagamentos and selecionar is not None:
                n = min(selecionar, len(self.pagamentos) - 1)
                self.lista_pag.Select(n)
                self.lista_pag.Focus(n)
                self.nav_pag.definir_linha(n)
        self.lista_pag.Enable(ligado)
        for botao in self.botoes_pag:
            botao.Enable(ligado)
        # Com pagamento dividido, banco e forma ficam em cada parte
        self.campo_banco.Enable(not ligado)
        self.forma.Enable(not ligado)

    def _falar_pagamentos(self, texto):
        soma = sum(p["valor"] for p in self.pagamentos)
        self.fala.falar("%s Total dos pagamentos: %s." % (
            texto, regras.fmt_valor(soma)), True)

    def _adicionar_pagamento(self):
        dlg = DialogoPagamento(self, self.banco_dados)
        if dlg.ShowModal() == wx.ID_OK:
            self.pagamentos.append(dlg.resultado)
            self._preencher_pagamentos(len(self.pagamentos) - 1)
            self._falar_pagamentos("Pagamento adicionado.")
        dlg.Destroy()

    def _editar_pagamento(self):
        n = self.nav_pag.atual()
        if not 0 <= n < len(self.pagamentos):
            self.fala.falar("Nenhum pagamento selecionado.", True)
            return
        dlg = DialogoPagamento(self, self.banco_dados, self.pagamentos[n])
        if dlg.ShowModal() == wx.ID_OK:
            self.pagamentos[n] = dlg.resultado
            self._preencher_pagamentos(n)
            self._falar_pagamentos("Pagamento alterado.")
        dlg.Destroy()

    def _remover_pagamento(self):
        n = self.nav_pag.atual()
        if not 0 <= n < len(self.pagamentos):
            self.fala.falar("Nenhum pagamento selecionado.", True)
            return
        del self.pagamentos[n]
        self._preencher_pagamentos(n)
        self._falar_pagamentos("Pagamento removido.")

    # ---- leitura por IA ----
    def _ler_arquivo(self, _evt):
        with wx.FileDialog(
                self, "Escolher documento", wildcard=(
                    "Documentos|*.pdf;*.png;*.jpg;*.jpeg;*.webp;*.txt;*.xml;*.xlsx;*.xls|"
                    "Todos os arquivos|*.*"),
                style=wx.FD_OPEN | wx.FD_FILE_MUST_EXIST) as dlg:
            if dlg.ShowModal() != wx.ID_OK:
                return
            caminho = dlg.GetPath()
        self.ler_caminho(caminho, guardar=bool(
            self.guardar_arquivo and self.guardar_arquivo.GetValue()))

    def _colar(self, caminho):
        self.ler_caminho(caminho, guardar=bool(
            self.guardar_arquivo and self.guardar_arquivo.GetValue()))

    def _nao_vincular(self, _evt):
        """O arquivo continua guardado; só não fica ligado a este lançamento."""
        self.documento_id = None
        self.botao_nao_vincular.Disable()
        self.fala.falar("Este lançamento será salvo sem vínculo com o documento.", True)
        self.tipo.SetFocus()

    def _cadastrar_novos(self, _evt):
        """O que foi digitado e ainda não está nas listas, inclusive em itens e
        pagamentos. Um botão só evita seis paradas a mais no Tab."""
        novos = []
        for campo, valor in self.textos_digitados():
            if valor and valor not in self.banco_dados.opcoes(campo) and (
                    (campo, valor) not in novos):
                novos.append((campo, valor))
        if not novos:
            self.fala.falar("Nenhum texto novo para cadastrar.", True)
            return
        nomes = {c: r for _, c, r in CAMPOS_IA}
        texto = "; ".join("%s: %s" % (nomes[c], v) for c, v in novos)
        if wx.MessageBox("Cadastrar nas listas? %s." % texto, "Cadastrar nas listas",
                         wx.YES_NO | wx.ICON_QUESTION, self) != wx.YES:
            return
        for campo, valor in novos:
            self.banco_dados.registrar_operacao("opcao_add", campo=campo, valor=valor)
        self.categorias = self.banco_dados.opcoes("categoria")
        self.fala.falar("%d opç%s cadastrada%s." % (
            len(novos), "ão" if len(novos) == 1 else "ões",
            "" if len(novos) == 1 else "s"), True)

    def textos_digitados(self):
        pares = [("banco", self.campo_banco.GetValue()),
                 ("forma", self.forma.GetValue()),
                 ("estabelecimento", self.estabelecimento.GetValue()),
                 ("categoria", self.categoria.GetValue()),
                 ("credor", self.credor.GetValue()),
                 ("devedor", self.devedor.GetValue())]
        pares += [("categoria", i["categoria"]) for i in self.itens]
        for p in self.pagamentos:
            pares += [("banco", p["banco"]), ("forma", p["forma"])]
        return [(c, (v or "").strip()) for c, v in pares]

    def ler_caminho(self, caminho, guardar=False, documento_id=None):
        try:
            tipo, conteudo, mime = documentos.preparar_para_ia(caminho)
        except (ValueError, OSError) as e:
            wx.MessageBox(str(e), "Ler documento", wx.OK | wx.ICON_ERROR, self)
            return
        dados = self._consultar_ia(tipo, conteudo, mime, caminho)
        if dados is None:
            return
        self.aplicar_ia(dados)
        if documento_id:
            self.documento_id = documento_id
        elif guardar:
            self.documento_id = self.GetParent().guardar_documento(caminho)
        self.botao_nao_vincular.Enable(bool(self.documento_id))

    def _ler_texto(self, _evt):
        dlg = DialogoTextoIA(self)
        texto = dlg.texto.GetValue().strip() if dlg.ShowModal() == wx.ID_OK else ""
        dlg.Destroy()
        if texto:
            dados = self._consultar_ia("texto", texto, "text/plain", "texto colado")
            if dados is not None:
                self.aplicar_ia(dados)

    def _consultar_ia(self, tipo, conteudo, mime, nome):
        correcoes = self.banco_dados.correcoes()
        dados, erro = executar_com_espera(
            self, "Lendo com IA, aguarde",
            lambda: self.nuvem.ler_documento(tipo, conteudo, mime, nome, correcoes))
        if erro:
            log.warning("leitura por IA: %r", erro)
            wx.MessageBox(mensagem_de_erro(erro), "Ler com IA",
                          wx.OK | wx.ICON_ERROR, self)
            return None
        return dados

    def aplicar_ia(self, dados):
        """Preenche o formulário como o site: só com o que bate com as listas;
        o resto é oferecido para entrar nelas."""
        preenchidos, nao_achados = [], []
        itens_ia = [i for i in dados.get("itens") or []
                    if i and _e_numero(i.get("valor"))]
        pag_ia = [p for p in dados.get("pagamentos") or []
                  if p and _e_numero(p.get("valor"))]
        usar_itens, usar_pag = len(itens_ia) >= 2, len(pag_ia) >= 2
        if usar_itens:
            self.valor_documento = sum(abs(float(i["valor"])) for i in itens_ia)
        elif _e_numero(dados.get("valor")):
            self.valor_documento = abs(float(dados["valor"]))
        for chave, campo, rotulo in (("data", self.data_origem, "Data"),
                                     ("data_vencimento", self.vencimento,
                                      "Vencimento")):
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(dados.get(chave) or "")):
                campo.SetValue(regras.fmt_data(dados[chave]))
                preenchidos.append(rotulo)
        if dados.get("tipo_transacao") == "credito":
            self.tipo.SetSelection(1)
            preenchidos.append("Tipo, entrada")
        elif dados.get("tipo_transacao") == "debito":
            self.tipo.SetSelection(0)
            preenchidos.append("Tipo, saída")
        cat_sem_lista = 0
        if usar_itens:
            self.itens = []
            for i in itens_ia:
                categoria = regras.opcao_equivalente(
                    "categoria", i.get("categoria_sugerida"), self.categorias)
                if i.get("categoria_sugerida") and not categoria:
                    cat_sem_lista += 1
                self.itens.append(dict(
                    valor=round(abs(float(i["valor"])), 2), categoria=categoria or "",
                    descricao=i.get("descricao") or "", data_original=None))
            self._preencher_itens(0)
            preenchidos.append("%d itens" % len(itens_ia))
        elif _e_numero(dados.get("valor")):
            self.valor.SetValue(regras.fmt_valor(abs(float(dados["valor"]))))
            preenchidos.append("Valor")
        if usar_pag:
            opcoes_b = self.banco_dados.opcoes("banco")
            opcoes_f = self.banco_dados.opcoes("forma")
            self.pagamentos = [dict(
                banco=regras.opcao_equivalente("banco", p.get("banco"), opcoes_b) or "",
                forma=regras.opcao_equivalente(
                    "forma", p.get("forma_pagamento"), opcoes_f) or None,
                valor=round(abs(float(p["valor"])), 2)) for p in pag_ia]
            self.dividir.SetValue(True)
            self._preencher_pagamentos(0)
            preenchidos.append("Pagamento dividido em %d formas" % len(pag_ia))
        controles = dict(banco=self.campo_banco, forma=self.forma,
                         estabelecimento=self.estabelecimento,
                         categoria=self.categoria, credor=self.credor,
                         devedor=self.devedor)
        for chave, campo, rotulo in CAMPOS_IA:
            if (usar_itens and campo == "categoria") or (
                    usar_pag and campo in ("banco", "forma")):
                continue
            sugestao = dados.get(chave)
            if not sugestao:
                continue
            achada = regras.opcao_equivalente(
                campo, sugestao, self.banco_dados.opcoes(campo))
            if achada:
                controles[campo].SetValue(achada)
                preenchidos.append(rotulo)
            else:
                nao_achados.append((campo, rotulo, sugestao))
        if not usar_itens and dados.get("descricao"):
            self.descricao.SetValue(dados["descricao"])
            preenchidos.append("Descrição")
        self._atualizar_fatura()
        self.extracao_ia = dados
        texto = ("Documento lido. Preenchido: %s." % ", ".join(preenchidos)
                 if preenchidos else "Documento lido, mas nenhum campo pôde ser "
                 "preenchido com confiança.")
        if cat_sem_lista:
            texto += " %d categoria%s de item fora das listas: escolha na aba Itens." % (
                cat_sem_lista, "" if cat_sem_lista == 1 else "s")
        if usar_pag and any(not p["banco"] for p in self.pagamentos):
            texto += " Confira os bancos na aba Pagamento dividido."
        if dados.get("tipo_transacao") not in ("credito", "debito"):
            texto += " A IA não teve certeza se é entrada ou saída: confira o Tipo."
        self.fala.falar(texto, True)
        wx.MessageBox(texto, "Ler com IA", wx.OK | wx.ICON_INFORMATION, self)
        self._oferecer_sugestoes(nao_achados, controles)

    def _oferecer_sugestoes(self, nao_achados, controles):
        for campo, rotulo, valor in nao_achados:
            resposta = wx.MessageBox(
                "A IA sugeriu %s: %s, que não está nas suas listas. Adicionar à "
                "lista e usar?" % (rotulo, valor), "Sugestão da IA",
                wx.YES_NO | wx.ICON_QUESTION, self)
            if resposta == wx.YES:
                self.banco_dados.registrar_operacao("opcao_add", campo=campo,
                                                    valor=valor)
                controles[campo].Append(valor)
                controles[campo].SetValue(valor)
                if campo == "categoria":
                    self.categorias = self.banco_dados.opcoes("categoria")

    # ---- salvar ----
    def _erro(self, texto, campo, aba=None):
        wx.MessageBox(texto, self.GetTitle(), wx.OK | wx.ICON_ERROR, self)
        if aba is not None:
            self.abas.SetSelection(aba)
        campo.SetFocus()

    def _ler_datas(self):
        """(origem, vencimento) ou None depois de avisar do erro."""
        try:
            origem = _ler_data(self.data_origem.GetValue())
        except ValueError:
            origem = ""
        if not origem:
            self._erro("Preencha a data de origem: digite os números, por exemplo "
                       "25102026, ou use as setas.", self.data_origem, 0)
            return None
        try:
            vencimento = _ler_data(self.vencimento.GetValue())
        except ValueError:
            self._erro("Vencimento inválido. Digite os números, use as setas ou "
                       "deixe em branco.",
                       self.vencimento, 0)
            return None
        return origem, vencimento

    def _parcelar(self, _evt):
        if self.itens and not self._soma_itens():
            self._erro("A soma dos itens precisa ser diferente de zero para parcelar.",
                       self.lista, 1)
            return
        datas = self._ler_datas()
        if not datas:
            return
        origem, vencimento = datas
        dividido = self.dividir.GetValue()
        if dividido:
            # O total precisa estar certo antes de dividir as formas entre as parcelas
            valor = self._valor_informado()
            if valor is None or not self._pagamentos_conferem(valor):
                return
        if not self._confirmar_importados():
            return
        base = vencimento or datetime.date.today().isoformat()
        dlg = DialogoParcelar(self, self.valor.GetValue().strip(),
                              self.tipo.GetSelection() == 1, base, len(self.itens),
                              len(self.pagamentos) if dividido else 0)
        if dlg.ShowModal() == wx.ID_OK:
            self.parcelas = self._montar_parcelas(dlg.parcelas, origem)
            self.EndModal(wx.ID_OK)
        dlg.Destroy()

    def _montar_parcelas(self, parcelas, origem):
        def texto(controle):
            return controle.GetValue().strip() or None

        dividido = self.dividir.GetValue()
        comum = dict(
            data_origem=origem,
            banco=None if dividido else texto(self.campo_banco),
            forma=None if dividido else texto(self.forma),
            estabelecimento=texto(self.estabelecimento), credor=texto(self.credor),
            devedor=texto(self.devedor), pago=False)
        absolutos = [abs(valor) for _, _, valor in parcelas]
        pagamentos = [[] for _ in parcelas]
        if dividido:
            rateio = regras.ratear_itens([p["valor"] for p in self.pagamentos], absolutos)
            pagamentos = [[dict(p, valor=parte)
                           for p, parte in zip(self.pagamentos, partes) if parte]
                          for partes in rateio]
        if self.itens:
            # Como em qualquer lançamento com itens, categoria e descrição são deles
            sinal = -1 if self._soma_itens() < 0 else 1
            rateio = regras.ratear_itens(
                [i["valor"] * sinal for i in self.itens], absolutos)
            return [dict(
                comum, data_vencimento=vencimento, valor=valor, parcela=rotulo,
                pagamentos=pags,
                itens=[dict(item, valor=parte)
                       for item, parte in zip(self.itens, partes) if parte])
                for (rotulo, vencimento, valor), partes, pags
                in zip(parcelas, rateio, pagamentos)]
        descricao = texto(self.descricao)
        return [dict(
            comum, data_vencimento=vencimento, valor=valor, parcela=rotulo,
            categoria=texto(self.categoria), itens=[], pagamentos=pags,
            descricao="%s — %s" % (descricao, rotulo) if descricao else rotulo)
            for (rotulo, vencimento, valor), pags in zip(parcelas, pagamentos)]

    def _valor_informado(self):
        """O valor com sinal, ou None depois de avisar do erro."""
        if self.itens:
            bruto = self._soma_itens()
        else:
            try:
                bruto = regras.ler_valor(self.valor.GetValue())
            except ValueError:
                self._erro("Informe o valor em números, por exemplo 12,50, ou "
                           "adicione itens.", self.valor, 0)
                return None
        return regras.valor_com_tipo(bruto, self.tipo.GetSelection() == 1)

    def _pagamentos_conferem(self, valor):
        if not self.pagamentos:
            self._erro("Adicione ao menos uma forma de pagamento, ou desmarque "
                       "Dividir pagamento.", self.dividir, 2)
            return False
        sem_banco = next((n for n, p in enumerate(self.pagamentos, 1)
                          if not p["banco"]), None)
        if sem_banco:
            self._erro("Escolha o banco ou cartão do pagamento %d." % sem_banco,
                       self.lista_pag, 2)
            return False
        soma = sum(p["valor"] for p in self.pagamentos)
        if abs(soma - abs(valor)) > 0.005:
            self._erro(
                "A soma dos pagamentos, %s, não bate com o valor total do "
                "lançamento, %s." % (regras.fmt_valor(soma),
                                     regras.fmt_valor(abs(valor))),
                self.lista_pag, 2)
            return False
        return True

    def _confirmar_importados(self):
        importados = self.ids_importados()
        if not importados:
            return True
        return wx.MessageBox(
            "Este lançamento vai importar %d lançamento%s antigo%s como itens. "
            "Os originais serão excluídos ao salvar, e os documentos anexados "
            "passam a ficar vinculados a este. Continuar?" % (
                len(importados), "" if len(importados) == 1 else "s",
                "" if len(importados) == 1 else "s"),
            self.GetTitle(), wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION,
            self) == wx.YES

    def _salvar(self, _evt):
        datas = self._ler_datas()
        if not datas:
            return
        origem, vencimento = datas
        valor = self._valor_informado()
        if valor is None:
            return
        dividido = self.dividir.GetValue()
        if dividido and not self._pagamentos_conferem(valor):
            return
        if not self._confirmar_importados():
            return

        def texto(controle):
            return controle.GetValue().strip() or None

        self.pagamentos = self.pagamentos if dividido else []
        self.resultado = dict(
            data_origem=origem, data_vencimento=vencimento, valor=valor,
            banco=None if dividido else texto(self.campo_banco),
            forma=None if dividido else texto(self.forma),
            estabelecimento=texto(self.estabelecimento),
            descricao=None if self.itens else texto(self.descricao),
            categoria=None if self.itens else texto(self.categoria),
            credor=texto(self.credor), devedor=texto(self.devedor),
            pago=self.pago.GetValue())
        self.EndModal(wx.ID_OK)


def _e_numero(valor):
    try:
        float(valor)
    except (TypeError, ValueError):
        return False
    return True


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
