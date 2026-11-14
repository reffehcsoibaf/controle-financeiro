package br.com.fabioscheffer.controlefinanceiro

import java.time.LocalDate
import kotlin.math.abs

/** Um item digitado, ainda como texto. O valor negativo é desconto. */
data class EntradaItem(
    val valor: String = "",
    val categoria: String = "",
    val descricao: String = "",
    /** Só vem de um item já gravado; a pessoa não digita. Vai junto para não perder a informação. */
    val dataOriginal: String = "",
)

/** Uma parte do pagamento dividido, ainda como texto. */
data class EntradaPagamento(val banco: String = "", val forma: String = "", val valor: String = "")

/** O que a pessoa digitou no formulário, ainda como texto. */
data class EntradaNovo(
    val entrada: Boolean = false,
    val valor: String = "",
    val origem: String = "",
    val vencimento: String = "",
    val banco: String = "",
    val forma: String = "",
    val estabelecimento: String = "",
    val categoria: String = "",
    val credor: String = "",
    val devedor: String = "",
    val descricao: String = "",
    val pago: Boolean = false,
    /** Com itens, o valor é a soma deles, e categoria e descrição são deles. */
    val itens: List<EntradaItem> = emptyList(),
    /** Com o pagamento dividido, banco e forma ficam em cada parte. */
    val dividir: Boolean = false,
    val pagamentos: List<EntradaPagamento> = emptyList(),
    /** Número de parcelas; vazio é um lançamento só. */
    val parcelas: String = "",
    val diferencaNaPrimeira: Boolean = false,
)

/** O lançamento pronto para gravar: datas em ISO, valor com sinal, textos vazios viram null. */
data class DadosNovo(
    val dataOrigem: String,
    val dataVencimento: String?,
    /** Positivo é entrada, negativo é saída. */
    val valor: Double,
    val banco: String?,
    val forma: String?,
    val estabelecimento: String?,
    val categoria: String?,
    val credor: String?,
    val devedor: String?,
    val descricao: String?,
    val pago: Boolean,
    val itens: List<Item> = emptyList(),
    val pagamentos: List<Pagamento> = emptyList(),
    /** "2/6" nas parcelas; null num lançamento único. */
    val parcela: String? = null,
)

sealed interface ResultadoNovo {
    /** [todos] tem uma entrada por parcela, ou só [dados] num lançamento único. */
    data class Ok(val dados: DadosNovo, val todos: List<DadosNovo> = listOf(dados)) : ResultadoNovo
    data class Erro(val mensagem: String) : ResultadoNovo
}

/** As mesmas conferências do formulário do programa Windows (formulario.py), na mesma ordem. */
object NovoLancamento {
    const val MAXIMO_PARCELAS = 360

    /** Soma dos itens digitados, ou null se algum valor não for número. */
    fun somaDosItens(itens: List<EntradaItem>): Double? = try {
        Regras.somar(itens.map { Regras.lerValor(it.valor) })
    } catch (ex: IllegalArgumentException) {
        null
    }

    fun somaDosPagamentos(pagamentos: List<EntradaPagamento>): Double? = try {
        Regras.somar(pagamentos.map { Regras.lerValor(it.valor) })
    } catch (ex: IllegalArgumentException) {
        null
    }

    /** O valor total digitado (sem sinal): a soma dos itens, se houver, ou o campo Valor. */
    fun valorBruto(e: EntradaNovo): Double? =
        if (e.itens.isNotEmpty()) {
            somaDosItens(e.itens)
        } else {
            try {
                Regras.lerValor(e.valor)
            } catch (ex: IllegalArgumentException) {
                null
            }
        }

    /** As parcelas da prévia: (rótulo, vencimento ISO, valor com sinal), ou o erro. */
    fun previa(e: EntradaNovo, hoje: LocalDate = LocalDate.now()): Pair<List<Triple<String, String, Double>>?, String> {
        val quantidade = e.parcelas.trim().toIntOrNull()
        if (quantidade == null || quantidade !in 2..MAXIMO_PARCELAS) {
            return null to "O número de parcelas vai de 2 a $MAXIMO_PARCELAS."
        }
        val bruto = valorBruto(e)
            ?: return null to "Informe o valor em números, por exemplo 12,50, ou adicione itens."
        if (e.itens.isNotEmpty() && bruto == 0.0) {
            return null to "A soma dos itens precisa ser diferente de zero para parcelar."
        }
        if (abs(bruto) == 0.0) return null to "O valor total precisa ser maior que zero."
        val vencimento = try {
            Regras.lerData(e.vencimento)
        } catch (ex: IllegalArgumentException) {
            return null to "Vencimento inválido. Digite os números, como 25102026, ou deixe em branco."
        }
        val base = vencimento ?: hoje.toString()
        val valores = Regras.calcularParcelas(bruto, quantidade, e.diferencaNaPrimeira)
        return valores.mapIndexed { n, v ->
            Triple("${n + 1}/$quantidade", Regras.avancarMeses(base, n), Regras.valorComTipo(v, e.entrada))
        } to ""
    }

    fun validar(e: EntradaNovo, hoje: LocalDate = LocalDate.now()): ResultadoNovo {
        val origem = try {
            Regras.lerData(e.origem)
        } catch (ex: IllegalArgumentException) {
            null
        }
        if (origem == null) {
            return ResultadoNovo.Erro(
                "Preencha a data de origem: digite os números, por exemplo 25102026."
            )
        }
        val vencimento = try {
            Regras.lerData(e.vencimento)
        } catch (ex: IllegalArgumentException) {
            return ResultadoNovo.Erro("Vencimento inválido. Digite os números, como 25102026, ou deixe em branco.")
        }
        val comItens = e.itens.isNotEmpty()
        val bruto = valorBruto(e)
            ?: return ResultadoNovo.Erro(
                if (comItens) {
                    "Há um item com valor que não é número. Corrija ou remova o item."
                } else {
                    "Informe o valor em números, por exemplo 12,50."
                }
            )
        val valor = Regras.valorComTipo(bruto, e.entrada)
        val erroPagamentos = if (e.dividir) erroDePagamentos(e.pagamentos, valor) else ""
        if (erroPagamentos.isNotEmpty()) return ResultadoNovo.Erro(erroPagamentos)
        fun texto(t: String) = t.trim().ifEmpty { null }
        if (!comItens && Regras.categoriaEFatura(e.categoria)) {
            return ResultadoNovo.Erro(
                "A categoria Cartão de crédito é pagamento de fatura e exige escolher o cartão a " +
                    "quitar, o que ainda não existe no celular. Escolha outra categoria, ou crie " +
                    "este lançamento no programa do Windows."
            )
        }
        val itens = e.itens.map { Item(Regras.lerValor(it.valor), texto(it.categoria), texto(it.descricao), texto(it.dataOriginal)) }
        val pagamentos = if (e.dividir) {
            e.pagamentos.map { Pagamento(it.banco.trim(), texto(it.forma), Regras.lerValor(it.valor)) }
        } else {
            emptyList()
        }
        val unico = DadosNovo(
            dataOrigem = origem,
            dataVencimento = vencimento,
            valor = valor,
            banco = if (e.dividir) null else texto(e.banco),
            forma = if (e.dividir) null else texto(e.forma),
            estabelecimento = texto(e.estabelecimento),
            categoria = if (comItens) null else texto(e.categoria),
            credor = texto(e.credor), devedor = texto(e.devedor),
            descricao = if (comItens) null else texto(e.descricao),
            pago = e.pago,
            itens = itens,
            pagamentos = pagamentos,
        )
        if (e.parcelas.isBlank()) return ResultadoNovo.Ok(unico)
        val (parcelas, erro) = previa(e, hoje)
        if (parcelas == null) return ResultadoNovo.Erro(erro)
        return ResultadoNovo.Ok(unico, dividirEmParcelas(unico, parcelas))
    }

    /** Cada parcela leva os itens e as formas de pagamento em valores proporcionais (_montar_parcelas). */
    private fun dividirEmParcelas(
        modelo: DadosNovo,
        parcelas: List<Triple<String, String, Double>>,
    ): List<DadosNovo> {
        val absolutos = parcelas.map { abs(it.third) }
        val partesPag = if (modelo.pagamentos.isNotEmpty()) {
            Regras.ratear(modelo.pagamentos.map { it.valor }, absolutos)
        } else {
            null
        }
        val sinal = if (modelo.itens.isNotEmpty() && Regras.somar(modelo.itens.map { it.valor }) < 0) -1 else 1
        val partesItens = if (modelo.itens.isNotEmpty()) {
            Regras.ratear(modelo.itens.map { it.valor * sinal }, absolutos)
        } else {
            null
        }
        return parcelas.mapIndexed { n, (rotulo, vencimento, valor) ->
            val pagamentos = partesPag?.get(n)?.let { partes ->
                modelo.pagamentos.zip(partes).filter { it.second != 0.0 }.map { (p, v) -> p.copy(valor = v) }
            }.orEmpty()
            val itens = partesItens?.get(n)?.let { partes ->
                modelo.itens.zip(partes).filter { it.second != 0.0 }.map { (i, v) -> i.copy(valor = v) }
            }.orEmpty()
            modelo.copy(
                dataVencimento = vencimento, valor = valor, pago = false, parcela = rotulo,
                itens = itens, pagamentos = pagamentos,
                descricao = if (itens.isNotEmpty()) null else modelo.descricao?.let { "$it — $rotulo" } ?: rotulo,
            )
        }
    }

    /** Vazio se os pagamentos conferem com o valor total (_pagamentos_conferem do Windows). */
    fun erroDePagamentos(pagamentos: List<EntradaPagamento>, valor: Double): String {
        if (pagamentos.isEmpty()) {
            return "Adicione ao menos uma forma de pagamento, ou desmarque Dividir pagamento."
        }
        val semBanco = pagamentos.indexOfFirst { it.banco.isBlank() }
        if (semBanco >= 0) return "Escolha o banco ou cartão do pagamento ${semBanco + 1}."
        val soma = somaDosPagamentos(pagamentos)
            ?: return "Há um pagamento com valor que não é número. Corrija ou remova o pagamento."
        if (abs(soma - abs(valor)) > 0.005) {
            return "A soma dos pagamentos, ${Formatacao.numero(soma)}, não bate com o valor total do " +
                "lançamento, ${Formatacao.numero(abs(valor))}."
        }
        return ""
    }

    /**
     * O formulário preenchido com um lançamento já gravado, para editar. Itens e pagamentos vêm como
     * estão; o sinal do valor vira o tipo. Com itens, o valor é a soma deles (itens antigos gravados
     * negativos são orientados pela soma, como na lista).
     */
    fun entradaDe(l: Lancamento): EntradaNovo {
        val valoresItens = Regras.itensOrientados(l)
        return EntradaNovo(
            entrada = Regras.ehEntrada(l),
            valor = if (l.itens.isEmpty()) Formatacao.numero(abs(l.valor)) else "",
            origem = l.dataOrigem?.let { Formatacao.dataCurta(it).replace("/", "") }.orEmpty(),
            vencimento = l.dataVencimento?.let { Formatacao.dataCurta(it).replace("/", "") }.orEmpty(),
            banco = l.banco.orEmpty(), forma = l.forma.orEmpty(),
            estabelecimento = l.estabelecimento.orEmpty(), categoria = l.categoria.orEmpty(),
            credor = l.credor.orEmpty(), devedor = l.devedor.orEmpty(), descricao = l.descricao.orEmpty(),
            pago = l.pago,
            itens = l.itens.mapIndexed { n, i ->
                EntradaItem(
                    Formatacao.numero(valoresItens[n]), i.categoria.orEmpty(), i.descricao.orEmpty(),
                    i.dataOriginal.orEmpty(),
                )
            },
            dividir = l.pagamentos.isNotEmpty(),
            pagamentos = l.pagamentos.map { EntradaPagamento(it.banco.orEmpty(), it.forma.orEmpty(), Formatacao.numero(it.valor)) },
        )
    }

    /**
     * Os textos digitados que ainda não estão nas listas (campo, texto), para "Cadastrar textos novos
     * nas listas". Sem acento nem maiúscula, "nubank" já vale "Nubank".
     */
    fun textosNovos(e: EntradaNovo, opcoes: Map<String, List<String>>): List<Pair<String, String>> {
        val pares = mutableListOf(
            "banco" to (if (e.dividir) "" else e.banco), "forma" to (if (e.dividir) "" else e.forma),
            "estabelecimento" to e.estabelecimento, "categoria" to (if (e.itens.isEmpty()) e.categoria else ""),
            "credor" to e.credor, "devedor" to e.devedor,
        )
        pares += e.itens.map { "categoria" to it.categoria }
        if (e.dividir) e.pagamentos.forEach { pares += "banco" to it.banco; pares += "forma" to it.forma }
        return pares.map { (c, v) -> c to v.trim() }
            .filter { (c, v) -> v.isNotEmpty() && opcoes[c].orEmpty().none { Regras.semAcentos(it).trim() == Regras.semAcentos(v) } }
            .distinctBy { (c, v) -> c to Regras.semAcentos(v) }
    }

    /** Sugestões de uma lista para o que foi digitado, sem acento nem maiúscula, as que começam pelo texto primeiro. */
    fun sugestoes(opcoes: List<String>, digitado: String, limite: Int): List<String> {
        val termo = Regras.semAcentos(digitado).trim()
        if (termo.isEmpty()) return opcoes.take(limite)
        val (comecam, contem) = opcoes.filter { termo in Regras.semAcentos(it) }
            .partition { Regras.semAcentos(it).startsWith(termo) }
        return (comecam + contem).take(limite)
    }
}
