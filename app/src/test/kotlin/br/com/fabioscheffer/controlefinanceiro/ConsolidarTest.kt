package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsolidarTest {
    @get:Rule
    val regra = createComposeRule()

    private fun l(id: Long, desc: String, valor: Double, pago: Boolean = false, credor: String? = null, origem: String = "2026-10-01") =
        Lancamento(id, origem, "2026-10-20", valor, "Nubank", null, null, null, desc, "Casa", pago, credor)

    private val todos = listOf(
        l(1, "Luz", -100.0, credor = "Cemig"),
        l(2, "Água", -50.0, pago = true),
        l(3, "Internet", -80.0, origem = "2026-09-01"),
    )

    private fun filtrar(
        busca: String = "", credor: String = "", pagos: Boolean = false, de: String = "", excluir: Set<Long> = emptySet(),
    ) = Consolidar.filtrar(todos, excluir, busca, credor, "", pagos, de, "", "", "").map { it.id }

    @Test fun porPadraoSoMostraOsPendentesDoMaisNovoParaOMaisAntigo() {
        assertEquals(listOf(1L, 3L), filtrar())
        assertEquals(listOf(1L, 2L, 3L), filtrar(pagos = true))
    }

    @Test fun buscaIgnoraAcentoEFiltraPorCredorEData() {
        assertEquals(listOf(1L), filtrar(busca = "LUZ"))
        assertEquals(listOf(1L), filtrar(credor = "cemig"))
        assertEquals(listOf(1L), filtrar(de = "2026-10-01"))
        assertEquals(listOf(3L), filtrar(excluir = setOf(1L)))
    }

    @Test fun lancamentoSemItensViraUmItemPositivoComADataOriginal() {
        val itens = Consolidar.itensDe(todos[0])
        assertEquals(1, itens.size)
        assertEquals("100,00", itens[0].valor)
        assertEquals("Luz", itens[0].descricao)
        assertEquals("2026-10-01", itens[0].dataOriginal)
        assertEquals(1L, itens[0].origemId)
    }

    @Test fun lancamentoComItensLevaOsItensDele() {
        val com = todos[0].copy(itens = listOf(Item(-60.0, "A", "Um", null), Item(-40.0, "B", null, null)))
        val itens = Consolidar.itensDe(com)
        assertEquals(listOf("60,00", "40,00"), itens.map { it.valor })
        assertEquals("(sem descrição)", itens[1].descricao)
    }

    @Test fun marcarEImportarEntregaOsLancamentosMarcados() {
        var recebidos = emptyList<Lancamento>()
        regra.setContent {
            TemaApp {
                TelaConsolidar(
                    Estado(etapa = Etapa.CONECTADO, todos = todos, lancamentos = todos),
                    excluir = emptySet(), aoCancelar = {}, aoImportar = { recebidos = it },
                )
            }
        }
        regra.onNodeWithText("Importar 0 lançamentos").assertIsDisplayed()
        regra.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasContentDescription("Luz", substring = true))
        regra.onNode(hasContentDescription("Luz", substring = true)).performClick()
        regra.onNodeWithText("Importar 1 lançamento").performClick()
        assertEquals(listOf(1L), recebidos.map { it.id })
        assertTrue(recebidos.isNotEmpty())
    }
}
