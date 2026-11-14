package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Os filtros por banco, forma, estabelecimento, categoria, credor e devedor, na tela de filtro. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FiltroListasTest {
    @get:Rule
    val regra = createComposeRule()

    private val chamadas = mutableListOf<String>()
    private var limpou = 0

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = { limpou++ },
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
        aplicarListas = { a, b, c, d, e, f -> chamadas += listOf(a, b, c, d, e, f).joinToString("|") },
    )

    private fun rolarAte(texto: String) {
        regra.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    private fun abrir(filtros: Filtros = Filtros()) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", filtros = filtros,
                        opcoes = mapOf("banco" to listOf("Itaú", "Nubank"), "categoria" to listOf("Casa")),
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.onNodeWithText("Filtrar e ordenar").performClick()
    }

    @Test fun aplicarEntregaOsSeisCamposNaOrdem() {
        abrir()
        rolarAte("Banco ou cartão")
        regra.onNodeWithText("Banco ou cartão").performTextInput("Nubank")
        rolarAte("Categoria")
        regra.onNodeWithText("Categoria").performTextInput("Casa")
        rolarAte("Aplicar filtros")
        regra.onNodeWithText("Aplicar filtros").performClick()
        assertEquals(listOf("Nubank|||Casa||"), chamadas)
    }

    @Test fun camposVemPreenchidosComOFiltroAtual() {
        abrir(Filtros(banco = "Itaú"))
        rolarAte("Banco ou cartão")
        regra.onNodeWithText("Itaú").assertExists()
    }

    @Test fun limparEsvaziaTambemAsListas() {
        abrir(Filtros(banco = "Itaú"))
        rolarAte("Limpar filtros")
        regra.onNodeWithText("Limpar filtros").performClick()
        assertEquals(1, limpou)
    }
}
