"""Endereços e pastas do programa."""
import os

# A chave publishable é pública (a mesma do site); quem protege os dados é o RLS.
SUPABASE_URL = "https://zlclakzjktpsbpfkltxa.supabase.co"
SUPABASE_KEY = "sb_publishable_o5Rn3Qp_C3boiYCRqQ5Ykw_2sNd3KiQ"
MODULO = "financeiro"

# Porta fixa porque o endereço de retorno do login precisa estar autorizado no
# Supabase exatamente como é.
PORTA_LOGIN = 53917
URL_RETORNO = "http://127.0.0.1:%d/callback" % PORTA_LOGIN


def pasta_dados():
    """FINANCEIRO_DADOS troca a pasta (testes e capturas de tela)."""
    pasta = os.environ.get("FINANCEIRO_DADOS") or os.path.join(
        os.environ.get("APPDATA") or os.path.expanduser("~"),
        "ControleFinanceiro")
    os.makedirs(pasta, exist_ok=True)
    return pasta


def caminho_banco():
    return os.path.join(pasta_dados(), "financeiro.db")


def caminho_sessao():
    return os.path.join(pasta_dados(), "sessao.bin")
