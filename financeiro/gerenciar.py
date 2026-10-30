"""Janelas de gerenciamento: listas, contas, documentos, correções e pendências."""
import datetime
import logging
import os
import shutil

import wx

from financeiro import documentos, regras
from financeiro.formulario import (_botoes_ok, _grade, _texto, executar_com_espera,
                                   mensagem_de_erro)
from financeiro.navegacao import NavegacaoDeColunas

log = logging.getLogger("financeiro.gerenciar")
CAMPOS = (("banco", "Banco ou cartão"), ("forma", "Forma de pagamento"),
          ("estabelecimento", "Estabelecimento"), ("categoria", "Categoria"),
          ("credor", "Credor"), ("devedor", "Devedor"))
NOME_CAMPO = dict(CAMPOS)
TIPOS_CONTA = (("cartao_credito", "Cartão de crédito", "Limite total"),
               ("conta_corrente", "Conta corrente", "Saldo inicial"))


def _lista(pai, rotulo, colunas):
    """Rótulo e tabela, nessa ordem de criação."""
    texto = wx.StaticText(pai, label=rotulo)
    tabela = wx.ListCtrl(pai, name=rotulo, style=wx.LC_REPORT | wx.LC_SINGLE_SEL)
    for col, (titulo, largura) in enumerate(colunas):
        tabela.InsertColumn(col, titulo, width=largura)
    return texto, tabela


def _pedir_texto(pai, titulo, rotulo, valor=""):
    dlg = wx.TextEntryDialog(pai, rotulo, titulo, valor)
    resposta = dlg.GetValue().strip() if dlg.ShowModal() == wx.ID_OK else None
    dlg.Destroy()
    return resposta


def _confirmar(pai, texto, titulo):
    return wx.MessageBox(texto, titulo, wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION,
                         pai) == wx.YES


class Listas(wx.Dialog):
    def __init__(self, pai, banco):
        super().__init__(pai, title="Gerenciar listas", size=(560, 520),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.banco, self.fala = banco, pai.fala
        self.valores = []
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(wx.StaticText(self, label="Lista"), 0, wx.LEFT | wx.TOP, 8)
        self.campo = wx.Choice(self, name="Lista", choices=[r for _, r in CAMPOS])
        self.campo.SetSelection(0)
        self.campo.Bind(wx.EVT_CHOICE, lambda e: self.atualizar())
        caixa.Add(self.campo, 0, wx.EXPAND | wx.ALL, 8)
        texto, self.lista = _lista(self, "Opções", (("Opção", 480),))
        caixa.Add(texto, 0, wx.LEFT, 8)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        self.navegacao = NavegacaoDeColunas(self.lista, self.fala,
                                            ao_ativar=self.renomear,
                                            ao_excluir=self.excluir)
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, lambda e: self.renomear())
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        for rotulo, acao in (("Adicionar", self.adicionar), ("Renomear", self.renomear),
                             ("Excluir", self.excluir)):
            botao = wx.Button(self, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            botoes.Add(botao, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.atualizar()
        self.campo.SetFocus()

    @property
    def chave(self):
        return CAMPOS[self.campo.GetSelection()][0]

    def atualizar(self, selecionar=0):
        self.valores = self.banco.opcoes(self.chave)
        self.lista.DeleteAllItems()
        for n, valor in enumerate(self.valores):
            self.lista.InsertItem(n, valor)
        self.navegacao.definir_linha(-1)
        if self.valores:
            n = min(selecionar, len(self.valores) - 1)
            self.lista.Select(n)
            self.lista.Focus(n)
            self.navegacao.definir_linha(n)

    def adicionar(self):
        valor = _pedir_texto(self, "Adicionar opção",
                             "Nova opção para %s" % NOME_CAMPO[self.chave])
        if not valor:
            return
        if valor in self.valores:
            wx.MessageBox("Essa opção já existe: %s." % valor, "Adicionar opção",
                          wx.OK | wx.ICON_ERROR, self)
            return
        self.banco.registrar_operacao("opcao_add", campo=self.chave, valor=valor)
        self.atualizar()
        self.atualizar(self.valores.index(valor))
        self.fala.falar("Opção %s adicionada." % valor, True)

    def _atual(self):
        n = self.navegacao.atual()
        if 0 <= n < len(self.valores):
            return n, self.valores[n]
        self.fala.falar("Nenhuma opção selecionada.", True)
        return None, None

    def renomear(self):
        """Renomear troca o texto em todos os lançamentos que usam a opção."""
        n, antigo = self._atual()
        if antigo is None:
            return
        novo = _pedir_texto(self, "Renomear opção", "Novo valor para %s" % antigo,
                            antigo)
        if not novo or novo == antigo:
            return
        if novo in self.valores:
            wx.MessageBox("Já existe uma opção chamada %s." % novo, "Renomear opção",
                          wx.OK | wx.ICON_ERROR, self)
            return
        self.banco.registrar_operacao("opcao_renomear", campo=self.chave,
                                      antigo=antigo, novo=novo)
        self.atualizar()
        self.atualizar(self.valores.index(novo))
        self.fala.falar("Renomeada para %s. Lançamentos atualizados." % novo, True)

    def excluir(self):
        n, valor = self._atual()
        if valor is None or not _confirmar(
                self, "Remover a opção %s de %s?" % (valor, NOME_CAMPO[self.chave]),
                "Excluir opção"):
            return
        self.banco.registrar_operacao("opcao_del", campo=self.chave, valor=valor)
        self.atualizar(n)
        self.fala.falar("Opção %s removida." % valor, True)


class ConfigurarConta(wx.Dialog):
    def __init__(self, pai, nome, conta=None):
        super().__init__(pai, title="Configurar: " + nome)
        conta = conta or {}
        self.resultado = None
        grade = _grade()
        grade.Add(wx.StaticText(self, label="Tipo"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.tipo = wx.Choice(self, name="Tipo", choices=[t[1] for t in TIPOS_CONTA])
        chaves = [t[0] for t in TIPOS_CONTA]
        self.tipo.SetSelection(chaves.index(conta["tipo"])
                               if conta.get("tipo") in chaves else 0)
        grade.Add(self.tipo, 1, wx.EXPAND)
        valor = conta.get("limite_total") if conta.get("tipo") == "cartao_credito" \
            else conta.get("saldo_inicial")
        self.valor = _texto(self, grade, "Limite total ou saldo inicial",
                            "" if valor is None else regras.fmt_valor(valor))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(_botoes_ok(self, "Salvar", self._salvar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.SetSize((max(self.GetSize().width, 420), -1))
        self.tipo.SetFocus()

    def _salvar(self, _evt):
        chave, _, rotulo = TIPOS_CONTA[self.tipo.GetSelection()]
        try:
            valor = regras.ler_valor(self.valor.GetValue())
        except ValueError:
            wx.MessageBox("Informe o %s em números." % rotulo.lower(), self.GetTitle(),
                          wx.OK | wx.ICON_ERROR, self)
            self.valor.SetFocus()
            return
        self.resultado = dict(
            tipo=chave,
            saldo_inicial=valor if chave == "conta_corrente" else None,
            limite_total=valor if chave == "cartao_credito" else None)
        self.EndModal(wx.ID_OK)


class Documentos(wx.Dialog):
    """Documentos guardados. "Analisar" fecha esta janela e abre o formulário."""

    def __init__(self, pai, banco, nuvem, todos):
        super().__init__(pai, title="Documentos", size=(860, 560),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.janela, self.banco, self.nuvem = pai, banco, nuvem
        self.fala = pai.fala
        self.por_id = {l["id"]: l for l in todos}
        self.visiveis = []
        self.analisar_doc = None
        grade = _grade()
        self.busca = _texto(self, grade, "Buscar pelo nome")
        self.de = _texto(self, grade, "Guardado de (dia/mês/ano)")
        self.ate = _texto(self, grade, "Guardado até (dia/mês/ano)")
        for campo in (self.busca, self.de, self.ate):
            campo.Bind(wx.EVT_TEXT, lambda e: self.atualizar())
        texto, self.lista = _lista(self, "Documentos guardados", (
            ("Nome", 340), ("Guardado em", 140), ("Tamanho", 80),
            ("Lançamento", 240)))
        self.navegacao = NavegacaoDeColunas(self.lista, self.fala,
                                            ao_ativar=self.abrir,
                                            ao_excluir=self.excluir)
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, lambda e: self.abrir())
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        permissoes = banco.permissoes()
        for rotulo, acao, liberado in (
                ("Guardar arquivo", self.guardar, permissoes["documentos"]),
                ("Abrir", self.abrir, True),
                ("Salvar cópia", self.salvar_copia, True),
                ("Analisar com IA", self.analisar, permissoes["ia"]),
                ("Vincular ao lançamento atual", self.vincular, True),
                ("Desvincular", self.desvincular, True),
                ("Excluir", self.excluir, True)):
            if not liberado:
                continue
            botao = wx.Button(self, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            botoes.Add(botao, 0, wx.RIGHT, 6)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 0, wx.EXPAND | wx.ALL, 8)
        caixa.Add(texto, 0, wx.LEFT, 8)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.ALL, 8)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.atualizar()
        self.lista.SetFocus()

    @staticmethod
    def _data(campo):
        try:
            return datetime.datetime.strptime(
                campo.GetValue().strip(), "%d/%m/%Y").date().isoformat()
        except ValueError:
            return ""

    def atualizar(self):
        termo = regras.sem_acentos(self.busca.GetValue()).strip()
        de, ate = self._data(self.de), self._data(self.ate)
        self.visiveis = [
            d for d in self.banco.documentos()
            if (not termo or termo in regras.sem_acentos(d["nome_arquivo"]))
            and (not de or (d["criado_em"] or "")[:10] >= de)
            and (not ate or (d["criado_em"] or "")[:10] <= ate)]
        self.lista.DeleteAllItems()
        for n, d in enumerate(self.visiveis):
            self.lista.InsertItem(n, d["nome_arquivo"])
            self.lista.SetItem(n, 1, regras.fmt_data((d["criado_em"] or "")[:10]))
            self.lista.SetItem(n, 2, _tamanho(d["tamanho_bytes"]))
            self.lista.SetItem(n, 3, self._nome_lancamento(d))
        self.navegacao.definir_linha(-1)
        if self.visiveis:
            self.lista.Select(0)
            self.lista.Focus(0)
            self.navegacao.definir_linha(0)

    def _nome_lancamento(self, d):
        l = self.por_id.get(d["lancamento_id"])
        if l:
            return "%s, %s" % (regras.descricao_de(l),
                               regras.fmt_valor_com_sinal(l["valor"]))
        return "Vinculado" if d["lancamento_id"] else "—"

    def _atual(self):
        n = self.navegacao.atual()
        if 0 <= n < len(self.visiveis):
            return self.visiveis[n]
        self.fala.falar("Nenhum documento selecionado.", True)
        return None

    def guardar(self):
        with wx.FileDialog(self, "Guardar arquivo",
                           style=wx.FD_OPEN | wx.FD_FILE_MUST_EXIST) as dlg:
            if dlg.ShowModal() != wx.ID_OK:
                return
            caminho = dlg.GetPath()
        self.janela.guardar_documento(caminho)
        self.atualizar()
        self.fala.falar("Arquivo guardado: %s." % os.path.basename(caminho), True)

    def _arquivo_local(self, d):
        """Baixa se ainda não houver cópia aqui. Devolve o caminho ou None."""
        destino = documentos.caminho_local(d["id"], d["nome_arquivo"])
        if os.path.exists(destino):
            return destino
        conteudo, erro = executar_com_espera(
            self, "Baixando o documento, aguarde",
            lambda: self.nuvem.baixar_documento(d["caminho"]))
        if erro:
            log.warning("baixar documento: %r", erro)
            wx.MessageBox(mensagem_de_erro(erro), "Documentos",
                          wx.OK | wx.ICON_ERROR, self)
            return None
        with open(destino, "wb") as f:
            f.write(conteudo)
        return destino

    def abrir(self):
        d = self._atual()
        caminho = d and self._arquivo_local(d)
        if caminho:
            os.startfile(caminho)

    def salvar_copia(self):
        d = self._atual()
        caminho = d and self._arquivo_local(d)
        if not caminho:
            return
        with wx.FileDialog(self, "Salvar cópia", defaultFile=d["nome_arquivo"],
                           style=wx.FD_SAVE | wx.FD_OVERWRITE_PROMPT) as dlg:
            if dlg.ShowModal() == wx.ID_OK:
                shutil.copyfile(caminho, dlg.GetPath())
                self.fala.falar("Cópia salva.", True)

    def analisar(self):
        d = self._atual()
        caminho = d and self._arquivo_local(d)
        if caminho:
            self.analisar_doc = (d, caminho)
            self.EndModal(wx.ID_OK)

    def vincular(self):
        d = self._atual()
        l = self.janela.lancamento_atual()
        if not d:
            return
        if not l:
            self.fala.falar("Nenhum lançamento selecionado na tabela principal.", True)
            return
        if _confirmar(self, "Vincular %s ao lançamento %s?" % (
                d["nome_arquivo"], regras.descricao_de(l)), "Vincular documento"):
            self.banco.registrar_operacao("doc_vincular", doc_id=d["id"], ids=[l["id"]])
            self.atualizar()
            self.fala.falar("Documento vinculado.", True)

    def desvincular(self):
        d = self._atual()
        if d and d["lancamento_id"] and _confirmar(
                self, "Remover o vínculo de %s com o lançamento? O documento continua "
                "guardado." % d["nome_arquivo"], "Desvincular"):
            self.banco.registrar_operacao("doc_vincular", doc_id=d["id"], ids=[])
            self.atualizar()
            self.fala.falar("Documento desvinculado.", True)

    def excluir(self):
        d = self._atual()
        if d and _confirmar(self, "Excluir o documento %s? Essa ação não pode ser "
                            "desfeita." % d["nome_arquivo"], "Excluir documento"):
            self.banco.registrar_operacao("doc_excluir", doc_id=d["id"],
                                          caminho=d["caminho"])
            local = documentos.caminho_local(d["id"], d["nome_arquivo"])
            if os.path.exists(local):
                os.remove(local)
            self.atualizar()
            self.fala.falar("Documento excluído.", True)


def _tamanho(bytes_):
    if not bytes_:
        return "—"
    if bytes_ < 1024:
        return "%d B" % bytes_
    if bytes_ < 1024 * 1024:
        return "%d KB" % round(bytes_ / 1024)
    return ("%.1f MB" % (bytes_ / (1024 * 1024))).replace(".", ",")


class EditarCorrecao(wx.Dialog):
    def __init__(self, pai, correcao):
        super().__init__(pai, title="Editar correção: " + NOME_CAMPO.get(
            correcao["campo"], correcao["campo"]))
        self.resultado = None
        grade = _grade()
        self.errado = _texto(self, grade, "Valor errado", correcao["valor_errado"] or "")
        self.correto = _texto(self, grade, "Valor correto",
                              correcao["valor_correto"] or "")
        self.contexto = _texto(self, grade, "Contexto", correcao["contexto"] or "")
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(grade, 1, wx.EXPAND | wx.ALL, 12)
        caixa.Add(_botoes_ok(self, "Salvar", self._salvar), 0,
                  wx.ALL | wx.ALIGN_RIGHT, 12)
        self.SetSizerAndFit(caixa)
        self.SetSize((max(self.GetSize().width, 460), -1))
        self.correto.SetFocus()

    def _salvar(self, _evt):
        errado, correto = self.errado.GetValue().strip(), self.correto.GetValue().strip()
        if not errado or not correto or errado == correto:
            wx.MessageBox("Preencha o valor errado e o correto, diferentes entre si.",
                          self.GetTitle(), wx.OK | wx.ICON_ERROR, self)
            return
        self.resultado = dict(valor_errado=errado, valor_correto=correto,
                              contexto=self.contexto.GetValue().strip() or None)
        self.EndModal(wx.ID_OK)


class Correcoes(wx.Dialog):
    """O que a IA aprendeu com as suas correções."""

    def __init__(self, pai, banco):
        super().__init__(pai, title="Correções aprendidas pela IA", size=(760, 480),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.banco, self.fala = banco, pai.fala
        self.correcoes = []
        texto, self.lista = _lista(self, "Correções", (
            ("Campo", 150), ("Valor errado", 200), ("Valor correto", 200),
            ("Contexto", 170)))
        self.navegacao = NavegacaoDeColunas(self.lista, self.fala,
                                            ao_ativar=self.editar,
                                            ao_excluir=self.excluir)
        self.lista.Bind(wx.EVT_LIST_ITEM_ACTIVATED, lambda e: self.editar())
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        for rotulo, acao in (("Editar", self.editar), ("Excluir", self.excluir),
                             ("Apagar todas", self.limpar)):
            botao = wx.Button(self, label=rotulo)
            botao.Bind(wx.EVT_BUTTON, lambda e, a=acao: a())
            botoes.Add(botao, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(texto, 0, wx.ALL, 8)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 8)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.atualizar()
        self.lista.SetFocus()

    def atualizar(self, selecionar=0):
        self.correcoes = self.banco.correcoes()
        self.lista.DeleteAllItems()
        for n, c in enumerate(self.correcoes):
            self.lista.InsertItem(n, NOME_CAMPO.get(c["campo"], c["campo"]))
            self.lista.SetItem(n, 1, c["valor_errado"] or "")
            self.lista.SetItem(n, 2, c["valor_correto"] or "")
            self.lista.SetItem(n, 3, c["contexto"] or "—")
        self.navegacao.definir_linha(-1)
        if self.correcoes:
            n = min(selecionar, len(self.correcoes) - 1)
            self.lista.Select(n)
            self.lista.Focus(n)
            self.navegacao.definir_linha(n)

    def _atual(self):
        n = self.navegacao.atual()
        if not 0 <= n < len(self.correcoes):
            self.fala.falar("Nenhuma correção selecionada.", True)
            return None, None
        c = self.correcoes[n]
        if c["id"] < 0:
            # O id definitivo só existe depois do envio
            self.fala.falar("Esta correção ainda não foi enviada. Sincronize e "
                            "tente de novo.", True)
            return None, None
        return n, c

    def editar(self):
        n, c = self._atual()
        if c is None:
            return
        dlg = EditarCorrecao(self, c)
        if dlg.ShowModal() == wx.ID_OK:
            self.banco.registrar_operacao("correcao_editar", id=c["id"], **dlg.resultado)
            self.atualizar(n)
            self.fala.falar("Correção atualizada.", True)
        dlg.Destroy()

    def excluir(self):
        n, c = self._atual()
        if c is not None:
            self.banco.registrar_operacao("correcao_excluir", id=c["id"])
            self.atualizar(n)
            self.fala.falar("Correção removida.", True)

    def limpar(self):
        if self.correcoes and _confirmar(
                self, "Apagar todas as %d correções aprendidas? A IA volta a inferir "
                "do zero." % len(self.correcoes), "Apagar correções"):
            self.banco.registrar_operacao("correcoes_limpar")
            self.atualizar()
            self.fala.falar("Correções apagadas.", True)


DESCRICAO_OPERACAO = {
    "opcao_add": "Adicionar opção {valor}",
    "opcao_del": "Excluir opção {valor}",
    "opcao_renomear": "Renomear {antigo} para {novo}",
    "conta_salvar": "Configurar conta {nome}",
    "conta_excluir": "Remover configuração da conta {nome}",
    "vincular": "Vincular dois lançamentos",
    "docs_mover": "Mudar documentos de lançamento",
    "doc_guardar": "Guardar documento {nome}",
    "doc_vincular": "Vincular documento",
    "doc_excluir": "Excluir documento",
    "correcao_add": "Memorizar correção da IA",
    "correcao_editar": "Editar correção da IA",
    "correcao_excluir": "Excluir correção da IA",
    "correcoes_limpar": "Apagar correções da IA",
}


class Pendencias(wx.Dialog):
    """Operações que esperam envio. A fila anda em ordem, então uma que o servidor
    sempre recusa travaria as seguintes: aqui dá para descartá-la."""

    def __init__(self, pai, banco):
        super().__init__(pai, title="Operações pendentes", size=(620, 420),
                         style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER)
        self.banco, self.fala = banco, pai.fala
        self.descartou = False
        texto, self.lista = _lista(self, "Operações na fila", (("Operação", 560),))
        self.navegacao = NavegacaoDeColunas(self.lista, self.fala,
                                            ao_excluir=self.descartar)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        descartar = wx.Button(self, label="Descartar operação")
        descartar.Bind(wx.EVT_BUTTON, lambda e: self.descartar())
        botoes.Add(descartar, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Fechar"))
        caixa = wx.BoxSizer(wx.VERTICAL)
        caixa.Add(texto, 0, wx.ALL, 8)
        caixa.Add(self.lista, 1, wx.EXPAND | wx.LEFT | wx.RIGHT, 8)
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 8)
        self.SetSizer(caixa)
        self.Layout()
        self.atualizar()
        self.lista.SetFocus()

    def atualizar(self, selecionar=0):
        self.operacoes = self.banco.operacoes()
        self.lista.DeleteAllItems()
        for n, op in enumerate(self.operacoes):
            modelo = DESCRICAO_OPERACAO.get(op["tipo"], op["tipo"])
            self.lista.InsertItem(n, modelo.format(**op["dados"]))
        self.navegacao.definir_linha(-1)
        if self.operacoes:
            n = min(selecionar, len(self.operacoes) - 1)
            self.lista.Select(n)
            self.lista.Focus(n)
            self.navegacao.definir_linha(n)
        else:
            self.lista.InsertItem(0, "Nenhuma operação pendente")

    def descartar(self):
        n = self.navegacao.atual()
        if not 0 <= n < len(self.operacoes):
            self.fala.falar("Nenhuma operação selecionada.", True)
            return
        if _confirmar(self, "Descartar esta operação? Ela não será enviada, e o "
                      "próximo download traz de volta o que está no servidor.",
                      "Descartar"):
            self.banco.concluir_operacoes([self.operacoes[n]["id"]])
            self.descartou = True
            self.atualizar(n)
            self.fala.falar("Operação descartada.", True)
