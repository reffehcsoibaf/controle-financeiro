"""Cópia local dos dados, em SQLite, com a fila do que falta enviar.

Toda alteração é gravada primeiro aqui e fica na fila até chegar ao servidor.
Cada sincronização troca a cópia inteira numa transação só (se falhar, a cópia
anterior continua intacta) e depois reaplica o que ainda está na fila.

Lançamento criado aqui tem id negativo até o servidor dar o definitivo.
"""
import datetime
import json
import sqlite3
import uuid

VERSAO_ESQUEMA = 3


def agora_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

ESQUEMA = """
CREATE TABLE IF NOT EXISTS lancamentos (
    id INTEGER PRIMARY KEY, data_origem TEXT, data_vencimento TEXT,
    valor REAL NOT NULL, banco TEXT, estabelecimento TEXT, forma TEXT,
    parcela TEXT, descricao TEXT, categoria TEXT, credor TEXT, devedor TEXT,
    pago INTEGER NOT NULL, lancamento_vinculado_id INTEGER, updated_at TEXT);
CREATE TABLE IF NOT EXISTS itens (
    id INTEGER PRIMARY KEY, lancamento_id INTEGER NOT NULL, ordem INTEGER,
    valor REAL NOT NULL, categoria TEXT, descricao TEXT, data_original TEXT);
CREATE TABLE IF NOT EXISTS pagamentos (
    id INTEGER PRIMARY KEY, lancamento_id INTEGER NOT NULL, ordem INTEGER,
    banco TEXT, forma TEXT, valor REAL NOT NULL);
CREATE TABLE IF NOT EXISTS contas (
    id TEXT PRIMARY KEY, nome_banco TEXT, tipo TEXT,
    saldo_inicial REAL, limite_total REAL);
CREATE TABLE IF NOT EXISTS opcoes (
    id INTEGER PRIMARY KEY, campo TEXT, valor TEXT);
CREATE TABLE IF NOT EXISTS documentos (
    id TEXT PRIMARY KEY, lancamento_id INTEGER, nome_arquivo TEXT,
    mime_type TEXT, tamanho_bytes INTEGER, caminho TEXT, criado_em TEXT);
CREATE TABLE IF NOT EXISTS meta (chave TEXT PRIMARY KEY, valor TEXT);
CREATE TABLE IF NOT EXISTS pendencias_pago (
    lancamento_id INTEGER PRIMARY KEY, pago INTEGER NOT NULL,
    base_updated_at TEXT, editado_em TEXT);
CREATE TABLE IF NOT EXISTS pendencias_lancamento (
    lancamento_id INTEGER PRIMARY KEY, operacao TEXT NOT NULL,
    client_uuid TEXT, base_updated_at TEXT, versao INTEGER NOT NULL,
    conflito INTEGER NOT NULL DEFAULT 0, servidor TEXT, editado_em TEXT);
CREATE TABLE IF NOT EXISTS correcoes_ia (
    id INTEGER PRIMARY KEY, campo TEXT, valor_errado TEXT, valor_correto TEXT,
    contexto TEXT, criado_em TEXT);
CREATE TABLE IF NOT EXISTS fila_operacoes (
    id INTEGER PRIMARY KEY AUTOINCREMENT, tipo TEXT NOT NULL, dados TEXT NOT NULL);
"""

# Colunas em que uma opção renomeada precisa ser trocada
CAMPOS_DE_LISTA = ("banco", "forma", "estabelecimento", "categoria", "credor",
                   "devedor")

_COLUNAS = {
    "lancamentos": ("id", "data_origem", "data_vencimento", "valor", "banco",
                    "estabelecimento", "forma", "parcela", "descricao",
                    "categoria", "credor", "devedor", "pago",
                    "lancamento_vinculado_id", "updated_at"),
    "itens": ("id", "lancamento_id", "ordem", "valor", "categoria",
              "descricao", "data_original"),
    "pagamentos": ("id", "lancamento_id", "ordem", "banco", "forma", "valor"),
    "contas": ("id", "nome_banco", "tipo", "saldo_inicial", "limite_total"),
    "opcoes": ("id", "campo", "valor"),
    "documentos": ("id", "lancamento_id", "nome_arquivo", "mime_type",
                   "tamanho_bytes", "caminho", "criado_em"),
    "correcoes_ia": ("id", "campo", "valor_errado", "valor_correto", "contexto",
                     "criado_em"),
}
CAMPOS_FORMULARIO = ("data_origem", "data_vencimento", "valor", "banco",
                     "estabelecimento", "forma", "descricao", "categoria",
                     "credor", "devedor", "pago")


def _inserir(con, tabela, linhas):
    colunas = _COLUNAS[tabela]
    con.executemany(
        "INSERT INTO %s (%s) VALUES (%s)" % (
            tabela, ",".join(colunas), ",".join("?" * len(colunas))),
        [tuple(linha.get(c) for c in colunas) for linha in linhas])


class Banco:
    def __init__(self, caminho):
        self._con = sqlite3.connect(caminho, check_same_thread=False)
        self._con.row_factory = sqlite3.Row
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.executescript(ESQUEMA)
        # Fila de versões anteriores: sem hora guardada, conflito vai para a pessoa
        for tabela in ("pendencias_pago", "pendencias_lancamento"):
            colunas = {l["name"] for l in self._con.execute(
                "PRAGMA table_info(%s)" % tabela)}
            if "editado_em" not in colunas:
                self._con.execute(
                    "ALTER TABLE %s ADD COLUMN editado_em TEXT" % tabela)
        self._con.execute("PRAGMA user_version=%d" % VERSAO_ESQUEMA)
        self._con.commit()

    def fechar(self):
        self._con.close()

    # ---- download ----
    def substituir_tudo(self, dados, quando):
        """`dados`: {tabela: [linhas]}. Tudo ou nada."""
        with self._con:
            guardados = self._copia_das_pendencias()
            for tabela in _COLUNAS:
                self._con.execute("DELETE FROM %s" % tabela)
                _inserir(self._con, tabela, dados.get(tabela, []))
            self._con.execute(
                "INSERT OR REPLACE INTO meta VALUES ('ultima_sincronizacao', ?)",
                (quando,))
            self._reaplicar(guardados)
            for op in self.operacoes():
                self._aplicar_operacao(op["tipo"], op["dados"])

    def _copia_das_pendencias(self):
        copia = {}
        for p in self._con.execute(
                "SELECT * FROM pendencias_lancamento").fetchall():
            i = p["lancamento_id"]
            copia[i] = (p["operacao"], {
                tabela: [dict(l) for l in self._con.execute(
                    "SELECT * FROM %s WHERE %s = ?" % (
                        tabela, "id" if tabela == "lancamentos"
                        else "lancamento_id"), (i,))]
                for tabela in ("lancamentos", "itens", "pagamentos")})
        return copia

    def _reaplicar(self, guardados):
        """O que ainda não subiu continua valendo na tela."""
        for i, (operacao, linhas) in guardados.items():
            self._apagar_linhas(i)
            if operacao == "salvar":
                for tabela, conteudo in linhas.items():
                    _inserir(self._con, tabela, conteudo)
        self._con.execute(
            "UPDATE lancamentos SET pago = (SELECT p.pago FROM "
            "pendencias_pago p WHERE p.lancamento_id = lancamentos.id) "
            "WHERE id IN (SELECT lancamento_id FROM pendencias_pago)")

    def _apagar_linhas(self, i):
        self._con.execute("DELETE FROM lancamentos WHERE id = ?", (i,))
        self._con.execute("DELETE FROM itens WHERE lancamento_id = ?", (i,))
        self._con.execute("DELETE FROM pagamentos WHERE lancamento_id = ?", (i,))

    def apagar_tudo(self):
        """Ao sair da conta, nada dos dados fica no computador."""
        with self._con:
            for tabela in _COLUNAS:
                self._con.execute("DELETE FROM %s" % tabela)
            self._con.execute("DELETE FROM meta")
            self._con.execute("DELETE FROM pendencias_pago")
            self._con.execute("DELETE FROM pendencias_lancamento")
            self._con.execute("DELETE FROM fila_operacoes")

    def ultima_sincronizacao(self):
        linha = self._con.execute(
            "SELECT valor FROM meta WHERE chave='ultima_sincronizacao'"
        ).fetchone()
        return linha["valor"] if linha else ""

    # ---- leitura ----
    def lancamentos(self):
        """Lançamentos com itens, pagamentos e documentos dentro de cada um."""
        por_id = {}
        for linha in self._con.execute("SELECT * FROM lancamentos"):
            l = dict(linha)
            l["pago"] = bool(l["pago"])
            for campo in ("banco", "estabelecimento", "forma", "parcela",
                          "descricao", "categoria", "credor", "devedor"):
                l[campo] = l[campo] or ""
            l.update(itens=[], pagamentos=[], documentos=[])
            por_id[l["id"]] = l
        for tabela in ("itens", "pagamentos"):
            for linha in self._con.execute(
                    "SELECT * FROM %s ORDER BY ordem, id" % tabela):
                dono = por_id.get(linha["lancamento_id"])
                if dono is not None:
                    d = dict(linha)
                    for campo in ("banco", "forma", "categoria", "descricao"):
                        if campo in d:
                            d[campo] = d[campo] or ""
                    dono[tabela].append(d)
        for linha in self._con.execute("SELECT * FROM documentos"):
            dono = por_id.get(linha["lancamento_id"])
            if dono is not None:
                dono["documentos"].append(dict(linha))
        return list(por_id.values())

    def contas(self):
        return [dict(l) for l in self._con.execute(
            "SELECT * FROM contas ORDER BY nome_banco")]

    def opcoes(self, campo):
        return [l["valor"] for l in self._con.execute(
            "SELECT valor FROM opcoes WHERE campo=? ORDER BY valor", (campo,))]

    def documentos(self):
        return [dict(l) for l in self._con.execute(
            "SELECT * FROM documentos ORDER BY criado_em DESC")]

    def correcoes(self):
        return [dict(l) for l in self._con.execute(
            "SELECT * FROM correcoes_ia ORDER BY criado_em DESC, id DESC")]

    # ---- fila de operações: listas, contas, vínculos, documentos, correções ----
    def operacoes(self):
        return [dict(id=l["id"], tipo=l["tipo"], dados=json.loads(l["dados"]))
                for l in self._con.execute(
                    "SELECT * FROM fila_operacoes ORDER BY id").fetchall()]

    def registrar_operacao(self, tipo, /, **dados):
        """Vale aqui na hora e fica na fila até chegar ao servidor."""
        with self._con:
            self._con.execute(
                "INSERT INTO fila_operacoes (tipo, dados) VALUES (?, ?)",
                (tipo, json.dumps(dados, ensure_ascii=False)))
            self._aplicar_operacao(tipo, dados)

    def concluir_operacoes(self, ids):
        with self._con:
            self._con.executemany("DELETE FROM fila_operacoes WHERE id = ?",
                                  [(i,) for i in ids])

    def _aplicar_operacao(self, tipo, d):
        """Efeito local. Roda ao registrar e de novo depois de cada download,
        enquanto a operação não subiu; por isso repetir não pode mudar nada."""
        con = self._con
        if tipo == "opcao_add":
            if not con.execute("SELECT 1 FROM opcoes WHERE campo=? AND valor=?",
                               (d["campo"], d["valor"])).fetchone():
                con.execute("INSERT INTO opcoes VALUES (?, ?, ?)",
                            (self._novo_id("opcoes"), d["campo"], d["valor"]))
        elif tipo == "opcao_del":
            con.execute("DELETE FROM opcoes WHERE campo=? AND valor=?",
                        (d["campo"], d["valor"]))
            if d["campo"] == "banco":
                con.execute("DELETE FROM contas WHERE nome_banco=?", (d["valor"],))
        elif tipo == "opcao_renomear":
            self._aplicar_renomear(d["campo"], d["antigo"], d["novo"])
        elif tipo == "conta_salvar":
            con.execute("DELETE FROM contas WHERE nome_banco=?", (d["nome"],))
            con.execute("INSERT INTO contas VALUES (?, ?, ?, ?, ?)",
                        ("local-" + d["nome"], d["nome"], d["tipo"],
                         d.get("saldo_inicial"), d.get("limite_total")))
        elif tipo == "conta_excluir":
            con.execute("DELETE FROM contas WHERE nome_banco=?", (d["nome"],))
        elif tipo == "vincular":
            a, b = d["ids"]
            for origem, destino in ((a, b), (b, a)):
                con.execute("UPDATE lancamentos SET lancamento_vinculado_id = ? "
                            "WHERE id = ?", (destino, origem))
        elif tipo == "docs_mover":
            para, *de = d["ids"]
            con.executemany("UPDATE documentos SET lancamento_id = ? "
                            "WHERE lancamento_id = ?", [(para, i) for i in de])
        elif tipo == "doc_guardar":
            if not con.execute("SELECT 1 FROM documentos WHERE id=?",
                               (d["doc_id"],)).fetchone():
                _inserir(con, "documentos", [dict(
                    id=d["doc_id"], nome_arquivo=d["nome"], mime_type=d["mime"],
                    tamanho_bytes=d["tamanho"], caminho=d["caminho"],
                    criado_em=d["criado_em"])])
        elif tipo == "doc_vincular":
            ids = d["ids"]
            con.execute("UPDATE documentos SET lancamento_id = ? WHERE id = ?",
                        (ids[0] if ids else None, d["doc_id"]))
        elif tipo == "doc_excluir":
            con.execute("DELETE FROM documentos WHERE id=?", (d["doc_id"],))
        elif tipo == "correcao_add":
            if not con.execute(
                    "SELECT 1 FROM correcoes_ia WHERE campo=? AND valor_errado=? "
                    "AND valor_correto=?",
                    (d["campo"], d["valor_errado"], d["valor_correto"])).fetchone():
                _inserir(con, "correcoes_ia", [dict(
                    d, id=self._novo_id("correcoes_ia"), criado_em=agora_iso())])
        elif tipo == "correcao_editar":
            con.execute("UPDATE correcoes_ia SET valor_errado=?, valor_correto=?, "
                        "contexto=? WHERE id=?",
                        (d["valor_errado"], d["valor_correto"], d["contexto"], d["id"]))
        elif tipo == "correcao_excluir":
            con.execute("DELETE FROM correcoes_ia WHERE id=?", (d["id"],))
        elif tipo == "correcoes_limpar":
            con.execute("DELETE FROM correcoes_ia")

    def _aplicar_renomear(self, campo, antigo, novo):
        if campo not in CAMPOS_DE_LISTA:
            raise ValueError(campo)
        con = self._con
        con.execute("UPDATE opcoes SET valor=? WHERE campo=? AND valor=?",
                    (novo, campo, antigo))
        con.execute("UPDATE lancamentos SET %s=? WHERE %s=?" % (campo, campo),
                    (novo, antigo))
        if campo == "categoria":
            con.execute("UPDATE itens SET categoria=? WHERE categoria=?",
                        (novo, antigo))
        if campo in ("banco", "forma"):
            con.execute("UPDATE pagamentos SET %s=? WHERE %s=?" % (campo, campo),
                        (novo, antigo))
        if campo == "banco":
            con.execute("UPDATE contas SET nome_banco=? WHERE nome_banco=?",
                        (novo, antigo))

    def _trocar_id_nas_operacoes(self, velho, novo):
        for op in self.operacoes():
            if velho in op["dados"].get("ids", ()):
                op["dados"]["ids"] = [novo if i == velho else i
                                      for i in op["dados"]["ids"]]
                self._con.execute(
                    "UPDATE fila_operacoes SET dados = ? WHERE id = ?",
                    (json.dumps(op["dados"], ensure_ascii=False), op["id"]))

    # ---- alterações locais ----
    def _novo_id(self, tabela):
        menor = self._con.execute(
            "SELECT MIN(id) AS m FROM %s" % tabela).fetchone()["m"]
        return min(menor or 0, 0) - 1

    def _pendencia(self, i):
        return self._con.execute(
            "SELECT * FROM pendencias_lancamento WHERE lancamento_id = ?",
            (i,)).fetchone()

    def _registrar(self, i, operacao):
        """Põe na fila, herdando a versão-base do servidor de quem já estava."""
        atual = self._pendencia(i)
        if atual:
            self._con.execute(
                "UPDATE pendencias_lancamento SET operacao = ?, editado_em = ?, "
                "versao = versao + 1 WHERE lancamento_id = ?",
                (operacao, agora_iso(), i))
            return
        pago = self._con.execute(
            "SELECT base_updated_at FROM pendencias_pago WHERE lancamento_id = ?",
            (i,)).fetchone()
        linha = self._con.execute(
            "SELECT updated_at FROM lancamentos WHERE id = ?", (i,)).fetchone()
        base = (pago["base_updated_at"] if pago
                else linha["updated_at"] if linha else None)
        self._con.execute(
            "DELETE FROM pendencias_pago WHERE lancamento_id = ?", (i,))
        self._con.execute(
            "INSERT INTO pendencias_lancamento (lancamento_id, operacao, "
            "client_uuid, base_updated_at, versao, editado_em) "
            "VALUES (?, ?, ?, ?, 1, ?)",
            (i, operacao, str(uuid.uuid4()) if i < 0 else None, base,
             agora_iso()))

    def salvar_lancamento(self, dados, itens, lancamento_id=None, pagamentos=None):
        """Cria (sem id) ou atualiza. Parcela e vínculo não mudam aqui, nem os
        pagamentos, se vierem como None. Devolve o id, temporário se for novo."""
        with self._con:
            campos = {c: dados.get(c) for c in CAMPOS_FORMULARIO}
            campos["pago"] = int(bool(campos["pago"]))
            if lancamento_id is None:
                lancamento_id = self._novo_id("lancamentos")
                _inserir(self._con, "lancamentos",
                         [dict(campos, id=lancamento_id)])
            else:
                self._con.execute(
                    "UPDATE lancamentos SET %s WHERE id = ?" % ", ".join(
                        "%s = ?" % c for c in campos),
                    (*campos.values(), lancamento_id))
            self._con.execute("DELETE FROM itens WHERE lancamento_id = ?",
                              (lancamento_id,))
            proximo = self._novo_id("itens")
            _inserir(self._con, "itens", [
                dict(item, id=proximo - n, lancamento_id=lancamento_id, ordem=n)
                for n, item in enumerate(itens)])
            if pagamentos is not None:
                self._con.execute(
                    "DELETE FROM pagamentos WHERE lancamento_id = ?",
                    (lancamento_id,))
                proximo = self._novo_id("pagamentos")
                _inserir(self._con, "pagamentos", [
                    dict(p, id=proximo - n, lancamento_id=lancamento_id, ordem=n)
                    for n, p in enumerate(pagamentos)])
            self._registrar(lancamento_id, "salvar")
        return lancamento_id

    def salvar_importados(self, lancamentos):
        """Vários lançamentos completos, vindos da planilha. Tudo ou nada aqui;
        cada um sobe por conta própria."""
        with self._con:
            for l in lancamentos:
                i = self._novo_id("lancamentos")
                _inserir(self._con, "lancamentos", [dict(
                    l, id=i, pago=int(bool(l.get("pago"))))])
                proximo = self._novo_id("itens")
                _inserir(self._con, "itens", [
                    dict(x, id=proximo - n, lancamento_id=i, ordem=n)
                    for n, x in enumerate(l["itens"])])
                proximo = self._novo_id("pagamentos")
                _inserir(self._con, "pagamentos", [
                    dict(x, id=proximo - n, lancamento_id=i, ordem=n)
                    for n, x in enumerate(l["pagamentos"])])
                self._registrar(i, "salvar")

    def salvar_parcelas(self, parcelas):
        """Cria um lançamento por parcela, tudo ou nada aqui; cada um sobe por
        conta própria, então uma falha de envio não cria duplicatas. Devolve os
        ids, temporários."""
        ids = []
        with self._con:
            for dados in parcelas:
                i = self._novo_id("lancamentos")
                _inserir(self._con, "lancamentos", [dict(
                    dados, id=i, pago=int(bool(dados.get("pago"))))])
                self._registrar(i, "salvar")
                ids.append(i)
        return ids

    def excluir_lancamento(self, i):
        with self._con:
            self._registrar(i, "excluir")
            self._apagar_linhas(i)
            self._con.execute(
                "UPDATE lancamentos SET lancamento_vinculado_id = NULL "
                "WHERE lancamento_vinculado_id = ?", (i,))
            self._con.execute(
                "UPDATE documentos SET lancamento_id = NULL "
                "WHERE lancamento_id = ?", (i,))

    def marcar_pago(self, lancamento_id, pago):
        """Guarda a versão do servidor em que a marcação se baseou: uma segunda
        marcação antes do envio troca o valor, mas não essa versão."""
        with self._con:
            self._con.execute("UPDATE lancamentos SET pago = ? WHERE id = ?",
                              (int(pago), lancamento_id))
            if self._pendencia(lancamento_id):
                self._registrar(lancamento_id, "salvar")
                return
            self._con.execute(
                "INSERT INTO pendencias_pago "
                "(lancamento_id, pago, base_updated_at, editado_em) "
                "SELECT id, ?, updated_at, ? FROM lancamentos WHERE id = ? "
                "ON CONFLICT(lancamento_id) DO UPDATE SET pago = excluded.pago, "
                "editado_em = excluded.editado_em",
                (int(pago), agora_iso(), lancamento_id))

    # ---- fila ----
    def qtd_pendentes(self):
        return sum(self._con.execute(
            "SELECT COUNT(*) AS n FROM %s" % t).fetchone()["n"]
            for t in ("pendencias_pago", "pendencias_lancamento", "fila_operacoes"))

    def pendencias(self):
        return [dict(l) for l in self._con.execute(
            "SELECT * FROM pendencias_pago")]

    def envios(self):
        """O que mandar agora. Criação desfeita antes de subir nem chega a ir."""
        saida = []
        with self._con:
            for p in self._con.execute(
                    "SELECT * FROM pendencias_lancamento WHERE conflito = 0 "
                    # Na ordem de criação: as parcelas ganham ids em sequência
                    "ORDER BY lancamento_id < 0, ABS(lancamento_id)"
                    ).fetchall():
                i = p["lancamento_id"]
                if p["operacao"] == "excluir" and i < 0:
                    self._con.execute(
                        "DELETE FROM pendencias_lancamento "
                        "WHERE lancamento_id = ?", (i,))
                    continue
                envio = dict(p)
                if p["operacao"] == "salvar":
                    l = dict(self._con.execute(
                        "SELECT * FROM lancamentos WHERE id = ?", (i,)).fetchone())
                    l["pago"] = bool(l["pago"])
                    envio["dados"] = l
                    envio["itens"] = [dict(x) for x in self._con.execute(
                        "SELECT valor, categoria, descricao, data_original "
                        "FROM itens WHERE lancamento_id = ? ORDER BY ordem, id",
                        (i,))]
                    envio["pagamentos"] = [dict(x) for x in self._con.execute(
                        "SELECT banco, forma, valor FROM pagamentos "
                        "WHERE lancamento_id = ? ORDER BY ordem, id", (i,))]
                saida.append(envio)
        return saida

    def confirmar_envio(self, lancamento_id, pago_enviado, novo_updated_at):
        """Se a pessoa mudou de ideia durante o envio, a marcação nova segue na
        fila, já baseada na versão que o servidor acabou de devolver."""
        with self._con:
            self._con.execute(
                "DELETE FROM pendencias_pago WHERE lancamento_id = ? AND pago = ?",
                (lancamento_id, int(pago_enviado)))
            self._con.execute(
                "UPDATE pendencias_pago SET base_updated_at = ? "
                "WHERE lancamento_id = ?", (novo_updated_at, lancamento_id))

    def rebasear_pago(self, lancamento_id, novo_updated_at):
        """A marcação daqui é a mais recente: reenvia sobre a versão de lá."""
        with self._con:
            self._con.execute(
                "UPDATE pendencias_pago SET base_updated_at = ? "
                "WHERE lancamento_id = ?", (novo_updated_at, lancamento_id))

    def descartar_pendencia(self, lancamento_id):
        with self._con:
            self._con.execute(
                "DELETE FROM pendencias_pago WHERE lancamento_id = ?",
                (lancamento_id,))

    def _trocar_id(self, velho, novo):
        self._con.execute("UPDATE lancamentos SET id = ? WHERE id = ?", (novo, velho))
        self._con.execute(
            "UPDATE lancamentos SET lancamento_vinculado_id = ? "
            "WHERE lancamento_vinculado_id = ?", (novo, velho))
        for tabela in ("itens", "pagamentos", "documentos"):
            self._con.execute(
                "UPDATE %s SET lancamento_id = ? WHERE lancamento_id = ?" % tabela,
                (novo, velho))
        self._con.execute(
            "UPDATE pendencias_lancamento SET lancamento_id = ?, "
            "client_uuid = NULL WHERE lancamento_id = ?", (novo, velho))
        self._trocar_id_nas_operacoes(velho, novo)

    def confirmar_lancamento(self, envio, novo_id, updated_at):
        """O servidor gravou. Se houve outra alteração durante o envio, ela
        segue na fila, já com o id definitivo e a versão nova."""
        velho = envio["lancamento_id"]
        with self._con:
            if novo_id != velho:
                self._trocar_id(velho, novo_id)
            self._con.execute(
                "UPDATE lancamentos SET updated_at = ? WHERE id = ?",
                (updated_at, novo_id))
            self._con.execute(
                "DELETE FROM pendencias_lancamento "
                "WHERE lancamento_id = ? AND versao = ?",
                (novo_id, envio["versao"]))
            self._con.execute(
                "UPDATE pendencias_lancamento SET base_updated_at = ? "
                "WHERE lancamento_id = ?", (updated_at, novo_id))

    def confirmar_exclusao(self, envio):
        with self._con:
            self._con.execute(
                "DELETE FROM pendencias_lancamento "
                "WHERE lancamento_id = ? AND versao = ?",
                (envio["lancamento_id"], envio["versao"]))

    # ---- conflitos ----
    def marcar_conflito(self, lancamento_id, versao_servidor):
        """Para de tentar enviar até a pessoa decidir."""
        with self._con:
            self._con.execute(
                "UPDATE pendencias_lancamento SET conflito = 1, servidor = ? "
                "WHERE lancamento_id = ?",
                (json.dumps(versao_servidor, ensure_ascii=False),
                 lancamento_id))

    def conflitos(self):
        """[{lancamento_id, operacao, local, servidor}]; `local` é None numa
        exclusão e `servidor` é None se lá o lançamento foi apagado."""
        saida = []
        locais = {l["id"]: l for l in self.lancamentos()}
        for p in self._con.execute(
                "SELECT * FROM pendencias_lancamento WHERE conflito = 1"):
            saida.append(dict(
                lancamento_id=p["lancamento_id"], operacao=p["operacao"],
                local=locais.get(p["lancamento_id"]),
                servidor=json.loads(p["servidor"] or "null")))
        return saida

    def manter_versao_local(self, conflito):
        """Volta para a fila baseada no que o servidor tem agora. Se lá foi
        apagado, sobe como um lançamento novo."""
        i, servidor = conflito["lancamento_id"], conflito["servidor"]
        with self._con:
            if servidor is None and conflito["operacao"] == "salvar":
                novo = min(self._novo_id("lancamentos"), i if i < 0 else 0)
                self._trocar_id(i, novo)
                self._con.execute(
                    "UPDATE pendencias_lancamento SET client_uuid = ?, "
                    "base_updated_at = NULL, conflito = 0, servidor = NULL "
                    "WHERE lancamento_id = ?", (str(uuid.uuid4()), novo))
            elif servidor is None:
                self._con.execute(
                    "DELETE FROM pendencias_lancamento WHERE lancamento_id = ?",
                    (i,))
            else:
                self._con.execute(
                    "UPDATE pendencias_lancamento SET base_updated_at = ?, "
                    "conflito = 0, servidor = NULL WHERE lancamento_id = ?",
                    (servidor["updated_at"], i))

    def usar_versao_do_servidor(self, conflito):
        """Tira da fila; o próximo download traz a versão de lá."""
        with self._con:
            self._con.execute(
                "DELETE FROM pendencias_lancamento WHERE lancamento_id = ?",
                (conflito["lancamento_id"],))
