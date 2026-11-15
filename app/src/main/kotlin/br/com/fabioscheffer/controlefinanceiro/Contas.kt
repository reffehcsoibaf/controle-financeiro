package br.com.fabioscheffer.controlefinanceiro

import kotlin.math.max

const val CARTAO_CREDITO = "cartao_credito"
const val CONTA_CORRENTE = "conta_corrente"

/** Uma linha de financeiro_contas_config: o tipo e o saldo inicial ou o limite de um banco ou cartão. */
data class ContaConfig(
    val nome: String,
    /** [CARTAO_CREDITO] ou [CONTA_CORRENTE]. */
    val tipo: String,
    val saldoInicial: Double? = null,
    val limiteTotal: Double? = null,
)

/** Como uma conta está agora; [tipo] vazio é um banco das listas que ainda não foi configurado. */
data class SituacaoConta(
    val nome: String,
    val tipo: String,
    val saldo: Double = 0.0,
    val limiteTotal: Double = 0.0,
    val limiteUsado: Double = 0.0,
    val limiteRestante: Double = 0.0,
    /** Passou do saldo ou do limite. */
    val alerta: Boolean = false,
    val detalhe: String = "",
) {
    val nomeDoTipo: String
        get() = when (tipo) {
            CARTAO_CREDITO -> "Cartão de crédito"
            CONTA_CORRENTE -> "Conta corrente"
            else -> "Não configurada"
        }

    /** Uma frase só, para a lista e para o leitor de tela: nunca só a cor diz que há um problema. */
    val fala: String
        get() = buildString {
            append(nome).append(", ").append(nomeDoTipo.lowercase()).append(". ")
            append(detalhe.ifEmpty { "Sem saldo nem limite calculado." })
            if (alerta) append(if (tipo == CARTAO_CREDITO) " Atenção: passou do limite." else " Atenção: saldo negativo.")
        }
}

/** Saldos e limites, com as mesmas contas do programa Windows (regras.py, situacao_contas). */
object Contas {
    /** Quanto deste lançamento pesa neste banco: com pagamento dividido, só a parte dele. */
    fun valorParaBanco(l: Lancamento, nome: String): Double {
        if (l.pagamentos.isNotEmpty()) {
            val soma = l.pagamentos.filter { it.banco == nome }.sumOf { Math.round(it.valor * 100) } / 100.0
            return if (l.valor < 0) -soma else soma
        }
        return if (l.banco == nome) l.valor else 0.0
    }

    fun envolveBanco(l: Lancamento, nome: String) = l.banco == nome || l.pagamentos.any { it.banco == nome }

    /**
     * Sempre com todos os lançamentos, sem os filtros da tela. Na conta corrente só o que está pago
     * mexe no saldo; no cartão, o pendente também consome o limite, porque costuma ser fatura em
     * aberto. Banco cadastrado nas listas e ainda sem configuração aparece como não configurado.
     */
    fun situacoes(
        contas: List<ContaConfig>,
        lancamentos: List<Lancamento>,
        nomesCadastrados: List<String> = emptyList(),
    ): List<SituacaoConta> {
        val configuradas = contas.map { it.nome }.toSet()
        val todas = contas.map { it as ContaConfig? } +
            nomesCadastrados.filter { it !in configuradas }.map { ContaConfig(it, "") }
        return todas.filterNotNull().sortedBy { Regras.semAcentos(it.nome) }.map { c ->
            val doc = lancamentos.filter { envolveBanco(it, c.nome) }
            when (c.tipo) {
                CONTA_CORRENTE -> {
                    val saldo = Regras.somar(
                        listOf(c.saldoInicial ?: 0.0) + doc.filter { it.pago }.map { valorParaBanco(it, c.nome) },
                    )
                    SituacaoConta(
                        c.nome, c.tipo, saldo = saldo, alerta = saldo < 0,
                        detalhe = "Saldo atual: " + Formatacao.reaisFalado(saldo),
                    )
                }
                CARTAO_CREDITO -> {
                    val usado = max(0.0, -Regras.somar(doc.map { valorParaBanco(it, c.nome) }))
                    val total = c.limiteTotal ?: 0.0
                    val restante = Regras.somar(listOf(total, -usado))
                    SituacaoConta(
                        c.nome, c.tipo, limiteTotal = total, limiteUsado = usado, limiteRestante = restante,
                        alerta = restante < 0,
                        detalhe = "Limite restante: ${Formatacao.reaisFalado(restante)} de " +
                            "${Formatacao.reaisFalado(total)} (usado: ${Formatacao.reaisFalado(usado)})",
                    )
                }
                else -> SituacaoConta(c.nome, c.tipo)
            }
        }
    }

    /** Os cartões que podem ser quitados por um pagamento de fatura, sem o banco que paga. */
    fun cartoesParaQuitar(contas: List<ContaConfig>, bancoPrincipal: String): List<String> =
        contas.filter { it.tipo == CARTAO_CREDITO && it.nome != bancoPrincipal.trim() }
            .map { it.nome }.sortedBy { Regras.semAcentos(it) }
}
