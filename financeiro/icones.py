"""Ícones desenhados no próprio programa e cores da lista.

Cor e ícone só reforçam: a mesma informação está sempre escrita numa coluna.
"""
import ctypes

import wx

from financeiro import regras

TAMANHO = 16
FUNDO = wx.Colour(255, 0, 255)      # vira transparente

# Contraste de pelo menos 4,5:1 sobre branco
COR_SAIDA = wx.Colour(176, 0, 32)
COR_ENTRADA = wx.Colour(11, 110, 46)
_AZUL = wx.Colour(30, 80, 170)
_CINZA = wx.Colour(90, 90, 90)
_VERDE = wx.Colour(11, 110, 46)
_ROXO = wx.Colour(100, 50, 150)


def alto_contraste():
    """No alto contraste do Windows, valem as cores do sistema."""
    class HighContrast(ctypes.Structure):
        _fields_ = [("cbSize", ctypes.c_uint), ("dwFlags", ctypes.c_uint),
                    ("lpszDefaultScheme", ctypes.c_wchar_p)]
    hc = HighContrast(ctypes.sizeof(HighContrast), 0, None)
    ok = ctypes.windll.user32.SystemParametersInfoW(
        0x42, ctypes.sizeof(hc), ctypes.byref(hc), 0)
    return bool(ok and hc.dwFlags & 1)


def _desenhar(funcao):
    bmp = wx.Bitmap(TAMANHO, TAMANHO)
    dc = wx.MemoryDC(bmp)
    dc.SetBackground(wx.Brush(FUNDO))
    dc.Clear()
    funcao(dc)
    dc.SelectObject(wx.NullBitmap)
    bmp.SetMaskColour(FUNDO)
    return bmp


def _pincel(dc, cor):
    dc.SetPen(wx.Pen(cor))
    dc.SetBrush(wx.Brush(cor))


def _cartao(cor):
    def f(dc):
        _pincel(dc, cor)
        dc.DrawRoundedRectangle(1, 3, 14, 10, 2)
        dc.SetPen(wx.Pen(wx.WHITE))
        dc.SetBrush(wx.Brush(wx.WHITE))
        dc.DrawRectangle(1, 5, 14, 2)
        dc.DrawRectangle(3, 10, 4, 1)
    return f


def _pix(dc):
    _pincel(dc, wx.Colour(0, 120, 110))
    dc.DrawPolygon([wx.Point(8, 1), wx.Point(15, 8), wx.Point(8, 15),
                    wx.Point(1, 8)])


def _boleto(dc):
    _pincel(dc, wx.Colour(40, 40, 40))
    for x, largura in ((1, 1), (3, 2), (6, 1), (8, 1), (10, 2), (13, 2)):
        dc.DrawRectangle(x, 3, largura, 10)


def _dinheiro(dc):
    _pincel(dc, _VERDE)
    dc.DrawRectangle(1, 4, 14, 8)
    dc.SetBrush(wx.Brush(wx.WHITE))
    dc.SetPen(wx.Pen(wx.WHITE))
    dc.DrawCircle(8, 8, 2)


def _transferencia(dc):
    _pincel(dc, _ROXO)
    dc.DrawRectangle(2, 4, 9, 2)
    dc.DrawPolygon([wx.Point(10, 1), wx.Point(14, 5), wx.Point(10, 9)])
    dc.DrawRectangle(5, 10, 9, 2)
    dc.DrawPolygon([wx.Point(6, 7), wx.Point(2, 11), wx.Point(6, 15)])


def _sem_forma(dc):
    _pincel(dc, _CINZA)
    dc.DrawRectangle(4, 7, 8, 2)


def _outra(dc):
    _pincel(dc, _CINZA)
    dc.DrawCircle(8, 8, 4)


def _entrada(dc):
    _pincel(dc, COR_ENTRADA)
    dc.DrawPolygon([wx.Point(8, 2), wx.Point(14, 12), wx.Point(2, 12)])


def _saida(dc):
    _pincel(dc, COR_SAIDA)
    dc.DrawPolygon([wx.Point(2, 4), wx.Point(14, 4), wx.Point(8, 14)])


def _conta(dc):
    _pincel(dc, _AZUL)
    dc.DrawPolygon([wx.Point(8, 1), wx.Point(15, 6), wx.Point(1, 6)])
    for x in (3, 7, 11):
        dc.DrawRectangle(x, 7, 2, 5)
    dc.DrawRectangle(1, 13, 14, 2)


DESENHOS = {
    "entrada": _entrada, "saida": _saida, "conta": _conta,
    regras.CARTAO_CREDITO: _cartao(_AZUL),
    regras.CARTAO_DEBITO: _cartao(wx.Colour(0, 110, 130)),
    regras.PIX: _pix, regras.BOLETO: _boleto, regras.DINHEIRO: _dinheiro,
    regras.TRANSFERENCIA: _transferencia, regras.SEM_FORMA: _sem_forma,
    regras.OUTRA_FORMA: _outra,
}


def lista_de_imagens():
    """(wx.ImageList, {nome: índice})."""
    imagens = wx.ImageList(TAMANHO, TAMANHO, True)
    return imagens, {nome: imagens.Add(_desenhar(f))
                     for nome, f in DESENHOS.items()}
