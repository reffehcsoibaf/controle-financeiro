package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasTest {
    private fun l(
        id: Long, valor: Double, pago: Boolean = false, origem: String? = "2026-03-01",
        descricao: String? = null, estabelecimento: String? = null, categoria: String? = null,
        parcela: String? = null, itens: List<Item> = emptyList(),
    ) = Lancamento(id, origem, null, valor, null, estabelecimento, null, parcela, descricao, categoria, pago, itens = itens)

    @Test fun resumirSomaEntradasSaidasPagoEPendente() {
        val r = Regras.resumir(
            listOf(
                l(1, 1000.0, pago = true),
                l(2, -200.0, pago = true),
                l(3, -50.5, pago = false),
                l(4, 30.0, pago = false),
            )
        )
        assertEquals(4, r.quantidade)
        assertEquals(1030.0, r.entradas, 0.001)
        assertEquals(-250.5, r.saidas, 0.001)
        assertEquals(779.5, r.total, 0.001)
        assertEquals(800.0, r.pago, 0.001)
        assertEquals(-20.5, r.pendente, 0.001)
    }

    @Test fun resumoDeListaVazia() {
        assertEquals(Resumo(), Regras.resumir(emptyList()))
    }

    @Test fun valorZeroContaComoEntrada() {
        assertTrue(Regras.ehEntrada(l(1, 0.0)))
        assertTrue(!Regras.ehEntrada(l(2, -0.01)))
    }

    @Test fun recentesPrimeiroMasNoMesmoDiaPelaOrdemDeCriacao() {
        val lista = listOf(
            l(3, 1.0, origem = "2026-03-02"),
            l(1, 1.0, origem = "2026-03-01"),
            l(5, 1.0, origem = "2026-03-02"),
            l(2, 1.0, origem = "2026-03-01"),
        )
        assertEquals(listOf(3L, 5L, 1L, 2L), Regras.ordenar(lista).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 5L), Regras.ordenar(lista, recentesPrimeiro = false).map { it.id })
    }

    @Test fun semDataVaiParaOFimNosRecentes() {
        val lista = listOf(l(1, 1.0, origem = null), l(2, 1.0, origem = "2026-01-01"))
        assertEquals(listOf(2L, 1L), Regras.ordenar(lista).map { it.id })
    }

    @Test fun idTemporarioNegativoFicaDepoisDosGravadosNoMesmoDia() {
        val lista = listOf(l(-1, 1.0), l(7, 1.0), l(8, 1.0))
        assertEquals(listOf(7L, 8L, -1L), Regras.ordenar(lista).map { it.id })
    }

    @Test fun descricaoVemDosItensQuandoHa() {
        val itens = listOf(Item(5.0, "Mercado", "Arroz"), Item(3.0, "Mercado", "Feijão"), Item(1.0, null, null))
        val lanc = l(1, -9.0, descricao = "Outra", itens = itens)
        assertEquals("Arroz / Feijão", Regras.descricaoDe(lanc))
        assertEquals("Arroz / Feijão — 1/3", Regras.descricaoDe(lanc.copy(parcela = "1/3")))
    }

    @Test fun descricaoSemItensUsaDescricaoDepoisEstabelecimento() {
        assertEquals("Luz", Regras.descricaoDe(l(1, -1.0, descricao = "Luz", estabelecimento = "Cemig")))
        assertEquals("Cemig", Regras.descricaoDe(l(1, -1.0, estabelecimento = "Cemig")))
        assertEquals("Sem descrição", Regras.descricaoDe(l(1, -1.0)))
    }

    @Test fun categoriasDosItensSemRepetir() {
        val itens = listOf(Item(1.0, "A", null), Item(1.0, "B", null), Item(1.0, "A", null), Item(1.0, null, null))
        assertEquals("A, B", Regras.categoriasDe(l(1, -3.0, categoria = "Z", itens = itens)))
        assertEquals("Z", Regras.categoriasDe(l(1, -3.0, categoria = "Z")))
        assertEquals("", Regras.categoriasDe(l(1, -3.0)))
    }

    @Test fun valorComoTextoOuNumeroEntendeDoisFormatos() {
        val a = Lancamento.fromJson(JSONObject("""{"id":1,"valor":"-12.50","pago":false}"""))
        val b = Lancamento.fromJson(JSONObject("""{"id":2,"valor":-12.5,"pago":false}"""))
        assertEquals(-12.5, a.valor, 0.0)
        assertEquals(-12.5, b.valor, 0.0)
    }
}
