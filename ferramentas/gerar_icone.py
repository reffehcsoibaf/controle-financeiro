"""Desenha o ícone do programa, sem abrir janela. Só precisa rodar se o desenho mudar.

Uso: venv\\Scripts\\python ferramentas\\gerar_icone.py
"""
import io
import os
import struct

import wx

DESTINO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "empacotar",
                       "financeiro.ico")
TAMANHOS = (16, 32, 48, 256)


def desenhar(tamanho):
    bitmap = wx.Bitmap(tamanho, tamanho, 32)
    dc = wx.MemoryDC(bitmap)
    dc.SetBackground(wx.Brush(wx.Colour(20, 110, 70)))
    dc.Clear()
    fonte = wx.Font(wx.FontInfo(max(int(tamanho * 0.42), 6)).Bold())
    dc.SetFont(fonte)
    dc.SetTextForeground(wx.WHITE)
    texto = "R$"
    largura, altura = dc.GetTextExtent(texto)
    dc.DrawText(texto, (tamanho - largura) // 2, (tamanho - altura) // 2)
    dc.SelectObject(wx.NullBitmap)
    return bitmap.ConvertToImage()


def png_de(imagem):
    saida = io.BytesIO()
    imagem.SaveFile(saida, wx.BITMAP_TYPE_PNG)
    return saida.getvalue()


def main():
    app = wx.App(False)
    pngs = [png_de(desenhar(t)) for t in TAMANHOS]
    deslocamento = 6 + 16 * len(pngs)
    with open(DESTINO, "wb") as f:
        f.write(struct.pack("<HHH", 0, 1, len(pngs)))
        for tamanho, png in zip(TAMANHOS, pngs):
            lado = 0 if tamanho == 256 else tamanho
            f.write(struct.pack("<BBBBHHII", lado, lado, 0, 0, 1, 32, len(png),
                                deslocamento))
            deslocamento += len(png)
        for png in pngs:
            f.write(png)
    del app
    print(DESTINO)


if __name__ == "__main__":
    main()
