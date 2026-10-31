"""Navegação de planilha numa tabela nativa.

Na primeira coluna a lista funciona como sempre: mudar de linha move a seleção
nativa e o leitor de tela lê a linha inteira. Nas outras, qualquer mudança da
seleção nativa também faria ele ler tudo, então as setas para cima e para baixo
andam por um cursor próprio (`linha`), destacado na tela, e só a célula é
falada. A seleção nativa alcança o cursor ao voltar à primeira coluna ou ao
sair da tabela. Enter e barra de espaço agem sempre sobre o cursor.
"""
import wx

SALTO_PAGINA = 10
_COR_CURSOR = wx.Colour(204, 232, 255)


class NavegacaoDeColunas:
    def __init__(self, lista, fala, ao_ativar=None, ao_espaco=None,
                 ao_excluir=None):
        self.lista, self.fala = lista, fala
        self.ao_ativar, self.ao_espaco = ao_ativar, ao_espaco
        self.ao_excluir = ao_excluir
        self.coluna = 0
        self.linha = -1
        self._destacada = -1
        lista.Bind(wx.EVT_KEY_DOWN, self._tecla)
        lista.Bind(wx.EVT_LIST_ITEM_SELECTED, self._ao_selecionar)
        lista.Bind(wx.EVT_KILL_FOCUS, self._ao_sair)

    # ---- quem usa a tabela ----
    def atual(self):
        """A linha em que a pessoa está, ou -1."""
        if 0 <= self.linha < self.lista.GetItemCount():
            return self.linha
        return self.lista.GetFirstSelected()

    def definir_linha(self, n):
        """Depois de redesenhar a tabela: a seleção nativa já está em `n`."""
        self.linha = n
        self._destacada = -1

    def coluna_atual(self):
        return self._coluna_valida()

    def definir_coluna(self, coluna):
        """Ao reabrir o programa: volta à coluna em que a pessoa estava."""
        self.coluna = coluna
        linha = self.atual()
        if linha >= 0:
            self._mostrar_coluna(linha, self._coluna_valida())

    def sincronizar(self):
        """Leva a seleção nativa até o cursor."""
        n = self.atual()
        self._tirar_destaque()
        if n >= 0 and n != self.lista.GetFirstSelected():
            self.lista.Select(n)
            self.lista.Focus(n)
            self.lista.EnsureVisible(n)

    # ---- eventos ----
    def _ao_selecionar(self, evt):
        self.linha = evt.GetIndex()
        self._tirar_destaque()
        evt.Skip()

    def _ao_sair(self, evt):
        self.sincronizar()
        evt.Skip()

    def _tecla(self, evt):
        tecla = evt.GetKeyCode()
        if evt.GetModifiers() != wx.MOD_NONE:
            evt.Skip()
        elif tecla == wx.WXK_LEFT:
            self.mover_coluna(-1)
        elif tecla == wx.WXK_RIGHT:
            self.mover_coluna(1)
        elif tecla == wx.WXK_SPACE:
            # Nunca segue adiante: a lista nativa trataria o espaço como Enter
            # e abriria a linha. Quem abre é só o Enter.
            if self.ao_espaco:
                self.ao_espaco()
        elif tecla == wx.WXK_DELETE and self.ao_excluir:
            self.ao_excluir()
        elif self._coluna_valida() == 0:
            evt.Skip()      # primeira coluna: tudo nativo
        elif tecla == wx.WXK_UP:
            self.mover_linha(-1)
        elif tecla == wx.WXK_DOWN:
            self.mover_linha(1)
        elif tecla == wx.WXK_PAGEUP:
            self.mover_linha(-SALTO_PAGINA)
        elif tecla == wx.WXK_PAGEDOWN:
            self.mover_linha(SALTO_PAGINA)
        elif tecla == wx.WXK_HOME:
            self.mover_linha(-self.lista.GetItemCount())
        elif tecla == wx.WXK_END:
            self.mover_linha(self.lista.GetItemCount())
        elif tecla in (wx.WXK_RETURN, wx.WXK_NUMPAD_ENTER) and self.ao_ativar:
            self.ao_ativar()
        else:
            evt.Skip()

    # ---- movimento ----
    def _coluna_valida(self):
        return min(self.coluna, max(self.lista.GetColumnCount() - 1, 0))

    def mover_coluna(self, direcao):
        """Devolve o texto falado (None se o leitor de tela é quem lê)."""
        linha = self.atual()
        total = self.lista.GetColumnCount()
        if linha < 0 or not total:
            return None
        atual = self._coluna_valida()
        destino = atual + direcao
        if not 0 <= destino < total:
            self.coluna = atual
            texto = "%s coluna." % ("Primeira" if direcao < 0 else "Última")
            self.fala.falar(texto, True)
            return texto
        self.coluna = destino
        self._mostrar_coluna(linha, destino)
        if destino == 0:
            return self._voltar_a_primeira(linha)
        texto = self.texto_com_titulo(linha, destino)
        self.fala.falar(texto, True)
        return texto

    def _voltar_a_primeira(self, linha):
        if linha != self.lista.GetFirstSelected():
            self.sincronizar()      # a seleção muda e o leitor lê a linha
            return None
        texto = self.texto_da_linha(linha)
        self.fala.falar(texto, True)
        return texto

    def mover_linha(self, passo):
        total = self.lista.GetItemCount()
        atual = self.atual()
        if atual < 0 or not total:
            return None
        destino = max(0, min(total - 1, atual + passo))
        if destino == atual:
            texto = "%s linha." % ("Primeira" if passo < 0 else "Última")
        else:
            self.linha = destino
            self._destacar(destino)
            self.lista.EnsureVisible(destino)
            texto = self.texto_da_celula(destino, self._coluna_valida())
        self.fala.falar(texto, True)
        return texto

    # ---- textos ----
    def texto_da_celula(self, linha, coluna):
        return self.lista.GetItemText(linha, coluna).strip() or "vazio"

    def texto_com_titulo(self, linha, coluna):
        return "%s: %s" % (self.lista.GetColumn(coluna).GetText(),
                           self.texto_da_celula(linha, coluna))

    def texto_da_linha(self, linha):
        return ", ".join(self.texto_da_celula(linha, c)
                         for c in range(self.lista.GetColumnCount()))

    # ---- na tela ----
    def _destacar(self, n):
        self._tirar_destaque()
        if n != self.lista.GetFirstSelected():
            self.lista.SetItemBackgroundColour(n, _COR_CURSOR)
            self._destacada = n

    def _tirar_destaque(self):
        if 0 <= self._destacada < self.lista.GetItemCount():
            self.lista.SetItemBackgroundColour(self._destacada, wx.NullColour)
        self._destacada = -1

    def _mostrar_coluna(self, linha, coluna):
        """Rola a tabela até a coluna, se estiver fora da área visível."""
        if coluna == 0:
            esquerda = self.lista.GetItemRect(linha).x
            if esquerda < 0:
                self.lista.ScrollList(esquerda, 0)
            return
        area = wx.Rect()
        if not self.lista.GetSubItemRect(linha, coluna, area):
            return
        largura = self.lista.GetClientSize().width
        if area.x < 0:
            self.lista.ScrollList(area.x, 0)
        elif area.GetRight() > largura:
            self.lista.ScrollList(area.GetRight() - largura, 0)
