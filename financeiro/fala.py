"""Fala direta pelo leitor de tela (hoje, o NVDA). Sem ele, não faz nada."""
import ctypes
import importlib.util
import logging
import os

log = logging.getLogger("financeiro.fala")


class Fala:
    def __init__(self):
        self.lib = None
        try:
            spec = importlib.util.find_spec("accessible_output2")
            if spec and spec.origin:
                caminho = os.path.join(os.path.dirname(spec.origin), "lib",
                                       "nvdaControllerClient64.dll")
                self.lib = ctypes.WinDLL(caminho)
                self.lib.nvdaController_speakText.argtypes = [ctypes.c_wchar_p]
        except Exception as e:
            log.info("fala direta indisponível: %r", e)

    def falar(self, texto, interromper=False):
        if not texto or self.lib is None:
            return
        try:
            if self.lib.nvdaController_testIfRunning() != 0:
                return
            if interromper:
                self.lib.nvdaController_cancelSpeech()
            self.lib.nvdaController_speakText(texto)
        except Exception as e:
            log.warning("erro ao falar: %r", e)
