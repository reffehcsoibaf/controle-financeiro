"""Endereços e pastas do programa."""
import os
import sys

# A chave publishable é pública (a mesma do site); quem protege os dados é o RLS.
SUPABASE_URL = "https://zlclakzjktpsbpfkltxa.supabase.co"
SUPABASE_KEY = "sb_publishable_o5Rn3Qp_C3boiYCRqQ5Ykw_2sNd3KiQ"
MODULO = "financeiro"
URL_LEITURA_IA = ("https://controle-financeiro.fabioscheffer.workers.dev"
                  "/api/ler-documento")
BUCKET_DOCUMENTOS = "documentos"

# Porta fixa porque o endereço de retorno do login precisa estar autorizado no
# Supabase exatamente como é.
PORTA_LOGIN = 53917
URL_RETORNO = "http://127.0.0.1:%d/callback" % PORTA_LOGIN


PASTA_DADOS_PORTATIL = "dados"


def pasta_do_programa():
    """A pasta do executável; None rodando pelo Python."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return None


def dados_portateis(programa=None):
    """A pasta `dados` ao lado do executável é o que faz uma cópia ser portátil."""
    programa = programa or pasta_do_programa()
    if programa:
        pasta = os.path.join(programa, PASTA_DADOS_PORTATIL)
        if os.path.isdir(pasta):
            return pasta
    return None


def dados_do_computador():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"),
                        "ControleFinanceiro")


def pasta_dados():
    """FINANCEIRO_DADOS troca a pasta (testes e capturas de tela)."""
    pasta = (os.environ.get("FINANCEIRO_DADOS") or dados_portateis()
             or dados_do_computador())
    os.makedirs(pasta, exist_ok=True)
    return pasta


def caminho_banco():
    return os.path.join(pasta_dados(), "financeiro.db")


def pasta_documentos():
    """Cópias locais: as que esperam envio e as já abertas neste computador."""
    pasta = os.path.join(pasta_dados(), "documentos")
    os.makedirs(pasta, exist_ok=True)
    return pasta


def caminho_sessao():
    return os.path.join(pasta_dados(), "sessao.bin")
