"""Comparações da sincronização, sem banco nem interface."""
import datetime

from financeiro import regras

# Acima disto o relógio do computador não é confiável para decidir sozinho
TOLERANCIA_RELOGIO = 120
# Alterações mais próximas que isto não dão para ordenar com segurança
EMPATE_SEGUNDOS = 10
LOCAL, SERVIDOR, PERGUNTAR = "local", "servidor", "perguntar"


def _instante(iso):
    try:
        momento = datetime.datetime.fromisoformat(iso)
    except (TypeError, ValueError):
        return None
    if momento.tzinfo is None:
        momento = momento.replace(tzinfo=datetime.timezone.utc)
    return momento.timestamp()


def relogio_confiavel(defasagem):
    return defasagem is not None and abs(defasagem) <= TOLERANCIA_RELOGIO


def decidir(editado_em, atualizado_no_servidor, defasagem, agora=None):
    """Vale a alteração mais recente. Devolve LOCAL, SERVIDOR ou PERGUNTAR.

    Pergunta à pessoa quando os horários não permitem afirmar: relógio
    desacertado agora, alteração "no futuro" (o relógio estava adiantado ao
    editar), hora ausente ou duas alterações quase juntas."""
    if not relogio_confiavel(defasagem):
        return PERGUNTAR
    local, servidor = _instante(editado_em), _instante(atualizado_no_servidor)
    if local is None or servidor is None:
        return PERGUNTAR
    agora = datetime.datetime.now(datetime.timezone.utc).timestamp() \
        if agora is None else agora
    local += defasagem
    if local > agora + defasagem + TOLERANCIA_RELOGIO:
        return PERGUNTAR
    if abs(local - servidor) < EMPATE_SEGUNDOS:
        return PERGUNTAR
    return LOCAL if local > servidor else SERVIDOR

CAMPOS = (("data_origem", "Data de origem"), ("data_vencimento", "Vencimento"),
          ("valor", "Valor"), ("banco", "Banco ou cartão"), ("forma", "Forma"),
          ("estabelecimento", "Estabelecimento"), ("parcela", "Parcela"),
          ("descricao", "Descrição"), ("categoria", "Categoria"),
          ("credor", "Credor"), ("devedor", "Devedor"), ("pago", "Situação"))


def _texto(valor):
    return "" if valor is None else valor


def assinatura(l, itens=None, pagamentos=None):
    """O conteúdo do lançamento, sem ids nem versão: serve para saber se duas
    cópias são iguais mesmo quando uma tem ids temporários."""
    itens = l.get("itens", []) if itens is None else itens
    pagamentos = l.get("pagamentos", []) if pagamentos is None else pagamentos
    return (
        _texto(l.get("data_origem")), _texto(l.get("data_vencimento")),
        round(float(l.get("valor") or 0), 2), _texto(l.get("banco")),
        _texto(l.get("forma")), _texto(l.get("estabelecimento")),
        _texto(l.get("parcela")), _texto(l.get("descricao")),
        _texto(l.get("categoria")), _texto(l.get("credor")),
        _texto(l.get("devedor")), bool(l.get("pago")),
        tuple((round(float(i.get("valor") or 0), 2), _texto(i.get("categoria")),
               _texto(i.get("descricao")), _texto(i.get("data_original")))
              for i in sorted(itens, key=lambda x: x.get("ordem") or 0)),
        tuple((_texto(p.get("banco")), _texto(p.get("forma")),
               round(float(p.get("valor") or 0), 2))
              for p in sorted(pagamentos, key=lambda x: x.get("ordem") or 0)),
    )


def versao_do_servidor(dados, lancamento_id):
    """O lançamento como veio no download, ou None se foi apagado lá."""
    l = next((x for x in dados["lancamentos"] if x["id"] == lancamento_id), None)
    if l is None:
        return None
    return dict(l, pago=bool(l.get("pago")),
                itens=[i for i in dados["itens"]
                       if i["lancamento_id"] == lancamento_id],
                pagamentos=[p for p in dados["pagamentos"]
                            if p["lancamento_id"] == lancamento_id])


def _mostrar(chave, valor):
    if chave == "valor":
        return regras.fmt_valor_com_sinal(float(valor or 0))
    if chave == "pago":
        return "Pago" if valor else "Pendente"
    if chave in ("data_origem", "data_vencimento"):
        return regras.fmt_data(valor)
    return valor or "vazio"


def _lista_de_itens(l):
    return ["%s, %s, %s" % (i.get("descricao") or "sem descrição",
                            i.get("categoria") or "sem categoria",
                            regras.fmt_valor_com_sinal(float(i.get("valor") or 0)))
            for i in sorted(l.get("itens", []), key=lambda x: x.get("ordem") or 0)]


def diferencas(local, servidor):
    """[(campo, neste computador, no servidor)] só do que difere."""
    saida = []
    for chave, rotulo in CAMPOS:
        a, b = local.get(chave), servidor.get(chave)
        if chave == "valor":
            igual = round(float(a or 0), 2) == round(float(b or 0), 2)
        elif chave == "pago":
            igual = bool(a) == bool(b)
        else:
            igual = _texto(a) == _texto(b)
        if not igual:
            saida.append((rotulo, _mostrar(chave, a), _mostrar(chave, b)))
    a, b = _lista_de_itens(local), _lista_de_itens(servidor)
    if a != b:
        saida.append(("Itens", "; ".join(a) or "nenhum", "; ".join(b) or "nenhum"))
    return saida
