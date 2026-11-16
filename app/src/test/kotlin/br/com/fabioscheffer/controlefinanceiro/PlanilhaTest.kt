package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanilhaTest {
    private fun simples(id: Long, desc: String = "Luz & Água <1>", valor: Double = -10.5) =
        Lancamento(id, "2026-10-01", "2026-10-10", valor, "Nubank", "Cemig", "Pix", null, desc, "Casa", true)

    private val comItens = Lancamento(
        2, "2026-10-02", null, -30.0, "Itaú", "Mercado", "Crédito", "2/3", null, null, false,
        itens = listOf(Item(-10.0, "Mercado", "Arroz", "2026-09-01"), Item(-20.0, "Limpeza", "Sabão", null)),
    )

    private val dividido = Lancamento(
        3, "2026-10-03", "2026-10-03", 100.0, null, null, null, null, "Venda", "Renda", true,
        pagamentos = listOf(Pagamento("Nubank", "Pix", 60.0), Pagamento("Itaú", "Dinheiro", 40.0)),
    )

    @Test fun exportarELerDevolveOsMesmosLancamentos() {
        val lidos = Planilha.ler(Planilha.exportar(listOf(simples(1), comItens, dividido)))
        assertEquals(3, lidos.size)
        assertEquals("Luz & Água <1>", lidos[0].descricao)
        assertEquals(-10.5, lidos[0].valor, 0.0)
        assertEquals("2026-10-01", lidos[0].dataOrigem)
        assertEquals("2026-10-10", lidos[0].dataVencimento)
        assertTrue(lidos[0].pago)
        assertEquals(2, lidos[1].itens.size)
        assertEquals("2026-09-01", lidos[1].itens[0].dataOriginal)
        assertEquals("Sabão", lidos[1].itens[1].descricao)
        assertEquals("2/3", lidos[1].parcela)
        assertEquals(false, lidos[1].pago)
        assertEquals(null, lidos[2].banco)
        assertEquals(listOf(60.0, 40.0), lidos[2].pagamentos.map { it.valor })
        assertEquals("Itaú", lidos[2].pagamentos[1].banco)
    }

    @Test fun aSegundaAbaEOResumo() {
        val abas = Planilha.abas(Planilha.exportar(listOf(simples(1), simples(2, "Outra", -4.5))))
        assertEquals(listOf("Lançamentos", "Resumo"), abas.map { it.nome })
        assertEquals("2", abas[1].linhas[1][1].toString().removeSuffix(".0"))
        assertEquals("-15.00", abas[1].linhas[2][1])
    }

    @Test fun reimportarOQueJaExisteNaoTrazNada() {
        val existentes = listOf(simples(1), comItens, dividido)
        val lidos = Planilha.ler(Planilha.exportar(existentes))
        val (novos, repetidos) = Planilha.novosERepetidos(lidos, existentes)
        assertTrue(novos.isEmpty())
        assertEquals(3, repetidos)
    }

    @Test fun soOQueEraNovoEntra() {
        val lidos = Planilha.ler(Planilha.exportar(listOf(simples(1), simples(2, "Nova", -1.0))))
        val (novos, repetidos) = Planilha.novosERepetidos(lidos, listOf(simples(9)))
        assertEquals(listOf("Nova"), novos.map { it.descricao })
        assertEquals(1, repetidos)
    }

    @Test fun planilhaSemAsColunasEsperadasEhRecusada() {
        val bytes = Planilha.exportar(emptyList())
        // Uma planilha só com cabeçalho não tem lançamentos, mas as colunas estão certas.
        assertTrue(Planilha.ler(bytes).isEmpty())
        try {
            Planilha.ler(ByteArray(10))
            throw AssertionError("devia recusar")
        } catch (e: ErroDocumento) {
            assertTrue(e.message!!.contains("planilha"))
        }
    }

    @Test fun planilhaEmTextoParaAIA() {
        val texto = Planilha.emTexto(Planilha.exportar(listOf(simples(1))))
        assertTrue(texto.startsWith("--- Planilha: Lançamentos ---"))
        assertTrue(texto.contains("Luz & Água <1>"))
        assertTrue(texto.contains("--- Planilha: Resumo ---"))
    }

    @Test fun opcoesDasLinhasNovas() {
        val o = Planilha.opcoesDe(Planilha.ler(Planilha.exportar(listOf(comItens, dividido))))
        assertEquals(setOf("Itaú", "Nubank"), o.getValue("banco"))
        assertTrue("Limpeza" in o.getValue("categoria"))
        assertTrue("Dinheiro" in o.getValue("forma"))
    }
}
