package br.com.fabioscheffer.controlefinanceiro

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

data class Resumo(
    val quantidade: Int = 0,
    val entradas: Double = 0.0,
    /** Soma das saídas, negativa, como no programa Windows. */
    val saidas: Double = 0.0,
    val total: Double = 0.0,
    val pago: Double = 0.0,
    val pendente: Double = 0.0,
)

/** Datas em texto ISO (aaaa-mm-dd); vazio quer dizer "sem esse filtro". */
data class Filtros(
    val busca: String = "",
    val origemDe: String = "",
    val origemAte: String = "",
    val vencDe: String = "",
    val vencAte: String = "",
    /** "", "pago" ou "pendente". */
    val status: String = "",
    /** "", "entrada" ou "saida". */
    val tipo: String = "",
    /** "recentes" ou "antigos". */
    val ordem: String = "recentes",
    val banco: String = "",
    val forma: String = "",
    val estabelecimento: String = "",
    val categoria: String = "",
    val credor: String = "",
    val devedor: String = "",
)

/**
 * Regras de consulta, as mesmas do site e do programa Windows (financeiro/regras.py no repositório
 * controle-financeiro-desktop, ramo windows). Mudou lá, tem de mudar aqui.
 */
object Regras {
    fun ehEntrada(l: Lancamento) = l.valor >= 0

    fun semAcentos(texto: String?): String =
        Normalizer.normalize(texto.orEmpty(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)

    fun resumir(lancamentos: List<Lancamento>): Resumo {
        var entradas = 0.0
        var saidas = 0.0
        var total = 0.0
        var pago = 0.0
        var pendente = 0.0
        for (l in lancamentos) {
            total += l.valor
            if (l.valor >= 0) entradas += l.valor else saidas += l.valor
            if (l.pago) pago += l.valor else pendente += l.valor
        }
        return Resumo(lancamentos.size, entradas, saidas, total, pago, pendente)
    }

    /**
     * Na ordem escolhida pela data de origem. No mesmo dia, sempre a ordem de criação, para as
     * parcelas ficarem 1/3, 2/3, 3/3. Id negativo é temporário (ainda não enviado).
     */
    fun ordenar(lancamentos: List<Lancamento>, recentesPrimeiro: Boolean = true): List<Lancamento> {
        val porCriacao = lancamentos.sortedWith(compareBy({ it.id < 0 }, { abs(it.id) }))
        return if (recentesPrimeiro) porCriacao.sortedByDescending { it.dataOrigem.orEmpty() }
        else porCriacao.sortedBy { it.dataOrigem.orEmpty() }
    }

    /** Com itens, a descrição vem deles, como na tabela do site. */
    fun descricaoDe(l: Lancamento): String {
        val textos = l.itens.mapNotNull { it.descricao?.takeIf { d -> d.isNotBlank() } }
        if (textos.isNotEmpty()) {
            val juntos = textos.joinToString(" / ")
            return l.parcela?.takeIf { it.isNotBlank() }?.let { "$juntos — $it" } ?: juntos
        }
        return listOf(l.descricao, l.estabelecimento).firstOrNull { !it.isNullOrBlank() } ?: "Sem descrição"
    }

    fun categoriasDe(l: Lancamento): String {
        if (l.itens.isEmpty()) return l.categoria.orEmpty()
        return l.itens.mapNotNull { it.categoria?.takeIf { c -> c.isNotBlank() } }.distinct().joinToString(", ")
    }

    /**
     * Valores dos itens como o site entende: positivo é item normal e negativo é desconto. Há
     * lançamentos antigos gravados com os itens negativos; como o sinal do total vem de entrada ou
     * saída, basta orientar pela soma.
     */
    fun itensOrientados(l: Lancamento): List<Double> {
        val valores = l.itens.map { it.valor }
        return if (valores.sum() < 0) valores.map { -it } else valores
    }

    private fun dataParaBusca(iso: String?): String = if (iso.isNullOrBlank()) "—" else Formatacao.dataCurta(iso)

    private fun bateBusca(l: Lancamento, termo: String): Boolean {
        val campos = mutableListOf(
            l.descricao, l.estabelecimento, l.categoria, l.credor, l.devedor, l.banco, l.forma,
            Formatacao.numero(l.valor), dataParaBusca(l.dataOrigem), dataParaBusca(l.dataVencimento),
        )
        for (i in l.itens) {
            campos += i.descricao
            campos += i.categoria
        }
        return campos.any { termo in semAcentos(it) }
    }

    /** Na ordem escolhida. No mesmo dia, sempre a ordem de criação. */
    fun filtrar(lancamentos: List<Lancamento>, f: Filtros): List<Lancamento> {
        val termo = semAcentos(f.busca).trim()
        val saida = lancamentos.filter { l ->
            val origem = l.dataOrigem.orEmpty()
            val venc = l.dataVencimento.orEmpty()
            when {
                f.origemDe.isNotEmpty() && origem < f.origemDe -> false
                f.origemAte.isNotEmpty() && origem > f.origemAte -> false
                f.vencDe.isNotEmpty() && venc.isNotEmpty() && venc < f.vencDe -> false
                f.vencAte.isNotEmpty() && venc.isNotEmpty() && venc > f.vencAte -> false
                f.status == "pago" && !l.pago -> false
                f.status == "pendente" && l.pago -> false
                f.tipo == "entrada" && !ehEntrada(l) -> false
                f.tipo == "saida" && ehEntrada(l) -> false
                f.banco.isNotEmpty() && l.banco != f.banco && l.pagamentos.none { it.banco == f.banco } -> false
                f.forma.isNotEmpty() && l.forma != f.forma && l.pagamentos.none { it.forma == f.forma } -> false
                f.estabelecimento.isNotEmpty() && l.estabelecimento != f.estabelecimento -> false
                f.categoria.isNotEmpty() && l.categoria != f.categoria && l.itens.none { it.categoria == f.categoria } -> false
                f.credor.isNotEmpty() && l.credor != f.credor -> false
                f.devedor.isNotEmpty() && l.devedor != f.devedor -> false
                termo.isNotEmpty() && !bateBusca(l, termo) -> false
                else -> true
            }
        }
        return ordenar(saida, f.ordem != "antigos")
    }

    /** Os filtros que não aparecem na tela principal, para ela poder avisar. */
    fun temFiltroDaJanela(f: Filtros): Boolean =
        listOf(
            f.busca.trim(), f.origemDe, f.origemAte, f.vencDe, f.vencAte, f.banco, f.forma,
            f.estabelecimento, f.categoria, f.credor, f.devedor,
        ).any { it.isNotEmpty() }

    /**
     * Lê o valor como se digita no Brasil: "1.234,50", "12,5", "12.50", "1.234". Levanta
     * IllegalArgumentException se não for um número.
     */
    fun lerValor(texto: String?): Double {
        var t = texto.orEmpty().replace("R$", "").replace(" ", "").trim()
        require(t.isNotEmpty()) { "vazio" }
        t = when {
            "," in t -> t.replace(".", "").replace(",", ".")
            Regex("[+-]?\\d{1,3}(\\.\\d{3})+").matches(t) -> t.replace(".", "")
            else -> t
        }
        val valor = t.toDoubleOrNull()
        require(valor != null && valor.isFinite()) { texto.orEmpty() }
        return Math.round(valor * 100) / 100.0
    }

    /** Pagamento de fatura: no Windows e no site cria também um lançamento de entrada no cartão. */
    fun categoriaEFatura(categoria: String?): Boolean =
        categoria.orEmpty().trim().lowercase(Locale.ROOT) == "cartão de crédito"

    /**
     * Aceita 25/10/2026, 25/10/26, 25102026 e 251026. Vazio vale null; data que não existe
     * levanta IllegalArgumentException.
     */
    fun lerData(texto: String?): String? {
        if (texto.isNullOrBlank()) return null
        var numeros = texto.filter { it.isDigit() }
        if (numeros.length == 6) numeros = numeros.substring(0, 4) + "20" + numeros.substring(4)
        require(numeros.length == 8) { texto }
        return try {
            LocalDate.of(
                numeros.substring(4).toInt(), numeros.substring(2, 4).toInt(), numeros.substring(0, 2).toInt(),
            ).toString()
        } catch (e: DateTimeException) {
            throw IllegalArgumentException(texto, e)
        }
    }

    /**
     * O segundo lançamento do pagamento de fatura: uma entrada no cartão, para recuperar o limite
     * (lancamento_espelho_fatura do Windows).
     */
    fun espelhoDeFatura(d: DadosNovo, cartao: String): DadosNovo {
        var descricao = "Pagamento de fatura"
        if (!d.banco.isNullOrBlank()) descricao += " — via " + d.banco
        if (!d.descricao.isNullOrBlank()) descricao += " (${d.descricao})"
        return DadosNovo(
            dataOrigem = d.dataOrigem, dataVencimento = d.dataVencimento, valor = abs(d.valor),
            banco = cartao, forma = d.forma, estabelecimento = null, categoria = d.categoria,
            credor = d.credor, devedor = d.devedor, descricao = descricao, pago = d.pago,
        )
    }

    /** Positivo é entrada, negativo é saída (valor_com_tipo do Windows). */
    fun valorComTipo(valor: Double, entrada: Boolean): Double = if (entrada) abs(valor) else -abs(valor)

    /** Soma em centavos exatos, para não acumular erro de ponto flutuante. */
    fun somar(valores: List<Double>): Double = valores.sumOf { Math.round(it * 100) } / 100.0

    /** Mantém o dia quando possível; senão, o último dia do mês de destino (avancar_meses do Windows). */
    fun avancarMeses(iso: String, meses: Int): String {
        val data = LocalDate.parse(iso.take(10))
        val total = data.year * 12 + (data.monthValue - 1) + meses
        val ano = Math.floorDiv(total, 12)
        val mes = Math.floorMod(total, 12) + 1
        val ultimo = java.time.YearMonth.of(ano, mes).lengthOfMonth()
        return LocalDate.of(ano, mes, minOf(data.dayOfMonth, ultimo)).toString()
    }

    /** Valores absolutos em centavos exatos: a sobra vai numa parcela só (calcular_parcelas do Windows). */
    fun calcularParcelas(total: Double, quantidade: Int, diferencaNaPrimeira: Boolean = false): List<Double> {
        val centavos = Math.round(abs(total) * 100)
        val base = centavos / quantidade
        val resto = centavos % quantidade
        val parcelas = MutableList(quantidade) { base }
        parcelas[if (diferencaNaPrimeira) 0 else quantidade - 1] += resto
        return parcelas.map { it / 100.0 }
    }

    /**
     * Divide cada valor (item ou forma de pagamento) entre as parcelas, em centavos exatos: a soma
     * de cada parcela bate com o valor dela e a soma de cada valor bate com o original. Devolve uma
     * lista por parcela, com um valor por entrada, na mesma ordem (ratear_itens do Windows).
     */
    fun ratear(valores: List<Double>, parcelas: List<Double>): List<List<Double>> {
        val itens = valores.map { Math.round(it * 100) }
        val totais = parcelas.map { Math.round(it * 100) }
        val n = totais.size
        val bases = itens.map { Math.floorDiv(it, n.toLong()) }
        val sobras = itens.zip(bases).map { (c, b) -> c - b * n }
        val faltam = totais.map { it - bases.sum() }.toMutableList()
        val vagas = ArrayList<Int>()
        while (faltam.any { it > 0 }) {
            for (j in 0 until n) {
                if (faltam[j] > 0) {
                    vagas.add(j)
                    faltam[j] -= 1
                }
            }
        }
        val tabela = List(n) { bases.toMutableList() }
        var posicao = 0
        for ((i, sobra) in sobras.withIndex()) {
            for (j in vagas.drop(posicao).take(sobra.toInt())) tabela[j][i] += 1
            posicao += sobra.toInt()
        }
        return tabela.map { linha -> linha.map { it / 100.0 } }
    }
}
