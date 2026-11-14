package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Editar lançamento: o formulário preenchido com o que está gravado, e o que volta dele. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdicaoTest {
    @get:Rule
    val regra = createComposeRule()

    private val simples = Lancamento(
        id = 7, dataOrigem = "2026-10-25", dataVencimento = "2026-11-10", valor = -1234.5,
        banco = "Nubank", estabelecimento = "Loja", forma = "Pix", parcela = "2/3",
        descricao = "Geladeira", categoria = "Casa", pago = true, credor = "João", devedor = null,
        updatedAt = "2026-11-13T10:00:00.123456+00:00",
    )

    @Test fun entradaDeUmaSaidaSimples() {
        val e = NovoLancamento.entradaDe(simples)
        assertFalse(e.entrada)
        assertEquals("1.234,50", e.valor)
        assertEquals("25102026", e.origem)
        assertEquals("10112026", e.vencimento)
        assertEquals("Nubank", e.banco)
        assertEquals("João", e.credor)
        assertEquals("", e.devedor)
        assertTrue(e.pago)
        assertFalse(e.dividir)
        assertTrue(e.itens.isEmpty())
    }

    @Test fun entradaDeUmaEntradaTemOTipoCerto() {
        assertTrue(NovoLancamento.entradaDe(simples.copy(valor = 50.0)).entrada)
    }

    @Test fun ida_e_volta_nao_muda_o_lancamento() {
        val ok = NovoLancamento.validar(NovoLancamento.entradaDe(simples)) as ResultadoNovo.Ok
        val d = ok.dados
        assertEquals(-1234.5, d.valor, 0.0)
        assertEquals("2026-10-25", d.dataOrigem)
        assertEquals("2026-11-10", d.dataVencimento)
        assertEquals("Geladeira", d.descricao)
        assertNull(d.devedor)
        assertTrue(d.pago)
    }

    @Test fun itensGuardamADataOriginalNaIdaEVolta() {
        val l = simples.copy(
            valor = -15.0, descricao = null, categoria = null,
            itens = listOf(Item(10.0, "A", "Arroz", "2026-03-05"), Item(5.0, null, "Feijão")),
        )
        val e = NovoLancamento.entradaDe(l)
        assertEquals("", e.valor)
        assertEquals("2026-03-05", e.itens[0].dataOriginal)
        val d = (NovoLancamento.validar(e) as ResultadoNovo.Ok).dados
        assertEquals(-15.0, d.valor, 0.0)
        assertEquals(listOf(Item(10.0, "A", "Arroz", "2026-03-05"), Item(5.0, null, "Feijão", null)), d.itens)
    }

    @Test fun itensAntigosNegativosViramPositivosNoFormulario() {
        val l = simples.copy(
            valor = -15.0, itens = listOf(Item(-10.0, null, "A"), Item(-5.0, null, "B")),
        )
        val e = NovoLancamento.entradaDe(l)
        assertEquals(listOf("10,00", "5,00"), e.itens.map { it.valor })
        assertEquals(-15.0, (NovoLancamento.validar(e) as ResultadoNovo.Ok).dados.valor, 0.0)
    }

    @Test fun pagamentoDivididoVoltaMarcado() {
        val l = simples.copy(pagamentos = listOf(Pagamento("Nubank", "Pix", 1000.0), Pagamento("Itaú", null, 234.5)))
        val e = NovoLancamento.entradaDe(l)
        assertTrue(e.dividir)
        assertEquals(EntradaPagamento("Itaú", "", "234,50"), e.pagamentos[1])
        assertTrue(NovoLancamento.validar(e) is ResultadoNovo.Ok)
    }

    // ---- na tela ----

    private val criados = mutableListOf<EntradaNovo>()
    private var abertos = mutableListOf<Long>()

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = { criados += it },
        abrirEdicao = { abertos += it },
    )

    @Test fun detalheTemOBotaoEditarQueAbreAEdicaoDoLancamento() {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = listOf(simples), lancamentos = listOf(simples)),
                    "0.0.0", acoes(),
                )
            }
        }
        val fala = Formatacao.fala(simples)
        regra.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(fala))
        regra.onNodeWithContentDescription(fala).performClick()
        regra.waitForIdle()
        regra.onNodeWithText("Editar lançamento").performClick()
        assertEquals(listOf(7L), abertos)
    }

    @Test fun formularioDeEdicaoVemPreenchidoSemAParteDeParcelar() {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", todos = listOf(simples),
                        novoAberto = true, editandoId = 7,
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.onNodeWithText("Editar lançamento").assertExists()
        regra.onAllNodesWithText("Parcelar").assertCountEquals(0)
        regra.onNodeWithText("Salvar").performClick()
        val e = criados.single()
        assertEquals("1.234,50", e.valor)
        assertEquals("Geladeira", e.descricao)
        assertEquals("25102026", e.origem)
        assertTrue(e.pago)
    }
}
