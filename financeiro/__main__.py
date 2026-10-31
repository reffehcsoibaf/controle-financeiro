import logging
import logging.handlers
import os
import sys

import wx

from financeiro import __version__, atualizacao, config, instalacao, instalador
from financeiro.banco import Banco
from financeiro.janela import Janela
from financeiro.nuvem import Nuvem


def testar_pacote():
    """Confere, sem abrir janela, se o empacotamento levou o que o programa usa."""
    import openpyxl  # noqa: F401
    import xlrd  # noqa: F401
    from financeiro.fala import Fala
    from financeiro.gerenciar import ler_assuntos
    app = wx.App(False)
    try:
        assert ler_assuntos("guia.txt") and ler_assuntos("novidades.txt")
        assert Fala().lib is not None
        banco = Banco(config.caminho_banco())
        banco.fechar()
    finally:
        del app
    return 0


def preparar_copia():
    """Na versão empacotada: limpa o que uma atualização deixou e, se instalada,
    mantém a versão certa em Aplicativos instalados."""
    programa = config.pasta_do_programa()
    if not programa:
        return
    atualizacao.limpar_restos(programa)
    if instalacao.modo(programa) == "instalado":
        try:
            instalacao.registrar(programa)
        except OSError as e:
            logging.warning("registro da versão: %r", e)


def main():
    # Sem terminal, as bibliotecas que escrevem nestes canais não podem falhar
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
    if "--desinstalar" in sys.argv[1:]:
        return instalador.desinstalar()
    if "--testar-pacote" in sys.argv[1:]:
        return testar_pacote()
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.handlers.RotatingFileHandler(
            os.path.join(config.pasta_dados(), "financeiro.log"),
            maxBytes=1_000_000, backupCount=2, encoding="utf-8")])
    sys.excepthook = lambda *e: logging.critical("erro fatal", exc_info=e)
    logging.info("versão %s, %s", __version__, instalacao.modo())
    preparar_copia()
    app = wx.App(False)
    unica = wx.SingleInstanceChecker("ControleFinanceiroWindows")
    if unica.IsAnotherRunning():
        wx.MessageBox("O Controle Financeiro já está aberto.",
                      "Controle Financeiro", wx.OK | wx.ICON_INFORMATION)
        return 0
    banco = Banco(config.caminho_banco())
    janela = Janela(banco, Nuvem())
    janela.Show()
    wx.CallAfter(janela.iniciar)
    app.MainLoop()
    banco.fechar()
    return 0


if __name__ == "__main__":
    sys.exit(main())
