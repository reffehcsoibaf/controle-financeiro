"""Arquivos: cópia local e preparação para a leitura por IA, sem interface."""
import base64
import mimetypes
import os
import re
import shutil

import openpyxl

from financeiro import config

LIMITE_PLANILHA = 50000


def tipo_do_arquivo(caminho):
    return mimetypes.guess_type(caminho)[0] or ""


def caminho_local(doc_id, nome):
    seguro = re.sub(r"[^\w.-]", "_", nome)
    return os.path.join(config.pasta_documentos(), "%s_%s" % (doc_id, seguro))


def guardar_copia(origem, doc_id, nome):
    destino = caminho_local(doc_id, nome)
    shutil.copyfile(origem, destino)
    return destino


def apagar_copias():
    shutil.rmtree(config.pasta_documentos(), ignore_errors=True)


def preparar_para_ia(caminho):
    """(tipo, conteúdo, mime), como o site envia ao Worker. Levanta ValueError
    com a mensagem para a pessoa se o formato não servir."""
    mime = tipo_do_arquivo(caminho)
    ext = os.path.splitext(caminho)[1].lower().lstrip(".")
    if mime.startswith("image/") or ext == "pdf":
        with open(caminho, "rb") as f:
            conteudo = base64.b64encode(f.read()).decode()
        if ext == "pdf":
            return "pdf", conteudo, "application/pdf"
        return "imagem", conteudo, mime
    if ext == "xlsx":
        return "texto", _planilha_em_texto(caminho), "text/plain"
    if ext == "xls":
        raise ValueError("Salve a planilha como XLSX para ler com a IA.")
    if ext in ("txt", "xml") or mime.startswith("text/"):
        with open(caminho, encoding="utf-8", errors="replace") as f:
            conteudo = f.read()
        if ext == "xml":
            return "xml", conteudo, mime or "text/xml"
        return "texto", conteudo, mime or "text/plain"
    raise ValueError("Formato não suportado. Use imagem, PDF, TXT, XML ou XLSX.")


def _planilha_em_texto(caminho):
    try:
        livro = openpyxl.load_workbook(caminho, read_only=True, data_only=True)
    except Exception:
        raise ValueError("Não foi possível ler a planilha. Verifique se o "
                         "arquivo não está corrompido.") from None
    try:
        partes = []
        for aba in livro.worksheets:
            partes.append("--- Planilha: %s ---" % aba.title)
            for linha in aba.iter_rows(values_only=True):
                partes.append(",".join("" if v is None else str(v) for v in linha))
            partes.append("")
    finally:
        livro.close()
    texto = "\n".join(partes)
    if not any(l and not l.startswith("---") and l.strip(",") for l in partes):
        raise ValueError("A planilha parece estar vazia.")
    return texto[:LIMITE_PLANILHA]
