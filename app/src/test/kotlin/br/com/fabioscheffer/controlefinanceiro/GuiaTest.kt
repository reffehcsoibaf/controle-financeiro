package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuiaTest {
    @get:Rule
    val regra = createComposeRule()

    @Test fun guiaAbreDaAbaMaisEMostraOsAssuntos() {
        regra.setContent {
            TemaApp {
                Tela(Estado(etapa = Etapa.CONECTADO, email = "a@b.c"), "0.0.0", Acoes(
                    entrar = {}, atualizar = {}, sair = {},
                    definirOrdem = {}, definirTipo = {}, definirSituacao = {},
                    aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
                    marcarPago = { _, _ -> },
                    alternarCampo = {}, moverCampo = { _, _ -> },
                    definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
                    importarConfiguracoes = {},
                    abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
                ))
            }
        }
        regra.onNodeWithText("Configurações").performClick()
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("Guia de uso"))
        regra.onNodeWithText("Guia de uso").performClick()
        regra.onNodeWithText("Primeiros passos").assertIsDisplayed()
    }

    @Test fun guiaNaoFalaDeLeitorDeTela() {
        val texto = Guia.assuntos.joinToString(" ") { it.first + " " + it.second.joinToString(" ") }.lowercase()
        assertFalse("talkback" in texto || "leitor de tela" in texto)
        assertTrue(Guia.assuntos.all { it.second.isNotEmpty() })
    }
}
