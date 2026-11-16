package br.com.fabioscheffer.controlefinanceiro

import java.util.Locale

/**
 * Lançamentos duplicados: a mesma identidade do site e do programa Windows (planilha.py,
 * assinatura). Duas cópias com tudo igual são duplicatas; com itens, descrição e categoria ficam de
 * fora, porque a planilha os troca por textos gerados.
 */
object Duplicados {
    private fun baixo(t: String?) = (t ?: "").trim().lowercase()
    private fun dois(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    fun assinatura(l: Lancamento): String {
        val temItens = l.itens.isNotEmpty()
        val base = listOf(
            l.dataOrigem ?: "", l.dataVencimento ?: "", dois(l.valor), baixo(l.banco),
            baixo(l.estabelecimento), baixo(l.forma), baixo(l.parcela),
            if (temItens) "" else baixo(l.descricao),
            if (temItens) "" else baixo(l.categoria),
            baixo(l.credor), baixo(l.devedor),
        ).joinToString("|")
        val itens = l.itens.map { listOf(dois(it.valor), baixo(it.categoria), baixo(it.descricao)).joinToString("|") }
            .sorted().joinToString(";")
        val pagamentos = l.pagamentos.map { listOf(baixo(it.banco), baixo(it.forma), dois(it.valor)).joinToString("|") }
            .sorted().joinToString(";")
        return "$base##$itens##$pagamentos"
    }

    /** As cópias a excluir: de cada grupo igual, fica o mais antigo (menor id). */
    fun copias(lancamentos: List<Lancamento>): List<Lancamento> {
        val vistos = HashSet<String>()
        val repetidos = ArrayList<Lancamento>()
        for (l in lancamentos.filter { it.id > 0 }.sortedBy { it.id }) {
            if (!vistos.add(assinatura(l))) repetidos += l
        }
        return repetidos
    }
}
