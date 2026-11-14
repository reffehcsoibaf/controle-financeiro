package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** O formulário de novo lançamento, testado por texto e por toque. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelaNovoTest {
    @get:Rule
    val regra = createComposeRule()

    private var abriu = 0
    private var fechou = 0
    private val criados = mutableListOf<EntradaNovo>()

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = { abriu++ }, fecharNovo = { fechou++ }, criarLancamento = { criados += it },
    )

    private val opcoes = mapOf(
        "banco" to listOf("Itaú", "Nubank"),
        "categoria" to listOf("Alimentação", "Casa"),
    )

    private fun formulario() = regra.setContent {
        TemaApp {
            Tela(
                Estado(etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true, opcoes = opcoes),
                "0.0.0", acoes(),
            )
        }
    }

    private fun rolarAte(texto: String) {
        regra.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    @Test fun botaoNovoLancamentoFicaNaFaixaFixaDaListaEAbreOFormulario() {
        regra.setContent {
            TemaApp { Tela(Estado(etapa = Etapa.CONECTADO, email = "a@b.c"), "0.0.0", acoes()) }
        }
        regra.onNodeWithText("Novo lançamento").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, abriu)
    }

    @Test fun salvarECancelarFicamFixosSemPrecisarRolar() {
        formulario()
        regra.onNodeWithText("Salvar").assertHeightIsAtLeast(48.dp)
        regra.onNodeWithText("Cancelar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, fechou)
    }

    @Test fun preencherESalvarEntregaOQueFoiDigitado() {
        formulario()
        regra.onNodeWithText("Entrada").performClick()
        rolarAte("Valor")
        regra.onNodeWithText("Valor").performTextInput("1.234,50")
        rolarAte("Vencimento (opcional)")
        regra.onNodeWithText("Vencimento (opcional)").performTextInput("10112026")
        rolarAte("Banco ou cartão")
        regra.onNodeWithText("Banco ou cartão").performTextInput("Nubank")
        rolarAte("Pago")
        regra.onNodeWithText("Pago").performClick()
        regra.onNodeWithText("Salvar").performClick()

        val e = criados.single()
        assertTrue(e.entrada)
        assertEquals("1.234,50", e.valor)
        assertEquals("10112026", e.vencimento)
        assertEquals("Nubank", e.banco)
        assertTrue(e.pago)
        // A data de origem já vem preenchida com hoje, como no Windows.
        assertEquals(Formatacao.dataCurta(java.time.LocalDate.now().toString()), e.origem)
    }

    @Test fun porPadraoEUmaSaidaNaoPaga() {
        formulario()
        regra.onNodeWithText("Salvar").performClick()
        val e = criados.single()
        assertFalse(e.entrada)
        assertFalse(e.pago)
    }

    @Test fun digitarMostraSugestoesDaListaEUsarPreencheOCampo() {
        formulario()
        rolarAte("Banco ou cartão")
        regra.onNodeWithText("Banco ou cartão").performTextInput("nub")
        rolarAte("Usar Nubank")
        regra.onNodeWithText("Usar Nubank").assertHeightIsAtLeast(48.dp).performClick()
        regra.onNodeWithText("Salvar").performClick()
        assertEquals("Nubank", criados.single().banco)
    }

    @Test fun botaoVerOpcoesMostraAsDaLista() {
        formulario()
        rolarAte("Ver opções de Categoria")
        regra.onNodeWithText("Ver opções de Categoria").performClick()
        rolarAte("Usar Alimentação")
        regra.onNodeWithText("Usar Casa").assertExists()
        regra.onNodeWithText("Esconder opções de Categoria").assertExists()
    }

    @Test fun textoLivreForaDaListaEAceito() {
        formulario()
        rolarAte("Estabelecimento")
        regra.onNodeWithText("Estabelecimento").performTextInput("Loja nova")
        regra.onNodeWithText("Salvar").performClick()
        assertEquals("Loja nova", criados.single().estabelecimento)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun altSSalva() {
        formulario()
        rolarAte("Valor")
        regra.onNodeWithText("Valor").performTextInput("5")
        regra.onNodeWithText("Valor").performKeyInput {
            keyDown(Key.AltLeft)
            pressKey(Key.S)
            keyUp(Key.AltLeft)
        }
        assertEquals("5", criados.single().valor)
    }

    // ---- itens, pagamento dividido e parcelas ----
    @Test fun itensComecamRecolhidosEAbremComUmBotao() {
        formulario()
        rolarAte("Itens do lançamento")
        regra.onNodeWithText("Itens do lançamento").assertExists()
        regra.onNodeWithText("Adicionar item").assertDoesNotExist()
        rolarAte("Abrir os itens")
        regra.onNodeWithText("Abrir os itens").assertHeightIsAtLeast(48.dp).performClick()
        rolarAte("Adicionar item")
        regra.onNodeWithText("Adicionar item").assertHeightIsAtLeast(48.dp)
        rolarAte("Recolher os itens")
        regra.onNodeWithText("Recolher os itens").assertExists()
    }

    @Test fun adicionarItemMudaOValorParaASomaEEntregaOsItens() {
        formulario()
        rolarAte("Abrir os itens")
        regra.onNodeWithText("Abrir os itens").performClick()
        rolarAte("Valor do item (com sinal de menos é desconto)")
        regra.onNodeWithText("Valor do item", substring = true).performTextInput("12,50")
        rolarAte("Descrição do item")
        regra.onNodeWithText("Descrição do item").performTextInput("Arroz")
        rolarAte("Adicionar item")
        regra.onNodeWithText("Adicionar item").performClick()
        rolarAte("Editar item 1")
        regra.onNodeWithText("Editar item 1").assertHeightIsAtLeast(48.dp)
        rolarAte("Remover item 1")
        regra.onNodeWithText("Remover item 1").assertHeightIsAtLeast(48.dp)
        regra.onNodeWithText("Salvar").performClick()
        val e = criados.single()
        assertEquals(listOf(EntradaItem("12,50", "", "Arroz")), e.itens)
    }

    @Test fun itemComSinalDeMenosEDesconto() {
        formulario()
        rolarAte("Abrir os itens")
        regra.onNodeWithText("Abrir os itens").performClick()
        rolarAte("Valor do item (com sinal de menos é desconto)")
        regra.onNodeWithText("Valor do item", substring = true).performTextInput("-5")
        rolarAte("Adicionar item")
        regra.onNodeWithText("Adicionar item").performClick()
        regra.onNodeWithText("Salvar").performClick()
        assertEquals("-5,00", criados.single().itens.single().valor)
    }

    @Test fun removerItemTiraDaLista() {
        formulario()
        rolarAte("Abrir os itens")
        regra.onNodeWithText("Abrir os itens").performClick()
        rolarAte("Valor do item (com sinal de menos é desconto)")
        regra.onNodeWithText("Valor do item", substring = true).performTextInput("3")
        rolarAte("Adicionar item")
        regra.onNodeWithText("Adicionar item").performClick()
        rolarAte("Remover item 1")
        regra.onNodeWithText("Remover item 1").performClick()
        regra.onNodeWithText("Salvar").performClick()
        assertTrue(criados.single().itens.isEmpty())
    }

    @Test fun dividirPagamentoEsconderBancoEFormaEEntregaAsPartes() {
        formulario()
        rolarAte("Dividir pagamento")
        regra.onNodeWithText("Dividir pagamento").performClick()
        regra.onNodeWithText("Banco ou cartão").assertDoesNotExist()
        rolarAte("Banco ou cartão do pagamento")
        regra.onNodeWithText("Banco ou cartão do pagamento").performTextInput("Nubank")
        rolarAte("Valor do pagamento")
        regra.onNodeWithText("Valor do pagamento").performTextInput("10")
        rolarAte("Adicionar pagamento")
        regra.onNodeWithText("Adicionar pagamento").performClick()
        rolarAte("Editar pagamento 1")
        rolarAte("Remover pagamento 1")
        regra.onNodeWithText("Remover pagamento 1").assertHeightIsAtLeast(48.dp)
        regra.onNodeWithText("Salvar").performClick()
        val e = criados.single()
        assertTrue(e.dividir)
        assertEquals(listOf(EntradaPagamento("Nubank", "", "10,00")), e.pagamentos)
    }

    @Test fun parcelarComecaRecolhidoEEntregaONumeroDeParcelas() {
        formulario()
        rolarAte("Abrir as parcelas")
        rolarAte("Sem parcelas: será um lançamento só.")
        regra.onNodeWithText("Sem parcelas: será um lançamento só.").assertExists()
        rolarAte("Abrir as parcelas")
        regra.onNodeWithText("Abrir as parcelas").performClick()
        rolarAte("Na primeira parcela")
        regra.onNodeWithText("Na primeira parcela").performClick()
        regra.onNodeWithText(
            "Número de parcelas, de 2 a 360. Deixe em branco para um lançamento só",
        ).performTextInput("3x")
        regra.onNodeWithText("Salvar").performClick()
        val e = criados.single()
        assertEquals("3", e.parcelas)
        assertTrue(e.diferencaNaPrimeira)
    }

    @Test fun textosDoFormularioNaoFalamDeLeitorDeTela() {
        formulario()
        for (proibido in listOf("leitor de tela", "TalkBack", "ouvir", "Falar ")) {
            regra.onAllNodesWithTextCount(proibido)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(texto: String) {
        onAllNodesWithText(texto, substring = true, ignoreCase = true)
            .assertCountEquals(0)
    }
}
