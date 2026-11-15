package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Testa a tela como o leitor de tela a veria: por texto e por toque, sem olhar para a aparência. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelaTest {
    @get:Rule
    val regra = createComposeRule()

    private val chamadas = mutableListOf<List<String>>()
    private val marcados = mutableListOf<Pair<Long, Boolean>>()
    private val alternados = mutableListOf<String>()
    private val importados = mutableListOf<String>()
    private val movidos = mutableListOf<Pair<String, Int>>()

    private fun acoes(resposta: String? = null) = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { busca, a, b, c, d ->
            chamadas += listOf(busca, a, b, c, d)
            resposta
        },
        limparFiltros = {},
        marcarPago = { id, pago -> marcados += id to pago },
        alternarCampo = { alternados += it },
        moverCampo = { chave, delta -> movidos += chave to delta },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = { importados += it },
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
    )

    private fun mostrar(acoes: Acoes, estado: Estado = Estado(etapa = Etapa.CONECTADO, email = "a@b.c")) =
        regra.setContent { TemaApp { Tela(estado, "0.0.0", acoes) } }

    @Test fun botoesDaListaContinuamAoAlcanceComAListaLongaRolada() {
        val muitos = List(200) { Lancamento(it + 1L, "2026-03-01", null, -10.0, null, "Loja $it", null, null, null, null, false) }
        mostrar(acoes(), Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = muitos, lancamentos = muitos))

        regra.onNode(hasScrollAction()).performScrollToIndex(199)
        regra.waitForIdle()

        // Com a lista no fim, as ações continuam lá, sem precisar voltar ao início.
        regra.onNodeWithText("Atualizar").assertExists().assertHeightIsAtLeast(48.dp)
        regra.onNodeWithText("Filtrar e ordenar").assertExists().assertHeightIsAtLeast(48.dp)
    }

    private fun um(pago: Boolean = false, id: Long = 7) = Lancamento(
        id, "2026-03-01", "2026-03-05", -120.0, "Nubank", "Mercado", "Cartão", null, null, "Alimentação", pago,
        itens = listOf(Item(100.0, "Comida", "Arroz"), Item(-5.0, "Comida", "Desconto")),
    )

    /** A tela de teste é pequena: a linha fica abaixo da dobra e só existe depois de rolar até ela. */
    private fun linha(l: Lancamento): SemanticsNodeInteraction {
        val fala = Formatacao.fala(l)
        regra.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(fala))
        return regra.onNodeWithContentDescription(fala)
    }

    private fun estadoCom(vararg l: Lancamento) =
        Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = l.toList(), lancamentos = l.toList())

    @Test fun linhaDaListaTemFraseCompletaPapelDeBotaoEAbreOsDetalhes() {
        val l = um()
        mostrar(acoes(), estadoCom(l))

        val item = linha(l)
        item.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        item.performClick()
        regra.waitForIdle()

        regra.onNodeWithText("Detalhes do lançamento").assertExists()
        regra.onNodeWithText("Tipo: Saída").assertExists()
        rolarAte("Situação: Pendente")
        regra.onNodeWithText("Situação: Pendente").assertExists()
    }

    @Test fun botaoDeMarcarPagoPedeOValorContrarioAoAtual() {
        mostrar(acoes(), estadoCom(um(pago = false)))
        linha(um()).performClick()
        regra.onNodeWithText("Marcar como pago").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(7L to true), marcados)
    }

    @Test fun lancamentoPagoOferecePendente() {
        mostrar(acoes(), estadoCom(um(pago = true)))
        linha(um(pago = true)).performClick()
        regra.onNodeWithText("Marcar como pendente").performClick()
        assertEquals(listOf(7L to false), marcados)
    }

    @Test fun detalheMostraItensEDescontoPorPalavra() {
        mostrar(acoes(), estadoCom(um()))
        linha(um()).performClick()
        rolarAte("Itens")
        regra.onNodeWithText("Item 1: Arroz, Comida, R$ 100,00").assertExists()
        regra.onNodeWithText("Item 2: Desconto, Comida, desconto de R$ 5,00").assertExists()
    }

    @Test fun detalheContinuaAbertoSeOLancamentoSaiDaListaFiltrada() {
        val l = um()
        var estado by mutableStateOf(estadoCom(l))
        regra.setContent { TemaApp { Tela(estado, "0.0.0", acoes()) } }
        linha(l).performClick()
        regra.onNodeWithText("Detalhes do lançamento").assertExists()

        // Como marcar como pago com o filtro "só pendentes": some da lista, continua em "todos".
        regra.runOnIdle { estado = estado.copy(lancamentos = emptyList()) }
        regra.waitForIdle()

        regra.onNodeWithText("Detalhes do lançamento").assertExists()
    }

    private fun abrirConfiguracoes() {
        regra.onNodeWithText("Mais").performClick()
        regra.onNodeWithText("Configurações").performClick()
        regra.onNodeWithText("Informações de cada lançamento").assertExists()
    }

    @Test fun textosVisiveisNaoFalamDeLeitorDeTelaPoisOAppServeATodos() {
        mostrar(acoes())
        val proibidos = listOf("leitor de tela", "TalkBack", "ouvir", "Falar ")
        fun conferir() = proibidos.forEach {
            regra.onAllNodesWithText(it, substring = true, ignoreCase = true).assertCountEquals(0)
        }
        conferir() // Lançamentos
        regra.onNodeWithText("Filtrar e ordenar").performClick()
        conferir() // Filtrar e ordenar
        regra.onNodeWithText("Voltar para os lançamentos").performClick()
        abrirConfiguracoes()
        conferir() // Configurações, no alto
        rolarAte("Informar o saldo em valores")
        conferir() // Configurações, nas opções ao filtrar
    }

    @Test fun configuracoesTemOBotaoDeImportarDoWindowsEOTextoNaoFalaDeLeitor() {
        mostrar(acoes())
        abrirConfiguracoes()
        rolarAte("Importar configurações do Windows")
        regra.onNodeWithText("Importar configurações do Windows").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        regra.onNodeWithText("Escolha o arquivo de configurações copiado do programa do Windows.").assertExists()
        // O seletor de arquivos é do Android e não abre aqui; nada foi importado só por mostrar o botão.
        assertEquals(emptyList<String>(), importados)
    }

    @Test fun configuracoesAbremPelaAbaMaisEVoltam() {
        mostrar(acoes())
        abrirConfiguracoes()
        regra.onNodeWithText("Voltar").performClick()
        regra.waitForIdle()
        regra.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Sair da conta"))
        regra.onNodeWithText("Sair da conta").assertExists()
    }

    @Test fun cadaCampoTemCaixaDeSelecaoEBotoesComONomeDoCampo() {
        mostrar(acoes())
        abrirConfiguracoes()
        rolarAte("Valor (posição 2 de 13)")
        regra.onNodeWithText("Valor (posição 2 de 13)")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        assertEquals(listOf("valor"), alternados)

        rolarAte("Mover Valor para cima")
        regra.onNodeWithText("Mover Valor para cima").assertHeightIsAtLeast(48.dp).performClick()
        regra.onNodeWithText("Mover Valor para baixo").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("valor" to -1, "valor" to 1), movidos)
    }

    @Test fun primeiroCampoNaoSobeEUltimoNaoDesce() {
        mostrar(acoes())
        abrirConfiguracoes()
        rolarAte("Mover Descrição para cima")
        regra.onNodeWithText("Mover Descrição para cima").assertIsNotEnabled()
        regra.onNodeWithText("Mover Descrição para baixo").assertIsEnabled()
        val ultimo = Campos.titulo(Campos.padrao().last().chave)
        rolarAte("Mover $ultimo para baixo")
        regra.onNodeWithText("Mover $ultimo para baixo").assertIsNotEnabled()
    }

    private fun rolarAte(texto: String) {
        regra.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    private fun abrirFiltro() {
        regra.onNodeWithText("Filtrar e ordenar").performClick()
        regra.onNodeWithText("Voltar para os lançamentos").assertExists()
    }

    @Test fun aplicarComDatasValidasFechaATelaDeFiltro() {
        mostrar(acoes())
        abrirFiltro()
        rolarAte("Vencimento a partir de")
        regra.onNodeWithText("Vencimento a partir de").performTextInput("21102026")
        rolarAte("Vencimento até")
        regra.onNodeWithText("Vencimento até").performTextInput("10112026")
        rolarAte("Aplicar filtros")
        regra.onNodeWithText("Aplicar filtros").performClick()
        regra.waitForIdle()

        assertEquals(listOf(listOf("", "", "", "21102026", "10112026")), chamadas)
        // Voltou para a lista: o título da tela de filtro não existe mais.
        regra.onNodeWithText("Voltar para os lançamentos").assertDoesNotExist()
        regra.onNodeWithText("Atualizar").assertExists()
    }

    @Test fun aplicarComErroFicaNaTelaEMostraAMensagem() {
        mostrar(acoes("Vencimento até: data inválida."))
        abrirFiltro()
        rolarAte("Aplicar filtros")
        regra.onNodeWithText("Aplicar filtros").performClick()
        regra.waitForIdle()

        // A mensagem precisa existir mesmo com a pessoa rolada até o botão, lá embaixo.
        regra.onNodeWithText("Vencimento até: data inválida.").assertExists()
        // Continua na tela de filtro: o botão Aplicar está lá e a lista de lançamentos não.
        regra.onNodeWithText("Aplicar filtros").assertExists()
        regra.onNodeWithText("Atualizar").assertDoesNotExist()
    }

    @Test fun voltarFechaATelaDeFiltro() {
        mostrar(acoes())
        abrirFiltro()
        regra.onNodeWithText("Voltar para os lançamentos").performClick()
        regra.waitForIdle()
        regra.onNodeWithText("Atualizar").assertExists()
    }
}
