"""Exportar e importar Excel, no mesmo formato do site, e achar duplicatas."""
import datetime

import openpyxl
import openpyxl.utils

from financeiro import regras

COLUNAS = ("Data Origem", "Data Vencimento", "Valor (R$)", "Banco / Cartão",
           "Estabelecimento", "Forma de Pagamento", "Parcela", "Descrição",
           "Categoria", "Credor", "Devedor", "Status", "Tipo")
LARGURAS = (14, 16, 12, 18, 22, 20, 10, 36, 18, 20, 20, 10, 12)


def _baixo(texto):
    return (texto or "").strip().lower()


def _dois(valor):
    return "%.2f" % float(valor or 0)


def assinatura(l):
    """A mesma identidade do site (assinaturaLancamento): duas cópias com tudo
    igual são duplicatas. Com itens, descrição e categoria ficam de fora, porque
    a planilha os troca por textos gerados."""
    itens = l.get("itens") or []
    pagamentos = l.get("pagamentos") or []
    tem_itens = bool(itens)
    base = "|".join((
        l.get("data_origem") or "", l.get("data_vencimento") or "",
        _dois(l.get("valor")), _baixo(l.get("banco")),
        _baixo(l.get("estabelecimento")), _baixo(l.get("forma")),
        _baixo(l.get("parcela")),
        "" if tem_itens else _baixo(l.get("descricao")),
        "" if tem_itens else _baixo(l.get("categoria")),
        _baixo(l.get("credor")), _baixo(l.get("devedor"))))
    sub_itens = ";".join(sorted("|".join((
        _dois(i.get("valor")), _baixo(i.get("categoria")),
        _baixo(i.get("descricao")))) for i in itens))
    sub_pag = ";".join(sorted("|".join((
        _baixo(p.get("banco")), _baixo(p.get("forma")),
        _dois(p.get("valor")))) for p in pagamentos))
    return base + "##" + sub_itens + "##" + sub_pag


def ids_duplicados(lancamentos):
    """Mantém o mais antigo de cada grupo. Lançamento que ainda não subiu fica de
    fora: seu id é temporário e ele pode ser o único no servidor."""
    vistos, repetidos = set(), []
    for l in sorted((x for x in lancamentos if x["id"] > 0), key=lambda x: x["id"]):
        marca = assinatura(l)
        if marca in vistos:
            repetidos.append(l["id"])
        else:
            vistos.add(marca)
    return repetidos


def _data(iso):
    return "" if not iso else regras.fmt_data(iso)


def _linhas_de(l):
    itens, pagamentos = l["itens"], l["pagamentos"]
    if itens or pagamentos:
        yield (_data(l["data_origem"]), _data(l["data_vencimento"]), l["valor"],
               "(dividido)" if pagamentos else l["banco"], l["estabelecimento"],
               "(dividido)" if pagamentos else l["forma"],
               l["parcela"] or "À vista",
               "(lançamento com %d itens)" % len(itens) if itens else l["descricao"],
               "(múltiplas)" if itens else l["categoria"], l["credor"], l["devedor"],
               "Pago" if l["pago"] else "Pendente", "Principal")
        for n, i in enumerate(itens, 1):
            yield (_data(i["data_original"]), "", i["valor"], "", "", "", "",
                   i["descricao"], i["categoria"], "", "", "", "Item %d" % n)
        for n, p in enumerate(pagamentos, 1):
            yield ("", "", p["valor"], p["banco"], "", p["forma"], "", "", "", "",
                   "", "", "Pagamento %d" % n)
    else:
        yield (_data(l["data_origem"]), _data(l["data_vencimento"]), l["valor"],
               l["banco"], l["estabelecimento"], l["forma"],
               l["parcela"] or "À vista", l["descricao"], l["categoria"],
               l["credor"], l["devedor"], "Pago" if l["pago"] else "Pendente",
               "Simples")


def exportar(lancamentos, caminho):
    livro = openpyxl.Workbook()
    aba = livro.active
    aba.title = "Lançamentos"
    aba.append(COLUNAS)
    ordenados = sorted(lancamentos, key=lambda l: (l["data_origem"] or "",
                                                   l["id"] < 0, abs(l["id"])))
    for l in ordenados:
        for linha in _linhas_de(l):
            aba.append(linha)
    for n, largura in enumerate(LARGURAS):
        aba.column_dimensions[openpyxl.utils.get_column_letter(n + 1)].width = largura
    r = regras.resumir(lancamentos)
    resumo = livro.create_sheet("Resumo")
    resumo.append(("Item", "Valor"))
    resumo.append(("Total de Registros", r.quantidade))
    resumo.append(("Total Geral (R$)", _dois(r.total)))
    resumo.append(("Total Pago (R$)", _dois(r.pago)))
    resumo.append(("Total Pendente (R$)", _dois(r.pendente)))
    resumo.column_dimensions["A"].width = 22
    resumo.column_dimensions["B"].width = 16
    livro.save(caminho)


def _ler_data(valor):
    if isinstance(valor, datetime.datetime):
        return valor.date().isoformat()
    if isinstance(valor, datetime.date):
        return valor.isoformat()
    texto = str(valor or "").strip()
    for formato in ("%d/%m/%Y", "%Y-%m-%d"):
        try:
            return datetime.datetime.strptime(texto, formato).date().isoformat()
        except ValueError:
            pass
    if isinstance(valor, (int, float)):     # data serial do Excel
        return (datetime.date(1899, 12, 30)
                + datetime.timedelta(days=int(valor))).isoformat()
    return None


def _numero(valor):
    if isinstance(valor, (int, float)):
        return float(valor)
    try:
        return float(str(valor or "0").replace(",", "."))
    except ValueError:
        return 0.0


def _texto(valor):
    return "" if valor is None else str(valor).strip()


def _parcela(valor):
    texto = _texto(valor)
    return "" if texto.lower() in ("à vista", "a vista") else texto


def ler(caminho):
    """Lançamentos da primeira aba, com itens e pagamentos agrupados. Levanta
    ValueError se a planilha não tiver o cabeçalho esperado."""
    livro = openpyxl.load_workbook(caminho, read_only=True, data_only=True)
    try:
        linhas = livro.worksheets[0].iter_rows(values_only=True)
        cabecalho = next(linhas, None)
        if not cabecalho or "Valor (R$)" not in cabecalho:
            raise ValueError("A planilha não tem as colunas esperadas.")
        nomes = [_texto(c) for c in cabecalho]
        saida, atual = [], None
        for valores in linhas:
            if not any(v not in (None, "") for v in valores):
                continue
            row = dict(zip(nomes, valores))
            tipo = _texto(row.get("Tipo"))
            if tipo.startswith("Item "):
                if atual:
                    atual["itens"].append(dict(
                        valor=_numero(row.get("Valor (R$)")),
                        categoria=_texto(row.get("Categoria")) or None,
                        descricao=_texto(row.get("Descrição")) or None,
                        data_original=_ler_data(row.get("Data Origem"))))
                continue
            if tipo.startswith("Pagamento "):
                if atual:
                    atual["pagamentos"].append(dict(
                        banco=_texto(row.get("Banco / Cartão")) or None,
                        forma=_texto(row.get("Forma de Pagamento")) or None,
                        valor=_numero(row.get("Valor (R$)"))))
                continue
            banco = _texto(row.get("Banco / Cartão"))
            forma = _texto(row.get("Forma de Pagamento"))
            atual = dict(
                data_origem=_ler_data(row.get("Data Origem")),
                data_vencimento=_ler_data(row.get("Data Vencimento")),
                valor=_numero(row.get("Valor (R$)")),
                banco=None if banco == "(dividido)" else banco or None,
                estabelecimento=_texto(row.get("Estabelecimento")) or None,
                forma=None if forma == "(dividido)" else forma or None,
                parcela=_parcela(row.get("Parcela")) or None,
                descricao=_texto(row.get("Descrição")) or None,
                categoria=_texto(row.get("Categoria")) or None,
                credor=_texto(row.get("Credor")) or None,
                devedor=_texto(row.get("Devedor")) or None,
                pago=_texto(row.get("Status")).lower() == "pago",
                itens=[], pagamentos=[])
            saida.append(atual)
        return saida
    finally:
        livro.close()


def novos_e_repetidos(lidos, existentes):
    marcas = {assinatura(l) for l in existentes}
    novos = [l for l in lidos if assinatura(l) not in marcas]
    return novos, len(lidos) - len(novos)


def opcoes_de(lancamentos):
    """{campo: {valores}} para acrescentar às listas, como o site faz."""
    saida = {c: set() for c in ("banco", "forma", "estabelecimento", "categoria",
                                "credor", "devedor")}
    for l in lancamentos:
        for campo, valores in saida.items():
            if l.get(campo):
                valores.add(l[campo])
        for i in l["itens"]:
            if i["categoria"]:
                saida["categoria"].add(i["categoria"])
        for p in l["pagamentos"]:
            if p["banco"]:
                saida["banco"].add(p["banco"])
            if p["forma"]:
                saida["forma"].add(p["forma"])
    return saida
