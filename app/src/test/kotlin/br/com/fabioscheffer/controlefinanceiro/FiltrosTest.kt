package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FiltrosTest {
    private fun l(
        id: Long, valor: Double = -10.0, pago: Boolean = false, origem: String? = "2026-03-01",
        venc: String? = null, banco: String? = null, forma: String? = null, categoria: String? = null,
        estabelecimento: String? = null, descricao: String? = null, itens: List<Item> = emptyList(),
        pagamentos: List<Pagamento> = emptyList(), credor: String? = null,
    ) = Lancamento(
        id, origem, venc, valor, banco, estabelecimento, forma, null, descricao, categoria, pago,
        credor = credor, itens = itens, pagamentos = pagamentos,
    )

    private fun ids(lista: List<Lancamento>) = lista.map { it.id }

    private val base = listOf(
        l(1, 100.0, pago = true, origem = "2026-03-01", descricao = "Salário"),
        l(2, -50.0, pago = false, origem = "2026-03-02", descricao = "Mercado"),
        l(3, -20.0, pago = true, origem = "2026-03-03", descricao = "Padaria"),
    )

    @Test fun semFiltroMostraTudoMaisRecentesPrimeiro() {
        assertEquals(listOf(3L, 2L, 1L), ids(Regras.filtrar(base, Filtros())))
        assertEquals(listOf(1L, 2L, 3L), ids(Regras.filtrar(base, Filtros(ordem = "antigos"))))
    }

    @Test fun tipoEntradaOuSaida() {
        assertEquals(listOf(1L), ids(Regras.filtrar(base, Filtros(tipo = "entrada"))))
        assertEquals(listOf(3L, 2L), ids(Regras.filtrar(base, Filtros(tipo = "saida"))))
    }

    @Test fun situacaoPagoOuPendente() {
        assertEquals(listOf(3L, 1L), ids(Regras.filtrar(base, Filtros(status = "pago"))))
        assertEquals(listOf(2L), ids(Regras.filtrar(base, Filtros(status = "pendente"))))
    }

    @Test fun filtrosSeCombinam() {
        assertEquals(listOf(3L), ids(Regras.filtrar(base, Filtros(tipo = "saida", status = "pago"))))
    }

    @Test fun buscaIgnoraAcentosEMaiusculas() {
        val lista = listOf(l(1, descricao = "Cafeteria São João"), l(2, descricao = "Outro"))
        assertEquals(listOf(1L), ids(Regras.filtrar(lista, Filtros(busca = "SAO joao"))))
    }

    @Test fun buscaAchaNoValorFormatadoENaData() {
        val lista = listOf(l(1, valor = -1234.5, origem = "2026-03-05"), l(2, valor = -7.0, origem = "2026-04-09"))
        assertEquals(listOf(1L), ids(Regras.filtrar(lista, Filtros(busca = "1.234,50"))))
        assertEquals(listOf(2L), ids(Regras.filtrar(lista, Filtros(busca = "09/04/2026"))))
    }

    @Test fun buscaAchaNosItens() {
        val lista = listOf(
            l(1, itens = listOf(Item(3.0, "Higiene", "Sabonete"))),
            l(2, itens = listOf(Item(3.0, "Comida", "Arroz"))),
        )
        assertEquals(listOf(1L), ids(Regras.filtrar(lista, Filtros(busca = "sabonete"))))
        assertEquals(listOf(2L), ids(Regras.filtrar(lista, Filtros(busca = "comida"))))
    }

    @Test fun periodoDeOrigemIncluiOsExtremos() {
        val f = Filtros(origemDe = "2026-03-02", origemAte = "2026-03-03")
        assertEquals(listOf(3L, 2L), ids(Regras.filtrar(base, f)))
    }

    @Test fun lancamentoSemVencimentoNaoSaiPeloFiltroDeVencimento() {
        val lista = listOf(l(1, venc = null), l(2, venc = "2026-05-10"), l(3, venc = "2026-06-10"))
        assertEquals(listOf(1L, 2L), ids(Regras.filtrar(lista, Filtros(vencAte = "2026-05-31")).sortedBy { it.id }))
    }

    @Test fun bancoTambemVemDosPagamentos() {
        val lista = listOf(
            l(1, banco = "Nubank"),
            l(2, banco = "Itaú", pagamentos = listOf(Pagamento("Nubank", null, 5.0))),
            l(3, banco = "Itaú"),
        )
        assertEquals(listOf(1L, 2L), ids(Regras.filtrar(lista, Filtros(banco = "Nubank"))).sorted())
    }

    @Test fun categoriaTambemVemDosItens() {
        val lista = listOf(
            l(1, categoria = "Lazer"),
            l(2, itens = listOf(Item(1.0, "Lazer", null))),
            l(3, categoria = "Casa"),
        )
        assertEquals(listOf(1L, 2L), ids(Regras.filtrar(lista, Filtros(categoria = "Lazer"))).sorted())
    }

    @Test fun avisoDeFiltroSoQuandoEscondeAlgo() {
        assertNull(Formatacao.avisoDeFiltro(3, 3, false))
        assertEquals("Mostrando 1 de 3 lançamentos.", Formatacao.avisoDeFiltro(1, 3, false))
        assertEquals("Com filtros. Mostrando 3 de 3 lançamentos.", Formatacao.avisoDeFiltro(3, 3, true))
        assertEquals("Com filtros. Mostrando 0 de 1 lançamento.", Formatacao.avisoDeFiltro(0, 1, true))
    }

    @Test fun resumoFaladoComecaComOAvisoDeFiltro() {
        val fala = Formatacao.resumoFalado(Resumo(quantidade = 1), "Mostrando 1 de 3 lançamentos.")
        assertTrue(fala.startsWith("Mostrando 1 de 3 lançamentos. Resumo: 1 lançamento."))
    }

    @Test fun ordemTipoESituacaoNaoContamComoFiltroDaJanela() {
        assertFalse(Regras.temFiltroDaJanela(Filtros(ordem = "antigos", tipo = "saida", status = "pago")))
        assertTrue(Regras.temFiltroDaJanela(Filtros(busca = "x")))
        assertFalse(Regras.temFiltroDaJanela(Filtros(busca = "   ")))
        assertTrue(Regras.temFiltroDaJanela(Filtros(vencDe = "2026-01-01")))
    }

    @Test fun lerDataAceitaOsFormatosDigitados() {
        assertEquals("2026-10-25", Regras.lerData("25/10/2026"))
        assertEquals("2026-10-25", Regras.lerData("25102026"))
        assertEquals("2026-10-25", Regras.lerData("251026"))
        assertEquals("2026-10-25", Regras.lerData("25/10/26"))
        assertNull(Regras.lerData(""))
        assertNull(Regras.lerData("   "))
        assertNull(Regras.lerData(null))
    }

    @Test fun lerDataRecusaDataQueNaoExiste() {
        for (ruim in listOf("31/02/2026", "3102", "abc", "32012026", "00012026")) {
            try {
                Regras.lerData(ruim)
                fail("devia recusar $ruim")
            } catch (e: IllegalArgumentException) {
                // esperado
            }
        }
    }
}
