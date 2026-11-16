package br.com.fabioscheffer.controlefinanceiro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Uma alteração feita sem internet, guardada para ser enviada depois. Cada uma traz o texto que a
 * descreve (o [rotulo]) para a pessoa reconhecê-la na tela de Sincronização.
 */
sealed interface Operacao {
    val id: String
    val rotulo: String

    /** Novo lançamento (ou parcelas). O mesmo [uuid] sempre: enviar duas vezes não duplica. */
    data class Criar(
        override val id: String, override val rotulo: String, val uuid: String,
        val dados: List<DadosNovo>, val idsTemporarios: List<Long>,
    ) : Operacao

    data class Pago(
        override val id: String, override val rotulo: String, val lancamentoId: Long, val pago: Boolean, val base: String?,
    ) : Operacao

    data class Excluir(
        override val id: String, override val rotulo: String, val lancamentoId: Long, val base: String?,
    ) : Operacao

    data class Editar(
        override val id: String, override val rotulo: String, val lancamentoId: Long, val base: String?, val dados: DadosNovo,
    ) : Operacao

    val oQueFaz: String
        get() = when (this) {
            is Criar -> if (dados.size == 1) "Novo lançamento" else "Novos lançamentos (${dados.size} parcelas)"
            is Pago -> if (pago) "Marcar como pago" else "Marcar como pendente"
            is Excluir -> "Excluir"
            is Editar -> "Editar"
        }
}

/** Uma alteração que o servidor não aceitou (o lançamento mudou ou sumiu) e que a pessoa precisa decidir. */
data class Conflito(val operacao: Operacao, val motivo: String)

/** A cópia dos dados do servidor guardada no celular, para abrir e consultar sem internet. */
data class Copia(
    val usuario: String,
    val salvoEm: String,
    val todos: List<Lancamento>,
    val opcoes: Map<String, List<String>>,
    val contas: List<ContaConfig>,
    val permissoes: Permissoes,
    val documentos: List<DocumentoGuardado>,
)

object Offline {
    private val FORMATO = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun agora(): String = LocalDateTime.now().withNano(0).format(FORMATO)

    /** "2026-11-16T10:30:00" vira "16/11/2026 às 10:30". */
    fun dataFalada(iso: String): String {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso) ?: return iso
        val (a, mes, d, h, min) = m.destructured
        return "$d/$mes/$a às $h:$min"
    }

    // ---- aplicar a fila sobre o que veio do servidor ----

    fun comoLancamento(id: Long, d: DadosNovo, base: Lancamento? = null) = Lancamento(
        id = id, dataOrigem = d.dataOrigem, dataVencimento = d.dataVencimento, valor = d.valor, banco = d.banco,
        estabelecimento = d.estabelecimento, forma = d.forma, parcela = d.parcela, descricao = d.descricao,
        categoria = d.categoria, pago = d.pago, credor = d.credor, devedor = d.devedor,
        itens = d.itens, pagamentos = d.pagamentos,
        updatedAt = base?.updatedAt, vinculadoId = base?.vinculadoId,
    )

    /** O que a lista mostra: o que veio do servidor com as alterações ainda não enviadas por cima. */
    fun aplicar(servidor: List<Lancamento>, fila: List<Operacao>): List<Lancamento> {
        var lista = servidor
        for (op in fila) {
            lista = when (op) {
                is Operacao.Criar -> lista + op.dados.mapIndexed { n, d -> comoLancamento(op.idsTemporarios[n], d) }
                is Operacao.Pago -> lista.map { if (it.id == op.lancamentoId) it.copy(pago = op.pago) else it }
                is Operacao.Excluir -> lista.filter { it.id != op.lancamentoId }
                is Operacao.Editar -> lista.map { if (it.id == op.lancamentoId) comoLancamento(it.id, op.dados, it) else it }
            }
        }
        return lista
    }

    /** O mesmo envio, mas partindo da versão que o servidor tem agora (a pessoa escolheu manter a sua alteração). */
    fun comBase(op: Operacao, base: String?): Operacao = when (op) {
        is Operacao.Pago -> op.copy(base = base)
        is Operacao.Excluir -> op.copy(base = base)
        is Operacao.Editar -> op.copy(base = base)
        is Operacao.Criar -> op
    }

    /** Código de envio da parcela [n]: sempre o mesmo para o mesmo lançamento, para não duplicar. */
    fun codigoDaParcela(uuid: String, n: Int): String =
        if (n == 0) uuid else java.util.UUID.nameUUIDFromBytes("$uuid#$n".toByteArray()).toString()

    // ---- JSON ----

    private fun JSONObject.txt(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
    private fun ou(t: String?): Any = t ?: JSONObject.NULL

    fun dadosParaJson(d: DadosNovo): JSONObject = JSONObject()
        .put("dataOrigem", d.dataOrigem).put("dataVencimento", ou(d.dataVencimento)).put("valor", d.valor)
        .put("banco", ou(d.banco)).put("forma", ou(d.forma)).put("estabelecimento", ou(d.estabelecimento))
        .put("categoria", ou(d.categoria)).put("credor", ou(d.credor)).put("devedor", ou(d.devedor))
        .put("descricao", ou(d.descricao)).put("pago", d.pago).put("parcela", ou(d.parcela))
        .put("itens", JSONArray(d.itens.map { itemParaJson(it) }))
        .put("pagamentos", JSONArray(d.pagamentos.map { pagamentoParaJson(it) }))

    private fun itemParaJson(i: Item) = JSONObject().put("valor", i.valor).put("categoria", ou(i.categoria))
        .put("descricao", ou(i.descricao)).put("dataOriginal", ou(i.dataOriginal))

    private fun pagamentoParaJson(p: Pagamento) =
        JSONObject().put("banco", ou(p.banco)).put("forma", ou(p.forma)).put("valor", p.valor)

    private fun itemDeJson(o: JSONObject) = Item(o.getDouble("valor"), o.txt("categoria"), o.txt("descricao"), o.txt("dataOriginal"))
    private fun pagamentoDeJson(o: JSONObject) = Pagamento(o.txt("banco"), o.txt("forma"), o.getDouble("valor"))

    private fun <T> JSONArray.mapear(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }

    fun dadosDeJson(o: JSONObject) = DadosNovo(
        dataOrigem = o.getString("dataOrigem"), dataVencimento = o.txt("dataVencimento"), valor = o.getDouble("valor"),
        banco = o.txt("banco"), forma = o.txt("forma"), estabelecimento = o.txt("estabelecimento"),
        categoria = o.txt("categoria"), credor = o.txt("credor"), devedor = o.txt("devedor"),
        descricao = o.txt("descricao"), pago = o.getBoolean("pago"),
        itens = o.getJSONArray("itens").mapear(::itemDeJson),
        pagamentos = o.getJSONArray("pagamentos").mapear(::pagamentoDeJson),
        parcela = o.txt("parcela"),
    )

    fun operacaoParaJson(op: Operacao): JSONObject {
        val o = JSONObject().put("id", op.id).put("rotulo", op.rotulo)
        return when (op) {
            is Operacao.Criar -> o.put("tipo", "criar").put("uuid", op.uuid)
                .put("dados", JSONArray(op.dados.map { dadosParaJson(it) }))
                .put("temporarios", JSONArray(op.idsTemporarios))
            is Operacao.Pago -> o.put("tipo", "pago").put("lancamento", op.lancamentoId).put("pago", op.pago).put("base", ou(op.base))
            is Operacao.Excluir -> o.put("tipo", "excluir").put("lancamento", op.lancamentoId).put("base", ou(op.base))
            is Operacao.Editar -> o.put("tipo", "editar").put("lancamento", op.lancamentoId).put("base", ou(op.base))
                .put("dados", dadosParaJson(op.dados))
        }
    }

    fun operacaoDeJson(o: JSONObject): Operacao {
        val id = o.getString("id")
        val rotulo = o.optString("rotulo")
        return when (o.getString("tipo")) {
            "criar" -> Operacao.Criar(
                id, rotulo, o.getString("uuid"), o.getJSONArray("dados").mapear(::dadosDeJson),
                o.getJSONArray("temporarios").let { a -> List(a.length()) { a.getLong(it) } },
            )
            "pago" -> Operacao.Pago(id, rotulo, o.getLong("lancamento"), o.getBoolean("pago"), o.txt("base"))
            "excluir" -> Operacao.Excluir(id, rotulo, o.getLong("lancamento"), o.txt("base"))
            else -> Operacao.Editar(id, rotulo, o.getLong("lancamento"), o.txt("base"), dadosDeJson(o.getJSONObject("dados")))
        }
    }

    private fun lancamentoParaJson(l: Lancamento) = JSONObject()
        .put("id", l.id).put("dataOrigem", ou(l.dataOrigem)).put("dataVencimento", ou(l.dataVencimento)).put("valor", l.valor)
        .put("banco", ou(l.banco)).put("estabelecimento", ou(l.estabelecimento)).put("forma", ou(l.forma))
        .put("parcela", ou(l.parcela)).put("descricao", ou(l.descricao)).put("categoria", ou(l.categoria))
        .put("pago", l.pago).put("credor", ou(l.credor)).put("devedor", ou(l.devedor))
        .put("itens", JSONArray(l.itens.map { itemParaJson(it) }))
        .put("pagamentos", JSONArray(l.pagamentos.map { pagamentoParaJson(it) }))
        .put("updatedAt", ou(l.updatedAt)).put("vinculadoId", l.vinculadoId ?: JSONObject.NULL)

    private fun lancamentoDeJson(o: JSONObject) = Lancamento(
        id = o.getLong("id"), dataOrigem = o.txt("dataOrigem"), dataVencimento = o.txt("dataVencimento"),
        valor = o.getDouble("valor"), banco = o.txt("banco"), estabelecimento = o.txt("estabelecimento"),
        forma = o.txt("forma"), parcela = o.txt("parcela"), descricao = o.txt("descricao"),
        categoria = o.txt("categoria"), pago = o.getBoolean("pago"), credor = o.txt("credor"), devedor = o.txt("devedor"),
        itens = o.getJSONArray("itens").mapear(::itemDeJson),
        pagamentos = o.getJSONArray("pagamentos").mapear(::pagamentoDeJson),
        updatedAt = o.txt("updatedAt"), vinculadoId = if (o.isNull("vinculadoId")) null else o.getLong("vinculadoId"),
    )

    fun copiaParaJson(c: Copia): String = JSONObject()
        .put("usuario", c.usuario).put("salvoEm", c.salvoEm)
        .put("todos", JSONArray(c.todos.map { lancamentoParaJson(it) }))
        .put("opcoes", JSONObject().also { o -> c.opcoes.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        .put(
            "contas",
            JSONArray(
                c.contas.map {
                    JSONObject().put("nome", it.nome).put("tipo", it.tipo)
                        .put("saldo", it.saldoInicial ?: JSONObject.NULL).put("limite", it.limiteTotal ?: JSONObject.NULL)
                },
            ),
        )
        .put("ia", c.permissoes.ia).put("documentosLiberados", c.permissoes.documentos)
        .put(
            "documentos",
            JSONArray(
                c.documentos.map {
                    JSONObject().put("id", it.id).put("nome", it.nome).put("caminho", it.caminho).put("mime", it.mime)
                        .put("tamanho", it.tamanho ?: JSONObject.NULL).put("criadoEm", it.criadoEm)
                        .put("lancamentoId", it.lancamentoId ?: JSONObject.NULL)
                },
            ),
        )
        .toString()

    fun copiaDeJson(texto: String): Copia {
        val o = JSONObject(texto)
        val opcoes = o.getJSONObject("opcoes").let { op -> op.keys().asSequence().associateWith { k -> op.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } } }
        return Copia(
            usuario = o.getString("usuario"), salvoEm = o.getString("salvoEm"),
            todos = o.getJSONArray("todos").mapear(::lancamentoDeJson),
            opcoes = opcoes,
            contas = o.getJSONArray("contas").mapear {
                ContaConfig(
                    it.getString("nome"), it.getString("tipo"),
                    if (it.isNull("saldo")) null else it.getDouble("saldo"),
                    if (it.isNull("limite")) null else it.getDouble("limite"),
                )
            },
            permissoes = Permissoes(o.getBoolean("ia"), o.getBoolean("documentosLiberados")),
            documentos = o.getJSONArray("documentos").mapear {
                DocumentoGuardado(
                    it.getString("id"), it.getString("nome"), it.getString("caminho"), it.getString("mime"),
                    if (it.isNull("tamanho")) null else it.getLong("tamanho"), it.getString("criadoEm"),
                    if (it.isNull("lancamentoId")) null else it.getLong("lancamentoId"),
                )
            },
        )
    }
}

/** Os arquivos do celular com a cópia dos dados, as alterações a enviar e os conflitos. */
class Armazem(contexto: Context) {
    private val pasta = File(contexto.filesDir, "offline").apply { mkdirs() }
    private val cache = File(pasta, "copia.json")
    private val fila = File(pasta, "fila.json")
    private val conflitos = File(pasta, "conflitos.json")

    private fun gravar(arquivo: File, texto: String) {
        // Grava num arquivo temporário e troca: se o app fechar no meio, o arquivo antigo continua inteiro.
        val tmp = File(pasta, arquivo.name + ".tmp")
        tmp.writeText(texto)
        if (!tmp.renameTo(arquivo)) {
            arquivo.writeText(texto)
            tmp.delete()
        }
    }

    fun salvarCopia(c: Copia) = gravar(cache, Offline.copiaParaJson(c))

    fun lerCopia(): Copia? = try {
        if (cache.exists()) Offline.copiaDeJson(cache.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun salvarFila(ops: List<Operacao>) =
        gravar(fila, JSONArray(ops.map { Offline.operacaoParaJson(it) }).toString())

    fun lerFila(): List<Operacao> = try {
        if (!fila.exists()) emptyList() else JSONArray(fila.readText()).let { a ->
            List(a.length()) { Offline.operacaoDeJson(a.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun salvarConflitos(lista: List<Conflito>) = gravar(
        conflitos,
        JSONArray(lista.map { JSONObject().put("operacao", Offline.operacaoParaJson(it.operacao)).put("motivo", it.motivo) }).toString(),
    )

    fun lerConflitos(): List<Conflito> = try {
        if (!conflitos.exists()) emptyList() else JSONArray(conflitos.readText()).let { a ->
            List(a.length()) {
                val o = a.getJSONObject(it)
                Conflito(Offline.operacaoDeJson(o.getJSONObject("operacao")), o.getString("motivo"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Apaga tudo o que foi guardado neste celular (a cópia, as alterações a enviar e os conflitos). */
    fun apagarTudo() {
        pasta.listFiles()?.forEach { it.delete() }
    }
}
