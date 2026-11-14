package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatacaoTest {
    private fun lanc(
        estabelecimento: String? = "Mercado", valor: Double = -120.0, pago: Boolean = false,
        origem: String? = "2026-03-01", vencimento: String? = "2026-03-05",
        banco: String? = "Nubank", forma: String? = "Cartão",
        parcela: String? = null, categoria: String? = "Alimentação", descricao: String? = null,
    ) = Lancamento(
        1, origem, vencimento, valor, banco, estabelecimento, forma, parcela, descricao, categoria, pago,
    )

    @Test fun reaisEnumeros() {
        assertEquals("R$ 1.234,50", Formatacao.reais(1234.5))
        assertEquals("R$ 0,00", Formatacao.reais(0.0))
        assertEquals("-R$ 10,00", Formatacao.reais(-10.0))
        assertEquals("1.234,50", Formatacao.numero(1234.5))
        assertEquals("-10,00", Formatacao.numero(-10.0))
    }

    @Test fun valorComSinalMostraEntradaESaida() {
        assertEquals("+10,00", Formatacao.valorComSinal(10.0))
        assertEquals("-10,00", Formatacao.valorComSinal(-10.0))
        assertEquals("+0,00", Formatacao.valorComSinal(0.0))
    }

    @Test fun falaDoValorUsaAPalavraMenos() {
        assertEquals("R$ 5,00", Formatacao.reaisFalado(5.0))
        assertEquals("menos R$ 5,00", Formatacao.reaisFalado(-5.0))
    }

    @Test fun datas() {
        assertEquals("05/03/2026", Formatacao.dataCurta("2026-03-05"))
        assertEquals("05/03/2026", Formatacao.dataCurta("2026-03-05T10:00:00+00:00"))
        assertEquals("5 de março de 2026", Formatacao.dataFalada("2026-03-05"))
        assertEquals("texto", Formatacao.dataCurta("texto"))
        assertEquals("", Formatacao.dataCurta(null))
    }

    @Test fun falaDizEntradaOuSaidaPorPalavraSemDependerDeCor() {
        assertEquals(
            "Mercado, saída de R$ 120,00, origem 1 de março de 2026, vencimento 5 de março de 2026, " +
                "Nubank, Cartão, Alimentação, Pendente",
            Formatacao.fala(lanc()),
        )
        assertTrue(Formatacao.fala(lanc(valor = 50.0, pago = true)).contains("entrada de R$ 50,00"))
        assertTrue(Formatacao.fala(lanc(pago = true)).endsWith("Pago"))
    }

    @Test fun falaIgnoraCamposVaziosEUsaTituloDeReserva() {
        val l = lanc(
            estabelecimento = null, descricao = null, categoria = null, banco = null, forma = null,
            origem = null, vencimento = null, valor = 120.0,
        )
        assertEquals("Sem descrição, entrada de R$ 120,00, Pendente", Formatacao.fala(l))
    }

    @Test fun estabelecimentoContinuaVisivelQuandoADescricaoViraOTitulo() {
        val l = lanc(descricao = "Compras do mês")
        assertEquals("Compras do mês", Formatacao.titulo(l))
        assertTrue(Formatacao.fala(l).contains("Mercado"))
        assertTrue(Formatacao.detalhes(l).contains("Mercado"))
    }

    @Test fun parcelaEntraNaFalaENosDetalhes() {
        val l = lanc(parcela = "2/10")
        assertTrue(Formatacao.fala(l).contains("parcela 2/10"))
        assertTrue(Formatacao.detalhes(l).contains("Parcela 2/10"))
    }

    @Test fun resumoFalado() {
        val r = Resumo(quantidade = 3, entradas = 1000.0, saidas = -250.5, total = 749.5, pago = 700.0, pendente = 49.5)
        assertEquals(
            "Resumo: 3 lançamentos. Entradas, R$ 1.000,00. Saídas, R$ 250,50. Saldo, R$ 749,50. " +
                "Pago, R$ 700,00. Pendente, R$ 49,50.",
            Formatacao.resumoFalado(r),
        )
    }

    @Test fun resumoFaladoComSaldoNegativo() {
        val r = Resumo(quantidade = 1, saidas = -30.0, total = -30.0, pendente = -30.0)
        assertTrue(Formatacao.resumoFalado(r).contains("Saldo, menos R$ 30,00."))
    }
}
