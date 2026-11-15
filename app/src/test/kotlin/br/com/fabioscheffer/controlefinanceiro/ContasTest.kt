package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saldos e limites: as contas são as do programa Windows (regras.py, situacao_contas). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContasTest {
    @get:Rule
    val regra = createComposeRule()

    private fun l(id: Long, valor: Double, banco: String?, pago: Boolean, pagamentos: List<Pagamento> = emptyList()) =
        Lancamento(id, "2026-10-01", null, valor, banco, null, null, null, "x", null, pago, pagamentos = pagamentos)

    private val contas = listOf(
        ContaConfig("Itaú", CONTA_CORRENTE, saldoInicial = 1000.0),
        ContaConfig("Visa", CARTAO_CREDITO, limiteTotal = 500.0),
    )

    @Test fun contaCorrenteSoContaOQueEstaPago() {
        val s = Contas.situacoes(
            contas, listOf(l(1, -100.0, "Itaú", true), l(2, -300.0, "Itaú", false), l(3, 50.0, "Itaú", true)),
        ).first { it.nome == "Itaú" }
        assertEquals(950.0, s.saldo, 0.0)
        assertFalse(s.alerta)
        assertTrue(s.detalhe.startsWith("Saldo atual: R$ 950,00"))
    }

    @Test fun cartaoContaTambemOPendenteENegativoFicaEmAlerta() {
        val s = Contas.situacoes(
            contas, listOf(l(1, -400.0, "Visa", false), l(2, -200.0, "Visa", true)),
        ).first { it.nome == "Visa" }
        assertEquals(600.0, s.limiteUsado, 0.0)
        assertEquals(-100.0, s.limiteRestante, 0.0)
        assertTrue(s.alerta)
        assertTrue(s.fala.contains("Atenção: passou do limite"))
        assertTrue(s.detalhe.contains("menos R$ 100,00"))
    }

    @Test fun entradaNoCartaoRecuperaOLimite() {
        val s = Contas.situacoes(contas, listOf(l(1, -400.0, "Visa", false), l(2, 400.0, "Visa", true)))
            .first { it.nome == "Visa" }
        assertEquals(0.0, s.limiteUsado, 0.0)
        assertEquals(500.0, s.limiteRestante, 0.0)
    }

    @Test fun pagamentoDivididoContaSoAParteDeCadaConta() {
        val dividido = l(1, -100.0, null, true, listOf(Pagamento("Itaú", "Pix", 60.0), Pagamento("Visa", "Crédito", 40.0)))
        val s = Contas.situacoes(contas, listOf(dividido))
        assertEquals(940.0, s.first { it.nome == "Itaú" }.saldo, 0.0)
        assertEquals(40.0, s.first { it.nome == "Visa" }.limiteUsado, 0.0)
    }

    @Test fun bancoDasListasSemConfiguracaoApareceComoNaoConfigurado() {
        val s = Contas.situacoes(contas, emptyList(), listOf("Itaú", "Inter", "Visa"))
        assertEquals(listOf("Inter", "Itaú", "Visa"), s.map { it.nome })
        val inter = s.first { it.nome == "Inter" }
        assertEquals("", inter.tipo)
        assertEquals("Não configurada", inter.nomeDoTipo)
    }

    @Test fun cartoesParaQuitarSaoOsCartoesSemOBancoQuePaga() {
        val c = contas + ContaConfig("Master", CARTAO_CREDITO, limiteTotal = 1.0)
        assertEquals(listOf("Master", "Visa"), Contas.cartoesParaQuitar(c, "Itaú"))
        assertEquals(listOf("Master"), Contas.cartoesParaQuitar(c, " Visa "))
    }

    // ---- telas ----

    private val salvas = mutableListOf<ContaConfig>()
    private val removidas = mutableListOf<String>()
    private val vistas = mutableListOf<String>()
    private val criados = mutableListOf<EntradaNovo>()

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = { criados += it },
        salvarConta = { salvas += it }, removerConta = { removidas += it },
        verLancamentosDaConta = { vistas += it },
    )

    private fun abrirContas() {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", contas = contas,
                        opcoes = mapOf("banco" to listOf("Itaú", "Visa", "Inter")),
                        todos = listOf(l(1, -600.0, "Visa", false)),
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.onNodeWithText("Mais").performClick()
        regra.onNodeWithText("Saldos e limites").performClick()
    }

    /** A linha de uma conta é lida como uma frase só: procura por ela, e não pelo texto visual. */
    private fun abrirConta(nome: String) {
        val frase = hasContentDescription(nome, substring = true)
        regra.onNode(hasScrollAction()).performScrollToNode(frase)
        regra.onNode(frase).performClick()
    }

    private fun rolarAte(texto: String) {
        regra.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    @Test fun listaMostraAContaEmAlertaComTexto() {
        abrirContas()
        regra.onNode(hasContentDescription("Atenção: passou do limite", substring = true)).assertExists()
    }

    @Test fun configurarUmBancoSalvaOTipoEOValor() {
        abrirContas()
        abrirConta("Inter")
        regra.onNodeWithText("Configurar").performClick()
        regra.onNodeWithText("Conta corrente").performClick()
        regra.onNodeWithText("Saldo inicial").performTextInput("1.500,50")
        regra.onNodeWithText("Salvar").performClick()
        assertEquals(listOf(ContaConfig("Inter", CONTA_CORRENTE, saldoInicial = 1500.5)), salvas)
    }

    @Test fun valorInvalidoNaoSalvaEFalaOErro() {
        abrirContas()
        abrirConta("Inter")
        regra.onNodeWithText("Configurar").performClick()
        regra.onNodeWithText("Limite total").performTextInput("abc")
        regra.onNodeWithText("Salvar").performClick()
        assertTrue(salvas.isEmpty())
        regra.onNodeWithText("Informe o limite total em números.").assertExists()
    }

    @Test fun removerPedeConfirmacaoAntesDeRemover() {
        abrirContas()
        abrirConta("Visa")
        regra.onNodeWithText("Remover configuração").performClick()
        regra.onNodeWithText("Remover a configuração de Visa?", substring = true).assertExists()
        assertTrue(removidas.isEmpty())
        regra.onNodeWithText("Remover").performClick()
        assertEquals(listOf("Visa"), removidas)
    }

    private fun formulario(vararg cartoes: ContaConfig) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true,
                        contas = cartoes.toList(), opcoes = mapOf("categoria" to listOf("Cartão de crédito", "Casa")),
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
    }

    @Test fun categoriaDeFaturaOferecePagarUmCartaoJaEscolhidoQuandoHaUmSo() {
        formulario(ContaConfig("Visa", CARTAO_CREDITO, limiteTotal = 500.0))
        rolarAte("Categoria")
        regra.onNodeWithText("Categoria").performTextInput("Cartão de crédito")
        rolarAte("Cartão a quitar")
        regra.onNodeWithText("Não vincular").assertExists()
        rolarAte("Valor")
        regra.onNodeWithText("Valor").performTextInput("300")
        regra.onNodeWithText("Salvar").performClick()
        assertEquals("Visa", criados.single().cartaoAQuitar)
    }

    @Test fun naoVincularEnviaOCartaoVazio() {
        formulario(ContaConfig("Visa", CARTAO_CREDITO, limiteTotal = 500.0))
        rolarAte("Categoria")
        regra.onNodeWithText("Categoria").performTextInput("Cartão de crédito")
        rolarAte("Não vincular")
        regra.onNodeWithText("Não vincular").performClick()
        regra.onNodeWithText("Salvar").performClick()
        assertEquals("", criados.single().cartaoAQuitar)
    }

    @Test fun semCartaoConfiguradoOFormularioExplicaOQueFazer() {
        formulario()
        rolarAte("Categoria")
        regra.onNodeWithText("Categoria").performTextInput("Cartão de crédito")
        rolarAte("Para quitar um cartão, configure o cartão em Mais, Saldos e limites.")
        regra.onNodeWithText("Para quitar um cartão, configure o cartão em Mais, Saldos e limites.").assertExists()
    }

    @Test fun verLancamentosDaContaVoltaParaALista() {
        abrirContas()
        abrirConta("Visa")
        regra.onNodeWithText("Ver lançamentos desta conta").performClick()
        assertEquals(listOf("Visa"), vistas)
        regra.onNodeWithText("Atualizar").assertExists()
    }
}
