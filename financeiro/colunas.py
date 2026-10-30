"""As colunas que a tabela de lançamentos pode mostrar, sem interface."""
from collections import namedtuple

from financeiro import regras

Coluna = namedtuple("Coluna", "chave titulo largura direita valor")


def _itens(l):
    n = len(l["itens"])
    return "" if not n else "1 item" if n == 1 else "%d itens" % n


TODAS = (
    Coluna("descricao", "Descrição", 250, False, regras.descricao_de),
    Coluna("itens", "Itens", 65, False, _itens),
    Coluna("valor", "Valor", 125, True,
           lambda l: regras.fmt_valor_com_sinal(l["valor"])),
    Coluna("vencimento", "Vencimento", 95, False,
           lambda l: regras.fmt_data(l["data_vencimento"])),
    Coluna("situacao", "Situação", 120, False,
           lambda l: "Pago" if l["pago"] else "Pendente"),
    Coluna("forma", "Forma", 150, False, lambda l: l["forma"] or "Sem forma"),
    Coluna("conta", "Conta", 130, False, lambda l: l["banco"] or "Sem conta"),
    Coluna("origem", "Data de origem", 105, False,
           lambda l: regras.fmt_data(l["data_origem"])),
    Coluna("parcela", "Parcela", 80, False,
           lambda l: l["parcela"] or "À vista"),
    Coluna("estabelecimento", "Estabelecimento", 160, False,
           lambda l: l["estabelecimento"] or "—"),
    Coluna("categoria", "Categoria", 150, False,
           lambda l: regras.categorias_de(l) or "—"),
    Coluna("credor", "Credor", 130, False, lambda l: l["credor"] or "—"),
    Coluna("devedor", "Devedor", 130, False, lambda l: l["devedor"] or "—"),
)
POR_CHAVE = {c.chave: c for c in TODAS}
FIXA = "situacao"     # sempre a primeira: é a que tem a caixa de pago
PADRAO_VISIVEIS = (FIXA, "descricao", "itens", "valor", "vencimento",
                   "forma", "conta", "origem")


def padrao():
    """[[chave, visível], ...] na ordem da tabela; ocultas vão ao fim."""
    return ([[k, True] for k in PADRAO_VISIVEIS]
            + [[c.chave, False] for c in TODAS
               if c.chave not in PADRAO_VISIVEIS])


def normalizar(salvas):
    """Aceita o que veio do arquivo: ignora o que não existe mais, e coluna
    nova de versão futura entra oculta. Situação fica sempre em primeiro. Sem
    nenhuma outra visível, volta ao padrão."""
    saida, vistas = [[FIXA, True]], {FIXA}
    if isinstance(salvas, list):
        for par in salvas:
            if (isinstance(par, (list, tuple)) and len(par) == 2
                    and par[0] in POR_CHAVE and par[0] not in vistas):
                saida.append([par[0], bool(par[1])])
                vistas.add(par[0])
    if not any(v for _, v in saida[1:]):
        return padrao()
    saida += [[c.chave, False] for c in TODAS if c.chave not in vistas]
    return saida


def visiveis(escolha):
    return [POR_CHAVE[k] for k, v in escolha if v]


def linha(l, cols):
    return tuple(c.valor(l) for c in cols)


def imagem(coluna, l):
    """Nome do ícone da célula, ou None."""
    if coluna.chave == "valor":
        return "entrada" if regras.eh_entrada(l) else "saida"
    if coluna.chave == "forma":
        return regras.classificar_forma(l["forma"])
    return None
