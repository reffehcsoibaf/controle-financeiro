"""A tela do instalador: Instalar, Cópia portátil, Guia de uso e Fechar. O mesmo
módulo pergunta o que for preciso ao desinstalar."""
import os
import subprocess
import sys

import wx

from financeiro import DESENVOLVEDOR, NOME, __version__, atualizacao, config, instalacao
from financeiro.dialogos import Progresso

TEXTO = (
    "O %s é um programa para controlar entradas, saídas, contas e cartões, feito "
    "para funcionar bem com leitor de tela. Funciona sem internet e sincroniza com "
    "o site.\n\n"
    "Versão %s. Desenvolvedor: %s.\n\n"
    "Instalar copia o programa para este computador e cria os atalhos. A cópia "
    "portátil cria uma pasta que guarda o programa e os dados juntos, por exemplo "
    "em um pendrive." % (NOME, __version__, DESENVOLVEDOR))


class DialogoPasta(wx.Dialog):
    """Escolhe a pasta e as opções."""

    def __init__(self, pai, instalar, pasta_inicial):
        super().__init__(pai, title="Instalar" if instalar else "Criar cópia portátil",
                         style=wx.DEFAULT_DIALOG_STYLE)
        if instalar:
            explicacao = ("O programa será copiado para a pasta abaixo, sem precisar "
                          "de administrador, e aparecerá em Aplicativos instalados.")
            rotulo = "Pasta de instalação"
        else:
            explicacao = ("Será criada a pasta %s dentro do lugar escolhido. Os dados "
                          "e o login ficam nela. Em outro computador, será preciso "
                          "entrar na conta de novo." % atualizacao.PASTA)
            rotulo = "Criar dentro da pasta"
        caixa = wx.BoxSizer(wx.VERTICAL)
        texto = wx.StaticText(self, label=explicacao)
        texto.Wrap(460)
        caixa.Add(texto, 0, wx.ALL, 10)
        caixa.Add(wx.StaticText(self, label=rotulo), 0, wx.LEFT | wx.RIGHT, 10)
        self.pasta = wx.TextCtrl(self, value=pasta_inicial, name=rotulo, size=(360, -1))
        procurar = wx.Button(self, label="&Procurar...")
        linha = wx.BoxSizer(wx.HORIZONTAL)
        linha.Add(self.pasta, 1, wx.RIGHT, 6)
        linha.Add(procurar)
        caixa.Add(linha, 0, wx.EXPAND | wx.LEFT | wx.RIGHT, 10)
        self.atalho_menu = self.atalho_area = self.abrir = None
        if instalar:
            self.atalho_menu = wx.CheckBox(self, label="Atalho no menu &Iniciar")
            self.atalho_menu.SetValue(True)
            self.atalho_area = wx.CheckBox(self, label="Atalho na área de &trabalho")
            self.abrir = wx.CheckBox(self, label="&Abrir o programa ao terminar")
            self.abrir.SetValue(True)
            for opcao in (self.atalho_menu, self.atalho_area, self.abrir):
                caixa.Add(opcao, 0, wx.LEFT | wx.RIGHT | wx.TOP, 10)
        botoes = wx.BoxSizer(wx.HORIZONTAL)
        ok = wx.Button(self, wx.ID_OK, "&Instalar" if instalar else "&Criar")
        ok.SetDefault()
        botoes.Add(ok, 0, wx.RIGHT, 8)
        botoes.Add(wx.Button(self, wx.ID_CANCEL, "Cancelar"))
        caixa.Add(botoes, 0, wx.ALL | wx.ALIGN_RIGHT, 10)
        self.SetSizerAndFit(caixa)
        procurar.Bind(wx.EVT_BUTTON, self._procurar)
        ok.Bind(wx.EVT_BUTTON, self._confirmar)
        self.CentreOnParent()
        self.pasta.SetFocus()

    def _procurar(self, _evt):
        with wx.DirDialog(self, "Escolha a pasta",
                          defaultPath=self.pasta.GetValue()) as dlg:
            if dlg.ShowModal() == wx.ID_OK:
                self.pasta.SetValue(dlg.GetPath())

    def _confirmar(self, evt):
        if self.pasta.GetValue().strip():
            evt.Skip()
        else:
            wx.MessageBox("Escolha a pasta.", self.GetTitle(),
                          wx.OK | wx.ICON_WARNING, self)
            self.pasta.SetFocus()


def _executar(pai, titulo, trabalho):
    barra = Progresso(pai, titulo)
    resultado = barra.executar(trabalho)
    erro = barra.erro
    barra.Destroy()
    return resultado, erro


class BoasVindas(wx.Dialog):
    def __init__(self, pacote):
        super().__init__(None, title="Instalador do %s" % NOME,
                         style=wx.DEFAULT_DIALOG_STYLE)
        self.pacote = pacote
        caixa = wx.BoxSizer(wx.VERTICAL)
        texto = wx.StaticText(self, label=TEXTO)
        texto.Wrap(460)
        caixa.Add(texto, 0, wx.ALL, 12)
        self.instalar = wx.Button(self, label="&Instalar")
        self.portatil = wx.Button(self, label="Cópia &portátil")
        self.guia = wx.Button(self, label="&Guia de uso")
        self.fechar = wx.Button(self, wx.ID_CANCEL, "&Fechar")
        for botao in (self.instalar, self.portatil, self.guia, self.fechar):
            caixa.Add(botao, 0, wx.EXPAND | wx.LEFT | wx.RIGHT | wx.BOTTOM, 12)
        self.SetSizerAndFit(caixa)
        self.instalar.SetDefault()
        self.instalar.SetFocus()
        self.CentreOnScreen()
        self.instalar.Bind(wx.EVT_BUTTON, self._instalar)
        self.portatil.Bind(wx.EVT_BUTTON, self._criar_portatil)
        self.guia.Bind(wx.EVT_BUTTON, self._ver_guia)

    def _instalar(self, _evt):
        dlg = DialogoPasta(self, True, instalacao.pasta_de_instalacao_padrao())
        if dlg.ShowModal() != wx.ID_OK:
            dlg.Destroy()
            return
        pasta = dlg.pasta.GetValue().strip()
        menu, area = dlg.atalho_menu.GetValue(), dlg.atalho_area.GetValue()
        abrir = dlg.abrir.GetValue()
        dlg.Destroy()

        def trabalho(progresso):
            with instalacao.extrair_pacote(
                    self.pacote, instalacao.faixa(progresso, 0, 40)) as programa:
                return instalacao.instalar(
                    programa, pasta, menu, area,
                    progresso=instalacao.faixa(progresso, 40, 100))

        resultado, erro = _executar(self, "Instalando", trabalho)
        if erro:
            wx.MessageBox("Não consegui instalar. %s" % erro, NOME,
                          wx.OK | wx.ICON_WARNING, self)
            return
        exe, avisos = resultado
        wx.MessageBox("\n\n".join(["O %s foi instalado em %s."
                                   % (NOME, os.path.dirname(exe))] + avisos),
                      NOME, wx.OK | wx.ICON_INFORMATION, self)
        if abrir:
            # A variável faz o programa novo não herdar a pasta temporária deste
            # instalador
            subprocess.Popen([exe], cwd=os.path.dirname(exe), env=dict(
                os.environ, PYINSTALLER_RESET_ENVIRONMENT="1"))
        self.EndModal(wx.ID_OK)

    def _criar_portatil(self, _evt):
        dlg = DialogoPasta(self, False, os.path.expanduser("~"))
        if dlg.ShowModal() != wx.ID_OK:
            dlg.Destroy()
            return
        pasta = dlg.pasta.GetValue().strip()
        dlg.Destroy()

        def trabalho(progresso):
            with instalacao.extrair_pacote(
                    self.pacote, instalacao.faixa(progresso, 0, 40)) as programa:
                return instalacao.criar_portatil(
                    programa, pasta, instalacao.faixa(progresso, 40, 100))

        alvo, erro = _executar(self, "Criando a cópia portátil", trabalho)
        if erro:
            wx.MessageBox("Não consegui criar a cópia. %s" % erro, NOME,
                          wx.OK | wx.ICON_WARNING, self)
            return
        wx.MessageBox("Cópia portátil criada em %s. Para usar, abra o %s que está "
                      "nessa pasta." % (alvo, atualizacao.EXE), NOME,
                      wx.OK | wx.ICON_INFORMATION, self)

    def _ver_guia(self, _evt):
        from financeiro.gerenciar import Guia
        dlg = Guia(self, "Guia de uso", "guia.txt")
        dlg.ShowModal()
        dlg.Destroy()


def testar_pacote(pacote):
    """Confere, sem abrir janela, se o instalador tem o que precisa."""
    from financeiro.gerenciar import ler_assuntos
    if not os.path.isfile(pacote) or not ler_assuntos("guia.txt"):
        return 1
    return 0


def main():
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    pacote = os.path.join(base, atualizacao.ZIP)
    if "--testar-pacote" in sys.argv[1:]:
        return testar_pacote(pacote)
    app = wx.App(False)     # precisa continuar referenciado
    dlg = BoasVindas(pacote)
    dlg.ShowModal()
    dlg.Destroy()
    del app
    return 0


def desinstalar():
    """`ControleFinanceiro.exe --desinstalar`, chamado por Aplicativos instalados."""
    app = wx.App(False)     # precisa continuar referenciado
    if instalacao.modo() != "instalado":
        wx.MessageBox("Esta cópia não é uma instalação.", NOME,
                      wx.OK | wx.ICON_INFORMATION)
        return 1
    unica = wx.SingleInstanceChecker("ControleFinanceiroWindows")
    if unica.IsAnotherRunning():
        wx.MessageBox("Feche o %s antes de desinstalar." % NOME, NOME,
                      wx.OK | wx.ICON_WARNING)
        return 1
    if wx.MessageBox("Desinstalar o %s?" % NOME, NOME,
                     wx.YES_NO | wx.NO_DEFAULT | wx.ICON_QUESTION) != wx.YES:
        return 0
    apagar = wx.MessageBox(
        "Apagar também a cópia dos dados deste computador, que fica em %s? Os dados "
        "online continuam guardados, mas alterações ainda não enviadas seriam "
        "perdidas." % config.dados_do_computador(), NOME,
        wx.YES_NO | wx.NO_DEFAULT | wx.ICON_WARNING) == wx.YES
    try:
        instalacao.desinstalar(apagar_dados=apagar)
    except Exception as e:
        wx.MessageBox("Não consegui desinstalar. %s" % e, NOME, wx.OK | wx.ICON_ERROR)
        return 1
    wx.MessageBox("O %s foi desinstalado." % NOME, NOME, wx.OK | wx.ICON_INFORMATION)
    del app
    return 0
