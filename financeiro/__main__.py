import logging
import logging.handlers
import os
import sys

import wx

from financeiro import config
from financeiro.banco import Banco
from financeiro.janela import Janela
from financeiro.nuvem import Nuvem


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.handlers.RotatingFileHandler(
            os.path.join(config.pasta_dados(), "financeiro.log"),
            maxBytes=1_000_000, backupCount=2, encoding="utf-8")])
    sys.excepthook = lambda *e: logging.critical("erro fatal", exc_info=e)
    app = wx.App(False)
    unica = wx.SingleInstanceChecker("ControleFinanceiroWindows")
    if unica.IsAnotherRunning():
        wx.MessageBox("O Controle Financeiro já está aberto.",
                      "Controle Financeiro", wx.OK | wx.ICON_INFORMATION)
        return
    banco = Banco(config.caminho_banco())
    janela = Janela(banco, Nuvem())
    janela.Show()
    wx.CallAfter(janela.iniciar)
    app.MainLoop()
    banco.fechar()


if __name__ == "__main__":
    main()
