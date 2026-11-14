package br.com.fabioscheffer.controlefinanceiro

import java.util.Locale
import kotlin.math.abs

/**
 * Textos de apresentação. O texto falado é montado aqui, à parte do visual, a partir do mesmo
 * dado: o que aparece na tela e o que o TalkBack lê podem ser diferentes de propósito. Quais
 * campos entram, e em que ordem, vem das Configurações ([Campos]).
 */
object Formatacao {
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val MESES = arrayOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    private val DATA = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /** "1.234,50", com "-" na frente se for negativo. Sem "R$". */
    fun numero(valor: Double): String {
        val corpo = String.format(PT_BR, "%,.2f", abs(valor))
        return if (valor < 0 && corpo != "0,00") "-$corpo" else corpo
    }

    fun reais(valor: Double): String {
        val corpo = numero(abs(valor))
        return if (valor < 0 && corpo != "0,00") "-R$ $corpo" else "R$ $corpo"
    }

    /** Nas listas o sinal diz se é entrada ou saída: "+10,00", "-10,00". */
    fun valorComSinal(valor: Double): String = (if (valor >= 0) "+" else "") + numero(valor)

    /** Para o leitor de tela: o sinal de menos às vezes não é falado, a palavra sempre é. */
    fun reaisFalado(valor: Double): String =
        if (valor < 0 && numero(valor) != "0,00") "menos R$ ${numero(abs(valor))}" else "R$ ${numero(abs(valor))}"

    /** "2026-03-05" vira "05/03/2026". Se não for uma data, devolve como veio. */
    fun dataCurta(iso: String?): String {
        val m = iso?.let { DATA.find(it) } ?: return iso.orEmpty()
        return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
    }

    /** "2026-03-05" vira "5 de março de 2026". */
    fun dataFalada(iso: String?): String {
        val m = iso?.let { DATA.find(it) } ?: return iso.orEmpty()
        val mes = m.groupValues[2].toInt()
        if (mes !in 1..12) return iso.orEmpty()
        return "${m.groupValues[3].toInt()} de ${MESES[mes - 1]} de ${m.groupValues[1]}"
    }

    fun titulo(l: Lancamento): String = Regras.descricaoDe(l)

    fun tipo(l: Lancamento): String = if (Regras.ehEntrada(l)) "Entrada" else "Saída"

    fun situacao(l: Lancamento): String = if (l.pago) "Pago" else "Pendente"

    private fun cheio(t: String?): String? = t?.takeIf { it.isNotBlank() }

    private fun estabelecimentoSeDiferente(l: Lancamento): String? =
        cheio(l.estabelecimento)?.takeIf { it != titulo(l) }

    private fun categoriaSeDiferente(l: Lancamento): String? =
        Regras.categoriasDe(l).takeIf { it.isNotBlank() && it != titulo(l) }

    private fun itensTexto(l: Lancamento): String? =
        l.itens.size.takeIf { it > 0 }?.let { if (it == 1) "1 item" else "$it itens" }

    /** O pedaço da frase falada para um campo, ou null se o lançamento não tem esse dado. */
    fun falaDoCampo(l: Lancamento, chave: String): String? = when (chave) {
        "descricao" -> titulo(l)
        "valor" -> "${tipo(l).lowercase()} de ${reais(abs(l.valor))}"
        "itens" -> itensTexto(l)
        "vencimento" -> cheio(l.dataVencimento)?.let { "vencimento ${dataFalada(it)}" }
        "situacao" -> situacao(l)
        "forma" -> cheio(l.forma)
        "conta" -> cheio(l.banco)
        "origem" -> cheio(l.dataOrigem)?.let { "origem ${dataFalada(it)}" }
        "parcela" -> cheio(l.parcela)?.let { "parcela $it" }
        "estabelecimento" -> estabelecimentoSeDiferente(l)
        "categoria" -> categoriaSeDiferente(l)
        "credor" -> cheio(l.credor)?.let { "credor $it" }
        "devedor" -> cheio(l.devedor)?.let { "devedor $it" }
        else -> null
    }

    /** O que o leitor de tela fala para um lançamento inteiro, na ordem das Configurações. */
    fun fala(l: Lancamento, campos: List<CampoConfig> = Campos.padrao()): String =
        campos.filter { it.visivel }.mapNotNull { falaDoCampo(l, it.chave) }
            .joinToString(", ").ifEmpty { titulo(l) }

    /** Texto na tela de um campo da segunda linha. Descrição, valor e situação têm lugar próprio. */
    private fun textoDoCampo(l: Lancamento, chave: String): String? = when (chave) {
        "itens" -> itensTexto(l)
        "vencimento" -> cheio(l.dataVencimento)?.let { "Vence ${dataCurta(it)}" }
        "forma" -> cheio(l.forma)
        "conta" -> cheio(l.banco)
        "origem" -> cheio(l.dataOrigem)?.let { "Origem ${dataCurta(it)}" }
        "parcela" -> cheio(l.parcela)?.let { "Parcela $it" }
        "estabelecimento" -> estabelecimentoSeDiferente(l)
        "categoria" -> categoriaSeDiferente(l)
        "credor" -> cheio(l.credor)?.let { "Credor $it" }
        "devedor" -> cheio(l.devedor)?.let { "Devedor $it" }
        else -> null
    }

    /** Segunda linha da tela: os campos ligados, na ordem das Configurações, só o que existir. */
    fun detalhes(l: Lancamento, campos: List<CampoConfig> = Campos.padrao()): String =
        campos.filter { it.visivel }.mapNotNull { textoDoCampo(l, it.chave) }.joinToString(" · ")

    /**
     * Cada campo preenchido como "rótulo: valor". Na ordem das Configurações: primeiro os ligados,
     * depois os desligados, porque nos detalhes a pessoa quer ver tudo.
     */
    fun camposDoDetalhe(l: Lancamento, campos: List<CampoConfig> = Campos.padrao()): List<Pair<String, String>> {
        val ordem = campos.filter { it.visivel } + campos.filter { !it.visivel }
        return ordem.flatMap { c ->
            when (c.chave) {
                "descricao" -> listOf("Descrição" to titulo(l))
                "valor" -> listOf("Tipo" to tipo(l), "Valor" to reais(abs(l.valor)))
                "situacao" -> listOf("Situação" to situacao(l))
                "origem" -> listOfNotNull(cheio(l.dataOrigem)?.let { "Data de origem" to dataFalada(it) })
                "vencimento" -> listOfNotNull(cheio(l.dataVencimento)?.let { "Vencimento" to dataFalada(it) })
                "conta" -> listOfNotNull(cheio(l.banco)?.let { "Banco ou cartão" to it })
                "forma" -> listOfNotNull(cheio(l.forma)?.let { "Forma de pagamento" to it })
                "estabelecimento" -> listOfNotNull(cheio(l.estabelecimento)?.let { "Estabelecimento" to it })
                "categoria" -> listOfNotNull(cheio(Regras.categoriasDe(l))?.let { "Categoria" to it })
                "credor" -> listOfNotNull(cheio(l.credor)?.let { "Credor" to it })
                "devedor" -> listOfNotNull(cheio(l.devedor)?.let { "Devedor" to it })
                "parcela" -> listOfNotNull(cheio(l.parcela)?.let { "Parcela" to it })
                else -> emptyList() // itens: aparecem na seção própria, depois dos campos
            }
        }
    }

    /** Uma frase por item: "Item 1: Arroz, Mercado, R$ 5,00". Desconto vem dito por palavra. */
    fun itensDoDetalhe(l: Lancamento): List<String> {
        val valores = Regras.itensOrientados(l)
        return l.itens.mapIndexed { i, item ->
            val valor = if (valores[i] < 0) "desconto de ${reais(abs(valores[i]))}" else reais(valores[i])
            "Item ${i + 1}: " + listOfNotNull(cheio(item.descricao), cheio(item.categoria), valor).joinToString(", ")
        }
    }

    /** Uma frase por pagamento: "Pagamento 1: Nubank, Cartão, R$ 30,00". */
    fun pagamentosDoDetalhe(l: Lancamento): List<String> =
        l.pagamentos.mapIndexed { i, p ->
            "Pagamento ${i + 1}: " + listOfNotNull(cheio(p.banco), cheio(p.forma), reais(abs(p.valor)))
                .joinToString(", ")
        }

    fun quantidade(n: Int): String = if (n == 1) "1 lançamento" else "$n lançamentos"

    /**
     * Avisa quando a lista não mostra tudo, para nenhum lançamento parecer sumido. Devolve null
     * quando nada está escondendo lançamentos.
     */
    fun avisoDeFiltro(mostrando: Int, total: Int, comFiltroDeBusca: Boolean): String? = when {
        comFiltroDeBusca -> "Com filtros. Mostrando $mostrando de ${quantidade(total)}."
        mostrando != total -> "Mostrando $mostrando de ${quantidade(total)}."
        else -> null
    }

    /** O resumo inteiro numa frase só. */
    fun resumoFalado(r: Resumo, avisoDeFiltro: String? = null): String =
        (if (avisoDeFiltro != null) "$avisoDeFiltro " else "") +
            "Resumo: ${quantidade(r.quantidade)}. " +
            "Entradas, ${reaisFalado(r.entradas)}. " +
            "Saídas, ${reaisFalado(abs(r.saidas))}. " +
            "Saldo, ${reaisFalado(r.total)}. " +
            "Pago, ${reaisFalado(r.pago)}. " +
            "Pendente, ${reaisFalado(r.pendente)}."
}
