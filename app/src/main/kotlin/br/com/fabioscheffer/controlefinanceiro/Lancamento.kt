package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONObject

/** Item de um lançamento com vários itens (financeiro_itens_lancamento). */
data class Item(
    val valor: Double,
    val categoria: String?,
    val descricao: String?,
    /** Data do lançamento antigo de onde o item veio (aaaa-mm-dd); não aparece, mas não pode se perder. */
    val dataOriginal: String? = null,
)

/** Parte do valor paga por outro banco ou forma (financeiro_pagamentos_lancamento). */
data class Pagamento(val banco: String?, val forma: String?, val valor: Double)

/**
 * Linha de financeiro_lancamentos, com os mesmos nomes de coluna do Supabase.
 * Valor positivo é entrada e negativo é saída, como no site e no programa Windows.
 */
data class Lancamento(
    val id: Long,
    val dataOrigem: String?,
    val dataVencimento: String?,
    val valor: Double,
    val banco: String?,
    val estabelecimento: String?,
    val forma: String?,
    val parcela: String?,
    val descricao: String?,
    val categoria: String?,
    val pago: Boolean,
    val credor: String? = null,
    val devedor: String? = null,
    val itens: List<Item> = emptyList(),
    val pagamentos: List<Pagamento> = emptyList(),
    /** Versão do lançamento no servidor; só se grava se ainda for esta (evita sobrescrever). */
    val updatedAt: String? = null,
    /** Outro lançamento ligado a este (o par do pagamento de fatura); null se não há. */
    val vinculadoId: Long? = null,
) {
    companion object {
        private fun texto(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

        /** O valor chega como número ou como texto; os dois viram Double. */
        private fun numero(o: JSONObject, k: String): Double = if (o.isNull(k)) 0.0 else o.optDouble(k, 0.0)

        fun itemDe(o: JSONObject) = Item(numero(o, "valor"), texto(o, "categoria"), texto(o, "descricao"), texto(o, "data_original")?.take(10))

        fun pagamentoDe(o: JSONObject) = Pagamento(texto(o, "banco"), texto(o, "forma"), numero(o, "valor"))

        fun fromJson(
            o: JSONObject,
            itens: List<Item> = emptyList(),
            pagamentos: List<Pagamento> = emptyList(),
        ): Lancamento {
            val pago = when (val p = o.opt("pago")) {
                is Boolean -> p
                is Number -> p.toInt() != 0
                else -> false
            }
            return Lancamento(
                id = o.getLong("id"),
                dataOrigem = texto(o, "data_origem"),
                dataVencimento = texto(o, "data_vencimento"),
                valor = numero(o, "valor"),
                banco = texto(o, "banco"),
                estabelecimento = texto(o, "estabelecimento"),
                forma = texto(o, "forma"),
                parcela = texto(o, "parcela"),
                descricao = texto(o, "descricao"),
                categoria = texto(o, "categoria"),
                pago = pago,
                credor = texto(o, "credor"),
                devedor = texto(o, "devedor"),
                itens = itens,
                pagamentos = pagamentos,
                updatedAt = texto(o, "updated_at"),
                vinculadoId = if (o.isNull("lancamento_vinculado_id")) null else o.optLong("lancamento_vinculado_id"),
            )
        }
    }
}
