"""Preferências da pessoa, em JSON na pasta de dados."""
import json
import logging
import os

from financeiro import colunas, config

log = logging.getLogger("financeiro.preferencias")


def _caminho():
    return os.path.join(config.pasta_dados(), "preferencias.json")


def _ler():
    try:
        with open(_caminho(), encoding="utf-8") as f:
            dados = json.load(f)
        return dados if isinstance(dados, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        log.warning("preferências ilegíveis: %r", e)
        return {}


def ler_colunas():
    return colunas.normalizar(_ler().get("colunas"))


def ler_falar_quantidade():
    return _ler().get("falar_quantidade_filtro", True) is not False


def ler_falar_total():
    return _ler().get("falar_total_filtro", False) is True


def salvar_falar_total(falar):
    _gravar("falar_total_filtro", bool(falar))


def ler_ordem():
    return "antigos" if _ler().get("ordem_tabela") == "antigos" else "recentes"


def salvar_ordem(ordem):
    _gravar("ordem_tabela", ordem)


def ler_tipo():
    tipo = _ler().get("tipo_tabela")
    return tipo if tipo in ("entrada", "saida") else ""


def ler_situacao():
    situacao = _ler().get("situacao_tabela")
    return situacao if situacao in ("pendente", "pago") else ""


def ler_posicao():
    """(id do lançamento, linha, coluna). A linha serve quando o lançamento
    não aparece mais na tabela."""
    posicao = _ler().get("posicao_tabela")
    if not isinstance(posicao, dict):
        return None, 0, 0

    def inteiro(chave):
        valor = posicao.get(chave)
        return valor if type(valor) is int and valor >= 0 else 0

    lancamento = posicao.get("lancamento")
    return (lancamento if type(lancamento) is int else None,
            inteiro("linha"), inteiro("coluna"))


def salvar_tela(tipo, situacao, lancamento, linha, coluna):
    """Numa gravação só, porque acontece ao fechar o programa."""
    dados = _ler()
    dados.update(tipo_tabela=tipo, situacao_tabela=situacao, posicao_tabela=dict(
        lancamento=lancamento, linha=linha, coluna=coluna))
    _escrever(dados)


def ler_atualizacao_ignorada():
    return str(_ler().get("atualizacao_ignorada") or "")


def salvar_atualizacao_ignorada(versao):
    _gravar("atualizacao_ignorada", versao)


def salvar_colunas(escolha):
    _gravar("colunas", escolha)


def salvar_falar_quantidade(falar):
    _gravar("falar_quantidade_filtro", bool(falar))


def _gravar(chave, valor):
    dados = _ler()
    dados[chave] = valor
    _escrever(dados)


def _escrever(dados):
    temporario = _caminho() + ".tmp"
    with open(temporario, "w", encoding="utf-8") as f:
        json.dump(dados, f, ensure_ascii=False, indent=2)
    os.replace(temporario, _caminho())
