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


def salvar_colunas(escolha):
    _gravar("colunas", escolha)


def salvar_falar_quantidade(falar):
    _gravar("falar_quantidade_filtro", bool(falar))


def _gravar(chave, valor):
    dados = _ler()
    dados[chave] = valor
    temporario = _caminho() + ".tmp"
    with open(temporario, "w", encoding="utf-8") as f:
        json.dump(dados, f, ensure_ascii=False, indent=2)
    os.replace(temporario, _caminho())
