"""Regras de consulta, sem interface: as mesmas do site.

Filtros, resumo e saldos seguem aplicarFiltrosSilencioso, atualizarSaldos e
atualizarContas do index.html. Mudou lá, tem de mudar aqui.
"""
import calendar
from dataclasses import dataclass, field
import datetime
import math
import re
import unicodedata

SEM_FORMA = "sem_forma"
CARTAO_CREDITO = "cartao_credito"
CARTAO_DEBITO = "cartao_debito"
PIX = "pix"
BOLETO = "boleto"
DINHEIRO = "dinheiro"
TRANSFERENCIA = "transferencia"
OUTRA_FORMA = "outra"


@dataclass
class Filtros:
    busca: str = ""
    origem_de: str = ""
    origem_ate: str = ""
    venc_de: str = ""
    venc_ate: str = ""
    status: str = ""          # "", "pago" ou "pendente"
    tipo: str = ""            # "", "entrada" ou "saida"
    ordem: str = "recentes"   # "recentes" ou "antigos"
    banco: str = ""
    forma: str = ""
    estabelecimento: str = ""
    categoria: str = ""
    credor: str = ""
    devedor: str = ""


@dataclass
class Resumo:
    quantidade: int = 0
    entradas: float = 0.0
    saidas: float = 0.0
    total: float = 0.0
    pago: float = 0.0
    pendente: float = 0.0


@dataclass
class SituacaoConta:
    nome: str
    tipo: str
    saldo: float = 0.0
    limite_total: float = 0.0
    limite_usado: float = 0.0
    limite_restante: float = 0.0
    alerta: bool = False
    detalhe: str = field(default="")


def sem_acentos(texto):
    decomposto = unicodedata.normalize("NFD", texto or "")
    return "".join(c for c in decomposto
                   if unicodedata.category(c) != "Mn").casefold()


def classificar_forma(forma):
    """O banco tem grafias diferentes da mesma forma ("Cartão Crédito" e
    "Cartão de Crédito"); isto só decide o ícone, o texto mostrado não muda."""
    t = sem_acentos(forma).strip()
    if not t:
        return SEM_FORMA
    if "cartao" in t and "cred" in t:
        return CARTAO_CREDITO
    if "cartao" in t and "deb" in t:
        return CARTAO_DEBITO
    if "pix" in t:
        return PIX
    if "boleto" in t:
        return BOLETO
    if "dinheiro" in t:
        return DINHEIRO
    if "transfer" in t or "ted" in t or "doc" in t:
        return TRANSFERENCIA
    return OUTRA_FORMA


def fmt_valor(valor):
    """1234.5 -> "1.234,50"; negativo com sinal. Sem "R$": com ele, o leitor de
    tela nem sempre fala o sinal de menos."""
    sinal = "-" if valor < 0 else ""
    corpo = "{:,.2f}".format(abs(valor)).translate(
        str.maketrans({",": ".", ".": ","}))
    return sinal + corpo


def fmt_valor_com_sinal(valor):
    """Nas tabelas o sinal diz se é entrada ou saída: "+10,00", "-10,00"."""
    return ("+" if valor >= 0 else "") + fmt_valor(valor)


def ler_valor(texto):
    """Lê como se digita no Brasil: "1.234,50", "12,5", "12.50", "1.234".
    Levanta ValueError se não for um número."""
    t = (texto or "").replace("R$", "").replace(" ", "").strip()
    if not t:
        raise ValueError("vazio")
    if "," in t:
        t = t.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"[+-]?\d{1,3}(\.\d{3})+", t):
        t = t.replace(".", "")
    valor = float(t)
    if not math.isfinite(valor):
        raise ValueError(texto)
    return round(valor, 2)


def valor_com_tipo(valor, entrada):
    return abs(valor) if entrada else -abs(valor)


def normalizar_texto(texto):
    return re.sub(r"\s+", " ", sem_acentos(texto).strip())


def opcao_equivalente(campo, sugerido, opcoes):
    """A opção já cadastrada que corresponde ao que a IA leu, como no site:
    ignora maiúsculas, acentos e espaços; "Next Bradesco" vale "Next"."""
    alvo = normalizar_texto(sugerido)
    if not alvo:
        return None
    for opcao in opcoes:
        if normalizar_texto(opcao) == alvo:
            return opcao
    if campo == "banco" and re.search(r"\bnext\b", alvo):
        for opcao in opcoes:
            if normalizar_texto(opcao) == "next":
                return opcao
    return None


def correcoes_aprendidas(extracao, dados, usou_itens):
    """O que a pessoa corrigiu do que a IA sugeriu, como no site. Devolve
    [{campo, valor_errado, valor_correto, contexto}]."""
    sugestoes = dict(
        banco=extracao.get("banco"), estabelecimento=extracao.get("estabelecimento"),
        forma=extracao.get("forma_pagamento"),
        categoria=None if usou_itens else extracao.get("categoria_sugerida"),
        credor=extracao.get("credor"), devedor=extracao.get("devedor"))
    contexto = (dados.get("estabelecimento") or "").strip() or None
    saida = []
    for campo, sugerido in sugestoes.items():
        salvo = dados.get(campo)
        if sugerido and salvo and (str(sugerido).strip().lower()
                                   != str(salvo).strip().lower()):
            saida.append(dict(campo=campo, valor_errado=sugerido,
                              valor_correto=salvo, contexto=contexto))
    return saida


def categoria_e_fatura(categoria):
    return (categoria or "").strip().lower() == "cartão de crédito"


def lancamento_espelho_fatura(dados, cartao):
    """O segundo lançamento do pagamento de fatura: entrada no cartão, para
    recuperar o limite."""
    descricao = "Pagamento de fatura"
    if dados.get("banco"):
        descricao += " — via " + dados["banco"]
    if dados.get("descricao"):
        descricao += " (%s)" % dados["descricao"]
    return dict(
        data_origem=dados["data_origem"], data_vencimento=dados["data_vencimento"],
        valor=abs(dados["valor"]), banco=cartao, estabelecimento=None,
        forma=dados.get("forma"), parcela=None, descricao=descricao,
        categoria=dados.get("categoria"), credor=dados.get("credor"),
        devedor=dados.get("devedor"), pago=dados["pago"])


MESES = ("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
         "agosto", "setembro", "outubro", "novembro", "dezembro")
DIAS_DA_SEMANA = ("segunda-feira", "terça-feira", "quarta-feira", "quinta-feira",
                  "sexta-feira", "sábado", "domingo")


def ler_data(texto):
    """Aceita 25/10/2026, 25/10/26, 25102026 e 251026. Vazio vale None; data que
    não existe levanta ValueError."""
    numeros = re.sub(r"\D", "", texto or "")
    if not (texto or "").strip():
        return None
    if len(numeros) == 6:
        numeros = numeros[:4] + "20" + numeros[4:]
    if len(numeros) != 8:
        raise ValueError(texto)
    return datetime.datetime.strptime(numeros, "%d%m%Y").date().isoformat()


def data_por_extenso(iso):
    data = datetime.date.fromisoformat(iso)
    return "%d de %s de %d, %s" % (data.day, MESES[data.month - 1], data.year,
                                   DIAS_DA_SEMANA[data.weekday()])


def formatar_digitacao(texto):
    """O que está sendo digitado, com as barras nos lugares: "2510" vira "25/10/"."""
    numeros = re.sub(r"\D", "", texto)[:8]
    saida = numeros[:2]
    if len(numeros) >= 2:
        saida += "/" + numeros[2:4]
    if len(numeros) >= 4:
        saida += "/" + numeros[4:]
    return saida


def rolar_data(iso, parte, passo):
    """Parte 0 é o dia, 1 é o mês e 2 é o ano."""
    if parte == 0:
        return (datetime.date.fromisoformat(iso)
                + datetime.timedelta(days=passo)).isoformat()
    return avancar_meses(iso, passo if parte == 1 else passo * 12)


def avancar_meses(iso, meses):
    """Mantém o dia quando possível; senão, o último dia do mês de destino."""
    ano, mes, dia = (int(p) for p in iso[:10].split("-"))
    total = ano * 12 + (mes - 1) + meses
    ano, mes = total // 12, total % 12 + 1
    return "%04d-%02d-%02d" % (ano, mes, min(dia, calendar.monthrange(ano, mes)[1]))


def calcular_parcelas(total, quantidade, diferenca_na_primeira=False):
    """Valores absolutos, em centavos exatos: a sobra vai numa parcela só."""
    centavos = round(abs(total) * 100)
    base, resto = divmod(centavos, quantidade)
    parcelas = [base] * quantidade
    parcelas[0 if diferenca_na_primeira else -1] += resto
    return [p / 100 for p in parcelas]


def ratear_itens(valores, parcelas):
    """Divide cada item entre as parcelas, em centavos exatos: a soma de cada
    parcela bate com o valor dela e a soma de cada item bate com o original.
    Desconto (negativo) também é dividido. Devolve uma lista por parcela, com um
    valor por item, na mesma ordem."""
    itens = [round(v * 100) for v in valores]
    totais = [round(p * 100) for p in parcelas]
    n = len(totais)
    # Cada item começa igual em todas; a sobra de cada um é de 0 a n-1 centavos
    bases = [c // n for c in itens]
    sobras = [c - b * n for c, b in zip(itens, bases)]
    faltam = [t - sum(bases) for t in totais]
    # Vai passando pelas parcelas que ainda têm falta, para espalhar os centavos
    vagas = []
    while any(f > 0 for f in faltam):
        for j in range(n):
            if faltam[j] > 0:
                vagas.append(j)
                faltam[j] -= 1
    tabela = [list(bases) for _ in range(n)]
    posicao = 0
    for i, sobra in enumerate(sobras):
        for j in vagas[posicao:posicao + sobra]:
            tabela[j][i] += 1
        posicao += sobra
    return [[c / 100 for c in linha] for linha in tabela]


def itens_orientados(lanc):
    """Valores dos itens como o site entende: positivo é item normal e negativo
    é desconto. Há lançamentos antigos gravados com os itens negativos; como o
    sinal do total vem de Entrada ou Saída, basta orientar pela soma."""
    valores = [i["valor"] for i in lanc["itens"]]
    if sum(valores) < 0:
        valores = [-v for v in valores]
    return valores


def fmt_data(iso):
    if not iso:
        return "—"
    partes = iso[:10].split("-")
    return "/".join(reversed(partes)) if len(partes) == 3 else iso


def eh_entrada(lanc):
    return lanc["valor"] >= 0


def descricao_de(lanc):
    """Com itens, a descrição vem deles, como na tabela do site."""
    if lanc["itens"]:
        textos = [i["descricao"] for i in lanc["itens"] if i["descricao"]]
        if textos:
            juntos = " / ".join(textos)
            # Com itens não há descrição própria para levar a etiqueta da parcela
            parcela = lanc.get("parcela")
            return "%s — %s" % (juntos, parcela) if parcela else juntos
    return lanc["descricao"] or lanc["estabelecimento"] or "Sem descrição"


def categorias_de(lanc):
    if lanc["itens"]:
        vistas = []
        for item in lanc["itens"]:
            if item["categoria"] and item["categoria"] not in vistas:
                vistas.append(item["categoria"])
        return ", ".join(vistas)
    return lanc["categoria"]


def _bate_busca(lanc, termo):
    campos = [lanc["descricao"], lanc["estabelecimento"], lanc["categoria"],
              lanc["credor"], lanc["devedor"], lanc["banco"], lanc["forma"],
              fmt_valor(lanc["valor"]), fmt_data(lanc["data_origem"]),
              fmt_data(lanc["data_vencimento"])]
    for item in lanc["itens"]:
        campos += [item["descricao"], item["categoria"]]
    return any(termo in sem_acentos(c) for c in campos)


def filtrar(lancamentos, f):
    """Na ordem escolhida. No mesmo dia, sempre a ordem de criação."""
    termo = sem_acentos(f.busca).strip()
    saida = []
    for l in lancamentos:
        origem, venc = l["data_origem"] or "", l["data_vencimento"] or ""
        if f.origem_de and origem < f.origem_de:
            continue
        if f.origem_ate and origem > f.origem_ate:
            continue
        if f.venc_de and venc and venc < f.venc_de:
            continue
        if f.venc_ate and venc and venc > f.venc_ate:
            continue
        if f.status == "pago" and not l["pago"]:
            continue
        if f.status == "pendente" and l["pago"]:
            continue
        if f.tipo == "entrada" and not eh_entrada(l):
            continue
        if f.tipo == "saida" and eh_entrada(l):
            continue
        if f.banco and l["banco"] != f.banco and not any(
                p["banco"] == f.banco for p in l["pagamentos"]):
            continue
        if f.forma and l["forma"] != f.forma and not any(
                p["forma"] == f.forma for p in l["pagamentos"]):
            continue
        if f.estabelecimento and l["estabelecimento"] != f.estabelecimento:
            continue
        if f.categoria and l["categoria"] != f.categoria and not any(
                i["categoria"] == f.categoria for i in l["itens"]):
            continue
        if f.credor and l["credor"] != f.credor:
            continue
        if f.devedor and l["devedor"] != f.devedor:
            continue
        if termo and not _bate_busca(l, termo):
            continue
        saida.append(l)
    # No mesmo dia, na ordem de criação: assim as parcelas ficam 1/3, 2/3, 3/3.
    # Id negativo é temporário, e cada novo é menor que o anterior.
    saida.sort(key=lambda l: (l["id"] < 0, abs(l["id"])))
    saida.sort(key=lambda l: l["data_origem"] or "", reverse=f.ordem != "antigos")
    return saida


def tem_filtro_da_janela(f):
    """Os filtros que não aparecem na tela inicial, para ela poder avisar."""
    return any((f.busca.strip(), f.origem_de, f.origem_ate, f.venc_de, f.venc_ate,
                f.banco, f.forma, f.estabelecimento, f.categoria, f.credor, f.devedor))


def valor_para_banco(lanc, nome):
    if lanc["pagamentos"]:
        soma = sum(p["valor"] for p in lanc["pagamentos"]
                   if p["banco"] == nome)
        return -soma if lanc["valor"] < 0 else soma
    return lanc["valor"] if lanc["banco"] == nome else 0.0


def envolve_banco(lanc, nome):
    return lanc["banco"] == nome or any(
        p["banco"] == nome for p in lanc["pagamentos"])


def situacao_contas(contas, lancamentos, nomes_cadastrados=()):
    """Sempre com todos os lançamentos, sem os filtros da tela. Na conta
    corrente só o que está pago mexe no saldo; no cartão, o pendente também
    consome o limite, porque costuma ser fatura em aberto. Banco cadastrado nas
    listas e ainda sem configuração aparece como não configurado, como no site."""
    saida = []
    configuradas = {c["nome_banco"] for c in contas}
    contas = list(contas) + [
        dict(nome_banco=n, tipo=None, saldo_inicial=None, limite_total=None)
        for n in nomes_cadastrados if n not in configuradas]
    for c in sorted(contas, key=lambda x: sem_acentos(x["nome_banco"])):
        nome, tipo = c["nome_banco"], c["tipo"]
        doc = [l for l in lancamentos if envolve_banco(l, nome)]
        s = SituacaoConta(nome=nome, tipo=tipo or "")
        if tipo == "conta_corrente":
            s.saldo = (c["saldo_inicial"] or 0) + sum(
                valor_para_banco(l, nome) for l in doc if l["pago"])
            s.alerta = s.saldo < 0
            s.detalhe = "Saldo atual: " + fmt_valor(s.saldo)
        elif tipo == "cartao_credito":
            s.limite_usado = max(0.0, -sum(
                valor_para_banco(l, nome) for l in doc))
            s.limite_total = c["limite_total"] or 0
            s.limite_restante = s.limite_total - s.limite_usado
            s.alerta = s.limite_restante < 0
            s.detalhe = "Limite restante: %s de %s (usado: %s)" % (
                fmt_valor(s.limite_restante), fmt_valor(s.limite_total),
                fmt_valor(s.limite_usado))
        saida.append(s)
    return saida


def resumir(lancamentos):
    r = Resumo(quantidade=len(lancamentos))
    for l in lancamentos:
        v = l["valor"]
        r.total += v
        if v >= 0:
            r.entradas += v
        else:
            r.saidas += v
        if l["pago"]:
            r.pago += v
        else:
            r.pendente += v
    return r
