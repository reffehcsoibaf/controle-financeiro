package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NovoLancamentoTest {
    private fun entrada(
        valor: String = "12,50", origem: String = "05/03/2026", vencimento: String = "",
        entrada: Boolean = false, categoria: String = "", pago: Boolean = false,
    ) = EntradaNovo(entrada = entrada, valor = valor, origem = origem, vencimento = vencimento, categoria = categoria, pago = pago)

    private fun ok(e: EntradaNovo) = (NovoLancamento.validar(e) as ResultadoNovo.Ok).dados
    private fun erro(e: EntradaNovo) = (NovoLancamento.validar(e) as ResultadoNovo.Erro).mensagem

    // ---- valor ----
    @Test fun valorComoSeDigitaNoBrasil() {
        assertEquals(1234.5, Regras.lerValor("1.234,50"), 0.0)
        assertEquals(12.5, Regras.lerValor("12,5"), 0.0)
        assertEquals(12.5, Regras.lerValor("12.50"), 0.0)
        assertEquals(1234.0, Regras.lerValor("1.234"), 0.0)
        assertEquals(10.0, Regras.lerValor("R$ 10,00"), 0.0)
        assertEquals(7.0, Regras.lerValor("7"), 0.0)
        assertEquals(1234567.89, Regras.lerValor("1.234.567,89"), 0.0)
    }

    @Test fun valorInvalidoLevantaErro() {
        for (ruim in listOf("", "   ", "abc", "1,2,3", "R$", "NaN", "Infinity")) {
            try {
                Regras.lerValor(ruim)
                fail("devia recusar '$ruim'")
            } catch (e: IllegalArgumentException) {
                // esperado
            }
        }
    }

    // ---- validação ----
    @Test fun saidaViraValorNegativoEEntradaPositivo() {
        assertEquals(-12.5, ok(entrada()).valor, 0.0)
        assertEquals(12.5, ok(entrada(entrada = true)).valor, 0.0)
        // O sinal vem do tipo, nunca do que foi digitado.
        assertEquals(-12.5, ok(entrada(valor = "-12,50")).valor, 0.0)
        assertEquals(12.5, ok(entrada(valor = "-12,50", entrada = true)).valor, 0.0)
    }

    @Test fun datasViramIsoEVencimentoVazioViraNull() {
        val d = ok(entrada(origem = "05032026"))
        assertEquals("2026-03-05", d.dataOrigem)
        assertNull(d.dataVencimento)
        assertEquals("2026-04-10", ok(entrada(vencimento = "10042026")).dataVencimento)
    }

    @Test fun textosSaemSemEspacosEVaziosViramNull() {
        val e = entrada().copy(banco = "  Nubank ", forma = "   ", descricao = " compra ")
        val d = ok(e)
        assertEquals("Nubank", d.banco)
        assertNull(d.forma)
        assertEquals("compra", d.descricao)
        assertNull(d.estabelecimento)
    }

    @Test fun pagoVaiComoMarcado() {
        assertTrue(ok(entrada(pago = true)).pago)
    }

    @Test fun origemObrigatoriaEValida() {
        assertTrue(erro(entrada(origem = "")).startsWith("Preencha a data de origem"))
        assertTrue(erro(entrada(origem = "31022026")).startsWith("Preencha a data de origem"))
    }

    @Test fun vencimentoInvalidoRecusaMasVazioPassa() {
        assertTrue(erro(entrada(vencimento = "99999999")).startsWith("Vencimento inválido"))
        assertNull(ok(entrada(vencimento = "")).dataVencimento)
    }

    @Test fun valorObrigatorioEEmNumeros() {
        assertTrue(erro(entrada(valor = "")).startsWith("Informe o valor"))
        assertTrue(erro(entrada(valor = "doze")).startsWith("Informe o valor"))
    }

    @Test fun pagamentoDeFaturaEBloqueadoPoisPrecisaDoCartaoAQuitar() {
        for (cat in listOf("Cartão de crédito", "cartão de crédito", "  CARTÃO DE CRÉDITO ")) {
            assertTrue(erro(entrada(categoria = cat)).contains("cartão a quitar"))
        }
        // Outra categoria que só contém a palavra cartão não é fatura.
        assertEquals("Cartão de débito", ok(entrada(categoria = "Cartão de débito")).categoria)
    }

    @Test fun aOrdemDosErrosEAdoWindowsDatasDepoisValor() {
        // Com tudo errado, a primeira queixa é a da data de origem.
        assertTrue(erro(entrada(valor = "x", origem = "")).startsWith("Preencha a data de origem"))
        assertTrue(erro(entrada(valor = "x", vencimento = "99")).startsWith("Vencimento inválido"))
    }

    // ---- sugestões ----
    private val opcoes = listOf("Mercado Livre", "Padaria Pão", "Supermercado", "Açougue", "Mercearia")

    @Test fun sugestoesIgnoramAcentoEMaiusculaEPriorizamQuemComeca() {
        assertEquals(listOf("Mercado Livre", "Mercearia", "Supermercado"), NovoLancamento.sugestoes(opcoes, "MERC", 10).let {
            // começam por "merc": Mercado Livre e Mercearia; depois quem só contém: Supermercado
            it
        })
        assertEquals(listOf("Açougue"), NovoLancamento.sugestoes(opcoes, "acougue", 10))
        assertEquals(listOf("Padaria Pão"), NovoLancamento.sugestoes(opcoes, "pao", 10))
    }

    @Test fun semTextoMostraAsPrimeirasELimitaOTamanho() {
        assertEquals(opcoes.take(2), NovoLancamento.sugestoes(opcoes, "", 2))
        assertEquals(2, NovoLancamento.sugestoes(opcoes, "e", 2).size)
    }

    @Test fun semCorrespondenciaNaoSugereNada() {
        assertEquals(emptyList<String>(), NovoLancamento.sugestoes(opcoes, "xyz", 5))
    }
}
