package br.com.fabioscheffer.controlefinanceiro

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Itens, pagamento dividido e parcelas do novo lançamento. Os números de referência vêm das funções
 * do programa Windows (financeiro/regras.py: calcular_parcelas, ratear_itens, avancar_meses).
 */
class ItensPagamentosParcelasTest {
    private val hoje = LocalDate.of(2026, 11, 13)

    private fun base(
        valor: String = "100,00", entrada: Boolean = false, itens: List<EntradaItem> = emptyList(),
        dividir: Boolean = false, pagamentos: List<EntradaPagamento> = emptyList(),
        parcelas: String = "", naPrimeira: Boolean = false, vencimento: String = "10112026",
        categoria: String = "", descricao: String = "", banco: String = "",
    ) = EntradaNovo(
        entrada = entrada, valor = valor, origem = "05/03/2026", vencimento = vencimento, banco = banco,
        categoria = categoria, descricao = descricao, itens = itens, dividir = dividir,
        pagamentos = pagamentos, parcelas = parcelas, diferencaNaPrimeira = naPrimeira,
    )

    private fun ok(e: EntradaNovo) = NovoLancamento.validar(e, hoje) as ResultadoNovo.Ok
    private fun erro(e: EntradaNovo) = (NovoLancamento.validar(e, hoje) as ResultadoNovo.Erro).mensagem

    // ---- contas de parcela, iguais às do Windows ----
    @Test fun calcularParcelasEmCentavosExatos() {
        assertEquals(listOf(33.33, 33.33, 33.34), Regras.calcularParcelas(100.0, 3))
        assertEquals(listOf(33.34, 33.33, 33.33), Regras.calcularParcelas(100.0, 3, true))
        assertEquals(listOf(50.0, 50.0), Regras.calcularParcelas(-100.0, 2))
    }

    @Test fun ratearDivideItensEntreAsParcelasSemPerderCentavo() {
        val r = Regras.ratear(listOf(10.0, 5.0), listOf(5.0, 5.0, 5.0))
        assertEquals(listOf(listOf(3.34, 1.66), listOf(3.33, 1.67), listOf(3.33, 1.67)), r)
        assertEquals(listOf(listOf(10.0, -2.5), listOf(10.0, -2.5)), Regras.ratear(listOf(20.0, -5.0), listOf(10.0, 5.0)))
    }

    @Test fun avancarMesesMantemODiaOuVaiParaOUltimo() {
        assertEquals("2026-02-28", Regras.avancarMeses("2026-01-31", 1))
        assertEquals("2027-02-28", Regras.avancarMeses("2026-11-30", 3))
        assertEquals("2027-02-15", Regras.avancarMeses("2026-12-15", 2))
        assertEquals("2026-11-30", Regras.avancarMeses("2026-11-30", 0))
    }

    // ---- itens ----
    @Test fun comItensOValorEASomaECategoriaEDescricaoSaoDosItens() {
        val e = base(
            valor = "999", categoria = "Casa", descricao = "ignorada",
            itens = listOf(EntradaItem("20,00", "Mercado", "Arroz"), EntradaItem("-5,00", "", "Desconto")),
        )
        val d = ok(e).dados
        assertEquals(-15.0, d.valor, 0.0)
        assertEquals(null, d.categoria)
        assertEquals(null, d.descricao)
        assertEquals(listOf(Item(20.0, "Mercado", "Arroz"), Item(-5.0, null, "Desconto")), d.itens)
    }

    @Test fun comItensEntradaFicaPositiva() {
        assertEquals(15.0, ok(base(entrada = true, itens = listOf(EntradaItem("15")))).dados.valor, 0.0)
    }

    @Test fun itemComValorInvalidoEhRecusado() {
        assertTrue(erro(base(itens = listOf(EntradaItem("abc")))).startsWith("Há um item"))
    }

    // ---- pagamento dividido ----
    @Test fun pagamentoDivididoPrecisaBaterComOTotal() {
        val pags = listOf(EntradaPagamento("Nubank", "Crédito", "60,00"), EntradaPagamento("Itaú", "", "40,00"))
        val d = ok(base(dividir = true, pagamentos = pags, banco = "ignorado")).dados
        assertEquals(null, d.banco)
        assertEquals(listOf(Pagamento("Nubank", "Crédito", 60.0), Pagamento("Itaú", null, 40.0)), d.pagamentos)
        assertTrue(erro(base(dividir = true, pagamentos = pags.take(1))).startsWith("A soma dos pagamentos, 60,00,"))
    }

    @Test fun pagamentoDivididoSemPartesOuSemBancoEhRecusado() {
        assertTrue(erro(base(dividir = true)).startsWith("Adicione ao menos uma forma"))
        assertEquals(
            "Escolha o banco ou cartão do pagamento 2.",
            erro(base(dividir = true, pagamentos = listOf(EntradaPagamento("A", "", "50"), EntradaPagamento("", "", "50")))),
        )
    }

    @Test fun pagamentosDesligadosNaoSaem() {
        val pags = listOf(EntradaPagamento("Nubank", "", "100"))
        assertEquals(emptyList<Pagamento>(), ok(base(dividir = false, pagamentos = pags)).dados.pagamentos)
    }

    // ---- parcelas ----
    @Test fun parcelarCriaUmLancamentoPorParcelaComVencimentoMesAMes() {
        val r = ok(base(parcelas = "3", descricao = "Geladeira"))
        assertEquals(3, r.todos.size)
        assertEquals(listOf(-33.33, -33.33, -33.34), r.todos.map { it.valor })
        assertEquals(listOf("1/3", "2/3", "3/3"), r.todos.map { it.parcela })
        assertEquals(listOf("2026-11-10", "2026-12-10", "2027-01-10"), r.todos.map { it.dataVencimento })
        assertEquals("Geladeira — 1/3", r.todos[0].descricao)
        assertTrue(r.todos.none { it.pago })
    }

    @Test fun parcelasSemVencimentoComecamHojeESemDescricaoUsamORotulo() {
        val r = ok(base(parcelas = "2", vencimento = ""))
        assertEquals(listOf("2026-11-13", "2026-12-13"), r.todos.map { it.dataVencimento })
        assertEquals(listOf("1/2", "2/2"), r.todos.map { it.descricao })
    }

    @Test fun diferencaDeCentavosNaPrimeira() {
        assertEquals(listOf(-33.34, -33.33, -33.33), ok(base(parcelas = "3", naPrimeira = true)).todos.map { it.valor })
    }

    @Test fun entradaParceladaFicaPositiva() {
        assertEquals(listOf(50.0, 50.0), ok(base(parcelas = "2", entrada = true)).todos.map { it.valor })
    }

    @Test fun parcelasLevamItensEPagamentosProporcionais() {
        val e = base(
            parcelas = "3",
            itens = listOf(EntradaItem("10,00", "Casa", "A"), EntradaItem("5,00", "", "B")),
            dividir = true,
            pagamentos = listOf(EntradaPagamento("Nubank", "", "9,00"), EntradaPagamento("Itaú", "", "6,00")),
        )
        val r = ok(e).todos
        assertEquals(listOf(-5.0, -5.0, -5.0), r.map { it.valor })
        assertEquals(listOf(3.34, 3.33, 3.33), r.map { it.itens[0].valor })
        assertEquals(listOf(1.66, 1.67, 1.67), r.map { it.itens[1].valor })
        r.forEach { assertEquals(5.0, Regras.somar(it.itens.map { i -> i.valor }), 0.0) }
        r.forEach { assertEquals(5.0, Regras.somar(it.pagamentos.map { p -> p.valor }), 0.0) }
        assertEquals(9.0, Regras.somar(r.flatMap { it.pagamentos }.filter { it.banco == "Nubank" }.map { it.valor }), 0.0)
        assertEquals(null, r[0].descricao)
        assertEquals(null, r[0].categoria)
    }

    @Test fun parcelasInvalidasSaoRecusadas() {
        assertEquals("O número de parcelas vai de 2 a 360.", erro(base(parcelas = "1")))
        assertEquals("O número de parcelas vai de 2 a 360.", erro(base(parcelas = "361")))
        assertTrue(erro(base(parcelas = "2", valor = "0")).startsWith("O valor total precisa ser maior"))
        assertTrue(
            erro(base(parcelas = "2", itens = listOf(EntradaItem("5"), EntradaItem("-5")))).startsWith("A soma dos itens"),
        )
        assertTrue(erro(base(parcelas = "2", vencimento = "99")).startsWith("Vencimento inválido"))
    }

    @Test fun previaMostraAsParcelasSemCriarNada() {
        val (linhas, mensagem) = NovoLancamento.previa(base(parcelas = "2"), hoje)
        assertEquals("", mensagem)
        assertEquals(listOf("1/2", "2/2"), linhas!!.map { it.first })
        assertEquals(listOf(-50.0, -50.0), linhas.map { it.third })
    }
}
