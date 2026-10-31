"""Campo de data: digita só os números, ou rola com as setas.

Usa uma caixa de texto comum, e não o seletor de data do Windows, porque datas
como o vencimento podem ficar vazias, e ele não permite.
"""
import datetime

import wx

from financeiro import regras

PARTES = ("Dia", "Mês", "Ano")
INICIO_DA_PARTE = (0, 3, 6)
TAMANHO_DA_PARTE = (2, 2, 4)
COMPLETO = 10


def _fala():
    """A fala fica na janela principal."""
    return getattr(wx.GetApp().GetTopWindow(), "fala", None)


class CampoData(wx.TextCtrl):
    def __init__(self, pai, rotulo, valor=""):
        super().__init__(pai, name=rotulo, value=valor)
        self.parte = 0
        self._tamanho = len(valor)
        self.Bind(wx.EVT_KEY_DOWN, self._tecla)
        self.Bind(wx.EVT_CHAR, self._caractere)
        self.Bind(wx.EVT_TEXT, self._ao_mudar)
        self.Bind(wx.EVT_SET_FOCUS, self._ao_entrar)
        self.Bind(wx.EVT_KILL_FOCUS, self._ao_sair)

    def _falar(self, texto):
        fala = _fala()
        if fala:
            fala.falar(texto, True)

    # ---- digitar ----
    def _caractere(self, evt):
        codigo = evt.GetKeyCode()
        if ord("0") <= codigo <= ord("9"):
            # Data completa: o primeiro número começa outra, mesmo com só o dia, o
            # mês ou o ano selecionado pelas setas
            texto = self.GetValue()
            if len(texto) >= COMPLETO and self.GetSelection() != (0, len(texto)):
                self.ChangeValue("")
                self._tamanho = 0
        evt.Skip()

    def _ao_mudar(self, evt):
        texto = self.GetValue()
        cresceu = len(texto) > self._tamanho
        no_fim = self.GetInsertionPoint() >= len(texto)
        # Só mexe enquanto se digita no fim: apagar ou corrigir no meio fica livre
        if cresceu and no_fim:
            formatado = regras.formatar_digitacao(texto)
            if formatado != texto:
                self.ChangeValue(formatado)
                self.SetInsertionPointEnd()
                texto = formatado
            if len(texto) == COMPLETO:
                self._anunciar_digitada(texto)
        self._tamanho = len(texto)
        evt.Skip()

    def _anunciar_digitada(self, texto):
        try:
            self._falar(regras.data_por_extenso(regras.ler_data(texto)))
        except ValueError:
            self._falar("Data inválida.")

    # ---- setas ----
    def _tecla(self, evt):
        tecla = evt.GetKeyCode()
        if evt.GetModifiers() != wx.MOD_NONE:
            evt.Skip()
        elif tecla == wx.WXK_UP:
            self.rolar(1)
        elif tecla == wx.WXK_DOWN:
            self.rolar(-1)
        elif tecla == wx.WXK_LEFT:
            self.mudar_parte(-1)
        elif tecla == wx.WXK_RIGHT:
            self.mudar_parte(1)
        else:
            evt.Skip()

    def _iso_atual(self):
        try:
            return regras.ler_data(self.GetValue())
        except ValueError:
            return None

    def rolar(self, passo):
        """Vazio ou inválido começa no dia de hoje."""
        atual = self._iso_atual()
        novo = (regras.rolar_data(atual, self.parte, passo) if atual
                else datetime.date.today().isoformat())
        self._tamanho = COMPLETO
        # SetValue avisa quem filtra enquanto o campo muda
        self.SetValue(regras.fmt_data(novo))
        self._selecionar_parte()
        self._falar(regras.data_por_extenso(novo))

    def mudar_parte(self, direcao):
        atual = self._iso_atual()
        if not atual:
            self._falar("Sem data. Use seta para cima ou para baixo para começar "
                        "no dia de hoje, ou digite os números.")
            return
        self.parte = max(0, min(2, self.parte + direcao))
        self._selecionar_parte()
        valor = regras.fmt_data(atual).split("/")[self.parte]
        self._falar("%s: %s" % (PARTES[self.parte], valor))

    def _selecionar_parte(self):
        inicio = INICIO_DA_PARTE[self.parte]
        self.SetSelection(inicio, inicio + TAMANHO_DA_PARTE[self.parte])

    # ---- entrar e sair ----
    def _ao_entrar(self, evt):
        self.parte = 0
        # Tudo selecionado: digitar substitui a data inteira
        wx.CallAfter(self._selecionar_tudo)
        evt.Skip()

    def _selecionar_tudo(self):
        if self:
            self.SelectAll()

    def _ao_sair(self, evt):
        atual = self._iso_atual()
        if atual:   # 25/10/26 ou 251026 ganham o formato completo
            self.ChangeValue(regras.fmt_data(atual))
            self._tamanho = COMPLETO
        evt.Skip()
