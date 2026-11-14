package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Excluir lançamento: a confirmação em tela própria e o que ela entrega. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExclusaoTest {
    @get:Rule
    val regra = createComposeRule()

    private val a = Lancamento(
        id = 1, dataOrigem = "2026-10-25", dataVencimento = null, valor = -50.0, banco = "Nubank",
        estabelecimento = "Loja", forma = null, parcela = null, descricao = "Cartão azul", categoria = null,
        pago = false, updatedAt = "v1", vinculadoId = 2,
        itens = listOf(Item(50.0, null, "Coisa")),
    )
    private val b = a.copy(id = 2, valor = 50.0, descricao = "Entrada na fatura", vinculadoId = 1, itens = emptyList())

    private val excluidos = mutableListOf<Pair<Long, Boolean>>()

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
        excluir = { id, junto -> excluidos += id to junto },
    )

    private fun abrirDetalhes(vararg l: Lancamento, alvo: Lancamento = l.first()) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = l.toList(), lancamentos = l.toList()),
                    "0.0.0", acoes(),
                )
            }
        }
        val fala = Formatacao.fala(alvo)
        regra.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(fala))
        regra.onNodeWithContentDescription(fala).performClick()
        regra.waitForIdle()
    }

    @Test fun vinculoVemDoServidor() {
        val com = Lancamento.fromJson(JSONObject("""{"id":1,"valor":-5,"pago":false,"lancamento_vinculado_id":9}"""))
        val sem = Lancamento.fromJson(JSONObject("""{"id":1,"valor":-5,"pago":false,"lancamento_vinculado_id":null}"""))
        assertEquals(9L, com.vinculadoId)
        assertNull(sem.vinculadoId)
    }

    @Test fun confirmacaoComecaEmCancelarEDizOQueSeraApagado() {
        abrirDetalhes(a.copy(vinculadoId = null))
        regra.onNodeWithText("Excluir lançamento").performClick()
        regra.waitForIdle()
        regra.onNodeWithText("Cancelar").assertIsFocused()
        regra.onNodeWithText("Você vai excluir", substring = true).assertExists()
        regra.onNodeWithText("O item dele também será apagado.", substring = true).assertExists()
        regra.onNodeWithText("Não dá para desfazer.").assertExists()
        regra.onAllNodesWithText("Excluir os dois").assertCountEquals(0)
        assertEquals(emptyList<Pair<Long, Boolean>>(), excluidos)
    }

    @Test fun cancelarVoltaAosDetalhesSemExcluir() {
        abrirDetalhes(a.copy(vinculadoId = null))
        regra.onNodeWithText("Excluir lançamento").performClick()
        regra.onNodeWithText("Cancelar").performClick()
        regra.onNodeWithText("Detalhes do lançamento").assertExists()
        assertEquals(emptyList<Pair<Long, Boolean>>(), excluidos)
    }

    @Test fun excluirEntregaOIdSemVinculado() {
        abrirDetalhes(a.copy(vinculadoId = null))
        regra.onNodeWithText("Excluir lançamento").performClick()
        regra.onNodeWithText("Excluir").performClick()
        assertEquals(listOf(1L to false), excluidos)
    }

    @Test fun comVinculadoOferecePorOsDoisOuSoEste() {
        abrirDetalhes(a, b)
        regra.onNodeWithText("Excluir lançamento").performClick()
        regra.onNodeWithText("vinculado a outro lançamento", substring = true).assertExists()
        regra.onNodeWithText("Excluir só este").performClick()
        regra.onNodeWithText("Excluir os dois").assertExists()
        assertEquals(listOf(1L to false), excluidos)
    }

    @Test fun excluirOsDoisEntregaAOpcaoCerta() {
        abrirDetalhes(a, b)
        regra.onNodeWithText("Excluir lançamento").performClick()
        regra.onNodeWithText("Excluir os dois").performClick()
        assertEquals(listOf(1L to true), excluidos)
    }
}
