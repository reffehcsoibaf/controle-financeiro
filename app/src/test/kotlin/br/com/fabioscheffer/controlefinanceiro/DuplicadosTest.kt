package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
class DuplicadosTest {
    @get:Rule
    val regra = createComposeRule()

    private fun l(id: Long, desc: String = "Luz", valor: Double = -10.0, banco: String? = "Nubank") =
        Lancamento(id, "2026-10-01", "2026-10-10", valor, banco, null, null, null, desc, "Casa", true)

    @Test fun ficaOMaisAntigoDeCadaGrupo() {
        val todos = listOf(l(5), l(2), l(9), l(3, "Água"))
        assertEquals(listOf(5L, 9L), Duplicados.copias(todos).map { it.id })
    }

    @Test fun ignoraMaiusculaEEspacoNosTextos() {
        val a = l(1, "Luz", banco = "Nubank")
        val b = l(2, " luz ", banco = "NUBANK")
        assertEquals(listOf(2L), Duplicados.copias(listOf(a, b)).map { it.id })
    }

    @Test fun valoresDiferentesNaoSaoDuplicados() {
        assertTrue(Duplicados.copias(listOf(l(1), l(2, valor = -10.01))).isEmpty())
    }

    @Test fun comItensADescricaoNaoContaMasOsItensContam() {
        val a = l(1, "A").copy(itens = listOf(Item(-10.0, "Mercado", "Arroz")))
        val b = l(2, "B").copy(itens = listOf(Item(-10.0, "Mercado", "Arroz")))
        val c = l(3, "C").copy(itens = listOf(Item(-10.0, "Mercado", "Feijão")))
        assertEquals(listOf(2L), Duplicados.copias(listOf(a, b, c)).map { it.id })
    }

    @Test fun temporarioNaoEntra() {
        assertTrue(Duplicados.copias(listOf(l(-1), l(-2))).isEmpty())
    }

    private var limpou = false

    private fun abrirTela(todos: List<Lancamento>) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = todos, lancamentos = todos),
                    "0.0.0",
                    Acoes(
                        entrar = {}, atualizar = {}, sair = {},
                        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
                        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
                        marcarPago = { _, _ -> },
                        alternarCampo = {}, moverCampo = { _, _ -> },
                        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
                        importarConfiguracoes = {},
                        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
                        limparDuplicados = { limpou = true },
                    ),
                )
            }
        }
        regra.onNodeWithText("Configurações").performClick()
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("Limpar lançamentos duplicados"))
        regra.onNodeWithText("Limpar lançamentos duplicados").performClick()
    }

    @Test fun semDuplicadosAvisa() {
        abrirTela(listOf(l(1), l(2, "Água")))
        regra.onNodeWithText("Nenhum lançamento duplicado encontrado.").assertIsDisplayed()
    }

    @Test fun comDuplicadosPedeConfirmacaoEExclui() {
        abrirTela(listOf(l(1), l(2)))
        regra.onNodeWithText("Não dá para desfazer.").assertIsDisplayed()
        assertTrue(!limpou)
        regra.onNodeWithText("Excluir a cópia").performClick()
        assertTrue(limpou)
    }
}
