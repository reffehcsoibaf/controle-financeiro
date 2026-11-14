package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetalheTest {
    private fun base(valor: Double = -120.0, itens: List<Item> = emptyList(), pagamentos: List<Pagamento> = emptyList()) =
        Lancamento(
            1, "2026-03-01", "2026-03-05", valor, "Nubank", "Mercado", "Cartão", "2/10", "Compras", "Alimentação", false,
            credor = "Loja X", devedor = null, itens = itens, pagamentos = pagamentos,
        )

    @Test fun camposNaOrdemDeLeituraSoOsPreenchidos() {
        assertEquals(
            listOf(
                // Ordem do padrão das Configurações: primeiro os campos ligados, depois os desligados.
                "Descrição" to "Compras",
                "Tipo" to "Saída",
                "Valor" to "R$ 120,00",
                "Estabelecimento" to "Mercado",
                "Data de origem" to "1 de março de 2026",
                "Vencimento" to "5 de março de 2026",
                "Banco ou cartão" to "Nubank",
                "Forma de pagamento" to "Cartão",
                "Parcela" to "2/10",
                "Categoria" to "Alimentação",
                "Situação" to "Pendente",
                "Credor" to "Loja X",
            ),
            Formatacao.camposDoDetalhe(base()),
        )
    }

    @Test fun camposVaziosNaoAparecem() {
        val vazio = Lancamento(1, null, null, 5.0, null, null, null, null, null, null, true)
        assertEquals(
            listOf("Descrição" to "Sem descrição", "Tipo" to "Entrada", "Valor" to "R$ 5,00", "Situação" to "Pago"),
            Formatacao.camposDoDetalhe(vazio),
        )
    }

    @Test fun valorDoDetalheNuncaTemSinalPoisOTipoJaDiz() {
        assertEquals("R$ 120,00", Formatacao.camposDoDetalhe(base(-120.0)).first { it.first == "Valor" }.second)
        assertEquals("R$ 120,00", Formatacao.camposDoDetalhe(base(120.0)).first { it.first == "Valor" }.second)
    }

    @Test fun itensComDescontoPorPalavra() {
        val l = base(itens = listOf(Item(10.0, "A", "Arroz"), Item(-2.0, null, "Cupom")))
        assertEquals(
            listOf("Item 1: Arroz, A, R$ 10,00", "Item 2: Cupom, desconto de R$ 2,00"),
            Formatacao.itensDoDetalhe(l),
        )
    }

    @Test fun itensAntigosGravadosNegativosSaoOrientadosPelaSoma() {
        val l = base(itens = listOf(Item(-10.0, null, "Arroz"), Item(-5.0, null, "Feijão"), Item(2.0, null, "Cupom")))
        assertEquals(listOf(10.0, 5.0, -2.0), Regras.itensOrientados(l))
        assertEquals(
            listOf(10.0, 5.0),
            Regras.itensOrientados(base(itens = listOf(Item(10.0, null, null), Item(5.0, null, null)))),
        )
    }

    @Test fun pagamentos() {
        val l = base(pagamentos = listOf(Pagamento("Nubank", "Cartão", 30.0), Pagamento(null, "Pix", -10.0)))
        assertEquals(
            listOf("Pagamento 1: Nubank, Cartão, R$ 30,00", "Pagamento 2: Pix, R$ 10,00"),
            Formatacao.pagamentosDoDetalhe(l),
        )
    }

    @Test fun updatedAtVemDoServidor() {
        val a = Lancamento.fromJson(JSONObject("""{"id":1,"valor":1,"pago":false,"updated_at":"2026-03-05T10:00:00.123+00:00"}"""))
        assertEquals("2026-03-05T10:00:00.123+00:00", a.updatedAt)
        assertNull(Lancamento.fromJson(JSONObject("""{"id":2,"valor":1,"pago":false}""")).updatedAt)
    }
}
