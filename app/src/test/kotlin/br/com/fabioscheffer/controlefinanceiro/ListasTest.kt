package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gerenciar listas e correções aprendidas pela IA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListasTest {
    @get:Rule
    val regra = createComposeRule()

    private val adicionadas = mutableListOf<Pair<String, String>>()
    private val renomeadas = mutableListOf<Triple<String, String, String>>()
    private val excluidas = mutableListOf<Pair<String, String>>()
    private val correcoesEditadas = mutableListOf<List<Any>>()
    private val correcoesExcluidas = mutableListOf<Long>()
    private var apagouTodas = false
    private var carregou = 0

    private fun rolarAte(texto: String) {
        regra.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
        adicionarOpcao = { c, t -> adicionadas += c to t; null },
        renomearOpcao = { c, a, n -> renomeadas += Triple(c, a, n); if (n == "Repetido") "Já existe." else null },
        excluirOpcao = { c, v -> excluidas += c to v },
        carregarCorrecoes = { carregou++ },
        editarCorrecao = { id, e, c, x -> correcoesEditadas += listOf(id, e, c, x); null },
        excluirCorrecao = { correcoesExcluidas += it },
        apagarCorrecoes = { apagouTodas = true },
    )

    private val opcoes = mapOf(
        "banco" to listOf("Nubank", "Itaú"),
        "categoria" to listOf("Mercado", "Luz"),
    )

    private val correcoes = listOf(
        Correcao("estabelecimento", "SUPERMERC XYZ", "Mercado Central", "nota fiscal", 7),
        Correcao("categoria", "Alimentacao", "Mercado", null, 8),
    )

    private fun abrirMais(permissoes: Permissoes = Permissoes()) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", permissoes = permissoes,
                        opcoes = opcoes, correcoes = correcoes,
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.onNodeWithText("Configurações").performClick()
    }

    @Test fun listasMostramAQuantidadeDeOpcoes() {
        abrirMais()
        regra.onNodeWithText("Gerenciar listas").performClick()
        regra.onNodeWithText("Banco ou cartão, 2 opções").assertIsDisplayed()
    }

    @Test fun adicionarOpcaoEnviaOTextoDigitado() {
        abrirMais()
        regra.onNodeWithText("Gerenciar listas").performClick()
        regra.onNodeWithText("Banco ou cartão, 2 opções").performClick()
        regra.onNodeWithText("Nova opção").performTextInput("Inter")
        regra.onNodeWithText("Adicionar à lista").performClick()
        assertEquals(listOf("banco" to "Inter"), adicionadas)
    }

    @Test fun renomearEnviaOsTresValoresEMostraOErro() {
        abrirMais()
        regra.onNodeWithText("Gerenciar listas").performClick()
        regra.onNodeWithText("Banco ou cartão, 2 opções").performClick()
        regra.onNodeWithText("Nubank").performClick()
        regra.onNodeWithText("Novo nome").performTextClearance()
        regra.onNodeWithText("Novo nome").performTextInput("Repetido")
        regra.onNode(hasText("Renomear") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(listOf(Triple("banco", "Nubank", "Repetido")), renomeadas)
        regra.onNodeWithText("Já existe.").assertIsDisplayed()
    }

    @Test fun excluirOpcaoPedeConfirmacaoEAvisaDoBanco() {
        abrirMais()
        regra.onNodeWithText("Gerenciar listas").performClick()
        regra.onNodeWithText("Banco ou cartão, 2 opções").performClick()
        regra.onNodeWithText("Itaú").performClick()
        regra.onNodeWithText("Excluir").performClick()
        assertTrue(excluidas.isEmpty())
        regra.onNodeWithText("A configuração de saldo e limite desse banco ou cartão também será removida.")
            .assertIsDisplayed()
        regra.onNodeWithText("Cancelar").performClick()
        assertTrue(excluidas.isEmpty())
        regra.onNodeWithText("Excluir").performClick()
        rolarAte("Excluir")
        regra.onNode(hasText("Excluir") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(listOf("banco" to "Itaú"), excluidas)
    }

    @Test fun correcoesSoAparecemComIA() {
        abrirMais(Permissoes(ia = false))
        rolarAte("Gerenciar listas")
        regra.onNodeWithText("Correções aprendidas pela IA").assertDoesNotExist()
    }

    @Test fun correcoesCarregamEFalamUmaFraseCadaUma() {
        abrirMais()
        rolarAte("Correções aprendidas pela IA")
        regra.onNodeWithText("Correções aprendidas pela IA").performClick()
        regra.waitForIdle()
        assertEquals(1, carregou)
        regra.onNodeWithText("2 correções.").assertIsDisplayed()
        regra.onNode(
            hasContentDescription(
                "Estabelecimento: onde a IA lia SUPERMERC XYZ, o certo é Mercado Central. Contexto: nota fiscal.",
            ),
        ).assertIsDisplayed()
    }

    @Test fun editarCorrecaoEnviaOsCampos() {
        abrirMais()
        rolarAte("Correções aprendidas pela IA")
        regra.onNodeWithText("Correções aprendidas pela IA").performClick()
        regra.onNode(hasContentDescription("Categoria: onde a IA lia Alimentacao", substring = true)).performClick()
        regra.onNodeWithText("Valor correto").performTextClearance()
        regra.onNodeWithText("Valor correto").performTextInput("Supermercado")
        regra.onNodeWithText("Salvar correção").performClick()
        assertEquals(listOf(listOf<Any>(8L, "Alimentacao", "Supermercado", "")), correcoesEditadas)
    }

    @Test fun apagarTodasPedeConfirmacao() {
        abrirMais()
        rolarAte("Correções aprendidas pela IA")
        regra.onNodeWithText("Correções aprendidas pela IA").performClick()
        regra.onNodeWithText("Apagar todas").performClick()
        assertTrue(!apagouTodas)
        regra.onNodeWithText("Cancelar").performClick()
        regra.onNodeWithText("Apagar todas").performClick()
        rolarAte("Apagar todas")
        regra.onNode(hasText("Apagar todas") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertTrue(apagouTodas)
    }
}
