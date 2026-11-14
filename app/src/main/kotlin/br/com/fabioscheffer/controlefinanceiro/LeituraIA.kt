package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONObject
import kotlin.math.abs

data class ItemIA(val valor: Double, val descricao: String?, val categoria: String?)
data class PagamentoIA(val banco: String?, val forma: String?, val valor: Double)

/** O que a IA leu do documento, com os mesmos nomes que o servidor devolve. */
data class DadosIA(
    val valor: Double? = null,
    /** aaaa-mm-dd */
    val data: String? = null,
    val dataVencimento: String? = null,
    /** "credito" (entrada), "debito" (saída) ou outra coisa se a IA não teve certeza. */
    val tipoTransacao: String? = null,
    val itens: List<ItemIA> = emptyList(),
    val pagamentos: List<PagamentoIA> = emptyList(),
    val banco: String? = null,
    val estabelecimento: String? = null,
    val forma: String? = null,
    val categoria: String? = null,
    val credor: String? = null,
    val devedor: String? = null,
    val descricao: String? = null,
) {
    companion object {
        private fun texto(o: JSONObject, k: String): String? =
            if (o.isNull(k)) null else o.optString(k).trim().ifEmpty { null }

        private fun numero(o: JSONObject, k: String): Double? {
            val v = o.opt(k)
            val n = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().replace(",", ".").toDoubleOrNull()
                else -> null
            }
            return n?.takeIf { it.isFinite() }
        }

        fun fromJson(o: JSONObject): DadosIA {
            val itens = o.optJSONArray("itens")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val x = a.optJSONObject(i) ?: return@mapNotNull null
                    val valor = numero(x, "valor") ?: return@mapNotNull null
                    ItemIA(valor, texto(x, "descricao"), texto(x, "categoria_sugerida"))
                }
            }.orEmpty()
            val pagamentos = o.optJSONArray("pagamentos")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val x = a.optJSONObject(i) ?: return@mapNotNull null
                    val valor = numero(x, "valor") ?: return@mapNotNull null
                    PagamentoIA(texto(x, "banco"), texto(x, "forma_pagamento"), valor)
                }
            }.orEmpty()
            return DadosIA(
                valor = numero(o, "valor"), data = texto(o, "data"),
                dataVencimento = texto(o, "data_vencimento"), tipoTransacao = texto(o, "tipo_transacao"),
                itens = itens, pagamentos = pagamentos, banco = texto(o, "banco"),
                estabelecimento = texto(o, "estabelecimento"), forma = texto(o, "forma_pagamento"),
                categoria = texto(o, "categoria_sugerida"), credor = texto(o, "credor"),
                devedor = texto(o, "devedor"), descricao = texto(o, "descricao"),
            )
        }
    }
}

/** O que a pessoa corrigiu do que a IA sugeriu; a IA usa isto como exemplo nas próximas leituras. */
data class Correcao(val campo: String, val valorErrado: String, val valorCorreto: String, val contexto: String?)

/**
 * Como o formulário usa o que a IA leu: as mesmas regras do programa Windows (formulario.py,
 * aplicar_ia). Só tem contas e textos; a tela é que mostra o resultado.
 */
object LeituraIA {
    class Resultado(
        val entrada: EntradaNovo,
        /** O que foi feito, para o aviso falado. */
        val texto: String,
        /** (campo, texto) que a IA leu e que ainda não estão nas listas. */
        val foraDasListas: List<Pair<String, String>>,
    )

    private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun normalizar(t: String?) = Regras.semAcentos(t).trim().replace(Regex("""\s+"""), " ")

    /** A opção já cadastrada que corresponde ao que a IA leu: sem acento, maiúscula ou espaço a mais. */
    fun opcaoEquivalente(campo: String, sugerido: String?, opcoes: List<String>): String? {
        val alvo = normalizar(sugerido)
        if (alvo.isEmpty()) return null
        opcoes.firstOrNull { normalizar(it) == alvo }?.let { return it }
        if (campo == "banco" && Regex("""\bnext\b""").containsMatchIn(alvo)) {
            return opcoes.firstOrNull { normalizar(it) == "next" }
        }
        return null
    }

    private fun dataDigitada(iso: String) = iso.split("-").reversed().joinToString("")

    fun aplicar(d: DadosIA, atual: EntradaNovo, opcoes: Map<String, List<String>>): Resultado {
        val preenchidos = mutableListOf<String>()
        val fora = mutableListOf<Pair<String, String>>()
        fun lista(campo: String) = opcoes[campo].orEmpty()

        /** O texto da lista se houver equivalente; senão o que a IA leu, marcado como fora das listas. */
        fun escolher(campo: String, lido: String): String {
            val achada = opcaoEquivalente(campo, lido, lista(campo))
            if (achada != null) return achada
            if (fora.none { it.first == campo && it.second == lido }) fora += campo to lido
            return lido
        }

        val itens = d.itens.takeIf { it.size >= 2 }.orEmpty()
        val pagamentos = d.pagamentos.takeIf { it.size >= 2 }.orEmpty()
        val usarItens = itens.isNotEmpty()
        val usarPag = pagamentos.isNotEmpty()
        var e = atual

        if (d.data?.matches(ISO) == true) {
            e = e.copy(origem = dataDigitada(d.data))
            preenchidos += "Data"
        }
        if (d.dataVencimento?.matches(ISO) == true) {
            e = e.copy(vencimento = dataDigitada(d.dataVencimento))
            preenchidos += "Vencimento"
        }
        when (d.tipoTransacao) {
            "credito" -> { e = e.copy(entrada = true); preenchidos += "Tipo, entrada" }
            "debito" -> { e = e.copy(entrada = false); preenchidos += "Tipo, saída" }
        }
        if (usarItens) {
            e = e.copy(
                valor = "",
                itens = itens.map {
                    EntradaItem(
                        Formatacao.numero(Regras.somar(listOf(abs(it.valor)))),
                        it.categoria?.let { c -> escolher("categoria", c) }.orEmpty(),
                        it.descricao.orEmpty(),
                    )
                },
            )
            preenchidos += "${itens.size} itens"
        } else if (d.valor != null) {
            e = e.copy(valor = Formatacao.numero(abs(d.valor)), itens = emptyList())
            preenchidos += "Valor"
        }
        if (usarPag) {
            e = e.copy(
                dividir = true,
                pagamentos = pagamentos.map {
                    EntradaPagamento(
                        it.banco?.let { b -> escolher("banco", b) }.orEmpty(),
                        it.forma?.let { f -> escolher("forma", f) }.orEmpty(),
                        Formatacao.numero(abs(it.valor)),
                    )
                },
            )
            preenchidos += "Pagamento dividido em ${pagamentos.size} formas"
        }
        fun campo(rotulo: String, nome: String, lido: String?, pular: Boolean, aplicar: (EntradaNovo, String) -> EntradaNovo) {
            if (pular || lido == null) return
            e = aplicar(e, escolher(nome, lido))
            preenchidos += rotulo
        }
        campo("Banco ou cartão", "banco", d.banco, usarPag) { x, v -> x.copy(banco = v) }
        campo("Estabelecimento", "estabelecimento", d.estabelecimento, false) { x, v -> x.copy(estabelecimento = v) }
        campo("Forma de pagamento", "forma", d.forma, usarPag) { x, v -> x.copy(forma = v) }
        campo("Categoria", "categoria", d.categoria, usarItens) { x, v -> x.copy(categoria = v) }
        campo("Credor", "credor", d.credor, false) { x, v -> x.copy(credor = v) }
        campo("Devedor", "devedor", d.devedor, false) { x, v -> x.copy(devedor = v) }
        if (!usarItens && d.descricao != null) {
            e = e.copy(descricao = d.descricao)
            preenchidos += "Descrição"
        }

        var texto = if (preenchidos.isNotEmpty()) {
            "Documento lido. Preenchido: ${preenchidos.joinToString(", ")}."
        } else {
            "Documento lido, mas nenhum campo pôde ser preenchido com confiança."
        }
        if (fora.isNotEmpty()) {
            val rotulos = mapOf(
                "banco" to "banco", "forma" to "forma", "estabelecimento" to "estabelecimento",
                "categoria" to "categoria", "credor" to "credor", "devedor" to "devedor",
            )
            texto += " Fora das suas listas, preenchido como texto novo: " +
                fora.joinToString("; ") { (c, v) -> "${rotulos[c]} $v" } +
                ". Use Cadastrar textos novos nas listas se quiser guardá-los."
        }
        if (d.tipoTransacao != "credito" && d.tipoTransacao != "debito") {
            texto += " A IA não teve certeza se é entrada ou saída: confira o Tipo."
        }
        texto += " Confira os campos antes de salvar."
        return Resultado(e, texto, fora)
    }

    /** O que a pessoa mudou do que a IA sugeriu (correcoes_aprendidas do Windows). */
    fun correcoes(extracao: DadosIA, salvo: DadosNovo, usouItens: Boolean): List<Correcao> {
        val contexto = salvo.estabelecimento?.trim()?.ifEmpty { null }
        val pares = listOf(
            Triple("banco", extracao.banco, salvo.banco),
            Triple("estabelecimento", extracao.estabelecimento, salvo.estabelecimento),
            Triple("forma", extracao.forma, salvo.forma),
            Triple("categoria", if (usouItens) null else extracao.categoria, salvo.categoria),
            Triple("credor", extracao.credor, salvo.credor),
            Triple("devedor", extracao.devedor, salvo.devedor),
        )
        return pares.mapNotNull { (campo, sugerido, final) ->
            if (!sugerido.isNullOrBlank() && !final.isNullOrBlank() &&
                sugerido.trim().lowercase() != final.trim().lowercase()
            ) {
                Correcao(campo, sugerido, final, contexto)
            } else {
                null
            }
        }
    }
}
