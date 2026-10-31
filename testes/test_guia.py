"""O guia não pode citar o que não existe nem dizer só um leitor de tela."""
import re
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent / "financeiro"


def assuntos(arquivo):
    texto = (RAIZ / arquivo).read_text(encoding="utf-8")
    return re.findall(r"^# (.+)$", texto, re.M), texto


def test_guia_tem_os_assuntos_e_sem_marca_de_leitor():
    titulos, texto = assuntos("guia.txt")
    for esperado in ("Primeiros passos", "Atalhos", "Parcelar", "Documentos",
                     "Leitura por IA", "Sem internet e sincronização"):
        assert esperado in titulos
    assert "NVDA" not in texto


def test_atalhos_do_guia_existem_no_menu():
    _, texto = assuntos("guia.txt")
    bloco = texto.split("# Atalhos")[1].split("\n# ")[0]
    janela = (RAIZ / "janela.py").read_text(encoding="utf-8")
    for atalho in re.findall(r"^((?:Ctrl\+)(?:Shift\+)?[A-Z]|F\d)\b", bloco, re.M):
        assert "\\t" + atalho + '"' in janela, atalho


def test_novidades_tem_assunto():
    titulos, _ = assuntos("novidades.txt")
    assert titulos
