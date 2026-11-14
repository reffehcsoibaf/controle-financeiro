package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CamposTest {
    private fun l(
        estabelecimento: String? = "Mercado", valor: Double = -120.0, pago: Boolean = false,
        banco: String? = "Nubank", forma: String? = "Cartão", credor: String? = null,
        itens: List<Item> = emptyList(),
    ) = Lancamento(
        1, "2026-03-01", "2026-03-05", valor, banco, estabelecimento, forma, null, null, "Alimentação", pago,
        credor = credor, itens = itens,
    )

    @Test fun padraoTemTodosOsCamposSemRepetirEComItensCredorEDevedorDesligados() {
        val p = Campos.padrao()
        assertEquals(Campos.TODOS.map { it.chave }.sorted(), p.map { it.chave }.sorted())
        assertEquals(setOf("itens", "credor", "devedor"), p.filter { !it.visivel }.map { it.chave }.toSet())
    }

    @Test fun serializarEDesserializarNaoPerdeNada() {
        val c = Campos.mover(Campos.padrao(), "situacao", -3)
        assertEquals(c, Campos.desserializar(Campos.serializar(c)))
    }

    @Test fun desserializarIgnoraLixoRepetidoEAcrescentaOQueFalta() {
        val lido = Campos.desserializar("valor:1,naoexiste:1,valor:0,descricao:1")
        assertEquals(listOf("valor", "descricao"), lido.take(2).map { it.chave })
        assertTrue(lido.first().visivel)
        assertEquals(Campos.TODOS.size, lido.size)
        assertFalse(lido.first { it.chave == "situacao" }.visivel)
    }

    @Test fun semNenhumCampoLigadoVoltaAoPadrao() {
        assertEquals(Campos.padrao(), Campos.desserializar("valor:0,descricao:0"))
        assertEquals(Campos.padrao(), Campos.desserializar(null))
        assertEquals(Campos.padrao(), Campos.desserializar(""))
    }

    @Test fun moverTrocaComOVizinhoENosExtremosNaoFazNada() {
        val p = Campos.padrao()
        assertEquals(listOf("valor", "descricao"), Campos.mover(p, "valor", -1).take(2).map { it.chave })
        assertEquals(listOf("valor", "descricao"), Campos.mover(p, "descricao", 1).take(2).map { it.chave })
        assertSame(p, Campos.mover(p, "descricao", -1))
        assertSame(p, Campos.mover(p, p.last().chave, 1))
        assertSame(p, Campos.mover(p, "naoexiste", 1))
    }

    @Test fun ordemDaFalaSegueAsConfiguracoes() {
        val ordem = listOf(CampoConfig("situacao", true), CampoConfig("valor", true), CampoConfig("descricao", true))
        assertEquals("Pendente, saída de R$ 120,00, Mercado", Formatacao.fala(l(), ordem))
    }

    @Test fun campoDesligadoNaoEntraNaFalaNemNaLinha() {
        val sem = Campos.padrao().map { if (it.chave == "forma") it.copy(visivel = false) else it }
        assertFalse(Formatacao.fala(l(), sem).contains("Cartão"))
        assertFalse(Formatacao.detalhes(l(), sem).contains("Cartão"))
        assertTrue(Formatacao.fala(l(), Campos.padrao()).contains("Cartão"))
    }

    @Test fun camposNovosLigadosEntramNaFala() {
        val com = Campos.padrao().map { if (it.chave == "credor" || it.chave == "itens") it.copy(visivel = true) else it }
        val fala = Formatacao.fala(l(credor = "Loja X", itens = listOf(Item(1.0, null, null), Item(2.0, null, null))), com)
        assertTrue(fala.contains("credor Loja X"))
        assertTrue(fala.contains("2 itens"))
    }

    @Test fun semNadaParaFalarCaiNoTitulo() {
        val soItens = listOf(CampoConfig("itens", true))
        assertEquals("Mercado", Formatacao.fala(l(), soItens))
    }

    @Test fun detalhesMostramTudoMasNaOrdemEscolhida() {
        val ordem = listOf(CampoConfig("situacao", true), CampoConfig("valor", false), CampoConfig("descricao", true))
        val nomes = Formatacao.camposDoDetalhe(l(), Campos.normalizar(ordem)).map { it.first }
        // Ligados primeiro (Situação, Descrição), depois os desligados, incluindo Valor.
        assertEquals(listOf("Situação", "Descrição"), nomes.take(2))
        assertTrue("Valor" in nomes && "Tipo" in nomes)
    }
}
