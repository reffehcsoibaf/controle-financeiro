package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTest {
    @get:Rule
    val regra = createComposeRule()

    private fun l(id: Long, desc: String = "Luz", valor: Double = -10.0, versao: String? = "v1") =
        Lancamento(id, "2026-10-01", "2026-10-10", valor, "Nubank", null, "Pix", null, desc, "Casa", false, updatedAt = versao)

    private fun dados(desc: String = "Nova", valor: Double = -5.0) =
        DadosNovo("2026-10-02", null, valor, "Nubank", "Pix", null, "Casa", null, null, desc, false)

    @Test fun aFilaPorCimaDoServidor() {
        val servidor = listOf(l(1), l(2, "Água"), l(3, "Gás"))
        val fila = listOf(
            Operacao.Pago("a", "x", 1, true, "v1"),
            Operacao.Excluir("b", "x", 2, "v1"),
            Operacao.Editar("c", "x", 3, "v1", dados("Gás novo", -7.0)),
            Operacao.Criar("d", "x", "u", listOf(dados("A"), dados("B")), listOf(-1, -2)),
        )
        val v = Offline.aplicar(servidor, fila)
        assertEquals(listOf(1L, 3L, -1L, -2L), v.map { it.id })
        assertTrue(v[0].pago)
        assertEquals("Gás novo", v[1].descricao)
        assertEquals("v1", v[1].updatedAt) // a versão do servidor continua
        assertEquals(-7.0, v[1].valor, 0.0)
        assertEquals(listOf("A", "B"), v.drop(2).map { it.descricao })
    }

    @Test fun asOperacoesVoltamDoJson() {
        val ops = listOf(
            Operacao.Pago("a", "Luz", 1, true, "v1"),
            Operacao.Excluir("b", "Água", 2, null),
            Operacao.Editar("c", "Gás", 3, "v3", dados("Gás").copy(itens = listOf(Item(-3.0, "Cat", "Item", "2026-09-01")), pagamentos = listOf(Pagamento("B", "F", -2.0)))),
            Operacao.Criar("d", "2 parcelas", "u", listOf(dados("A"), dados("B")), listOf(-1, -2)),
        )
        for (op in ops) assertEquals(op, Offline.operacaoDeJson(Offline.operacaoParaJson(op)))
    }

    @Test fun acopiaVoltaDoJson() {
        val c = Copia(
            "u1", "2026-11-16T10:30:00",
            listOf(l(1).copy(itens = listOf(Item(-3.0, "Cat", null, "2026-09-01")), vinculadoId = 9)),
            mapOf("banco" to listOf("Nubank", "Itaú")),
            listOf(ContaConfig("Nubank", "cartao_credito", null, 1000.0)),
            Permissoes(ia = false, documentos = true),
            listOf(DocumentoGuardado("d", "n.pdf", "u/n.pdf", "application/pdf", null, "2026-10-01T10:00:00+00:00", 1)),
        )
        assertEquals(c, Offline.copiaDeJson(Offline.copiaParaJson(c)))
    }

    @Test fun oArmazemGuardaELeEApagaTudo() {
        val a = Armazem(ApplicationProvider.getApplicationContext())
        a.apagarTudo()
        assertNull(a.lerCopia())
        val op = Operacao.Pago("a", "Luz", 1, true, "v1")
        a.salvarFila(listOf(op))
        a.salvarConflitos(listOf(Conflito(op, "mudou")))
        assertEquals(listOf(op), a.lerFila())
        assertEquals(listOf(Conflito(op, "mudou")), a.lerConflitos())
        a.apagarTudo()
        assertTrue(a.lerFila().isEmpty())
        assertTrue(a.lerConflitos().isEmpty())
    }

    @Test fun manterAAlteracaoParteDaVersaoNova() {
        val op = Operacao.Pago("a", "x", 1, true, "v1")
        assertEquals("v9", (Offline.comBase(op, "v9") as Operacao.Pago).base)
        val criar = Operacao.Criar("d", "x", "u", listOf(dados()), listOf(-1))
        assertEquals(criar, Offline.comBase(criar, "v9"))
    }

    @Test fun cadaParcelaTemOSeuCodigoSempreOMesmo() {
        assertEquals("u", Offline.codigoDaParcela("u", 0))
        assertEquals(Offline.codigoDaParcela("u", 1), Offline.codigoDaParcela("u", 1))
        assertNotEquals(Offline.codigoDaParcela("u", 1), Offline.codigoDaParcela("u", 2))
    }

    @Test fun dataFalada() {
        assertEquals("16/11/2026 às 10:30", Offline.dataFalada("2026-11-16T10:30:00"))
    }

    @Test fun avisoDeSincronizacaoSoApareceQuandoHaAlgo() {
        assertEquals("", avisoDeSincronizacao(Estado()))
        val e = Estado(
            offline = true, salvoEm = "2026-11-16T10:30:00",
            fila = listOf(Operacao.Pago("a", "x", 1, true, null)),
            conflitos = listOf(Conflito(Operacao.Excluir("b", "y", 2, null), "m")),
        )
        val t = avisoDeSincronizacao(e)
        assertTrue(t.contains("Sem conexão"))
        assertTrue(t.contains("16/11/2026 às 10:30"))
        assertTrue(t.contains("1 alteração aguarda envio"))
        assertTrue(t.contains("Sincronização"))
    }

    private val acoesVazias = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
    )

    @Test fun telaDeSincronizacaoMostraAFilaEOsConflitos() {
        val mantidas = mutableListOf<String>()
        val descartadas = mutableListOf<String>()
        regra.setContent {
            TemaApp {
                TelaSincronizacao(
                    Estado(
                        etapa = Etapa.CONECTADO, offline = true, salvoEm = "2026-11-16T10:30:00",
                        fila = listOf(Operacao.Pago("a", "Luz", 1, true, null)),
                        conflitos = listOf(Conflito(Operacao.Editar("b", "Água", 2, null, dados()), "Mudou em outro lugar.")),
                    ),
                    acoesVazias.let {
                        Acoes(
                            entrar = {}, atualizar = {}, sair = {},
                            definirOrdem = {}, definirTipo = {}, definirSituacao = {},
                            aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
                            marcarPago = { _, _ -> },
                            alternarCampo = {}, moverCampo = { _, _ -> },
                            definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
                            importarConfiguracoes = {},
                            abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
                            manterAlteracao = { mantidas += it },
                            descartarConflito = { descartadas += it },
                        )
                    },
                    aoVoltar = {},
                )
            }
        }
        regra.onNodeWithText("Sem conexão com a internet.").assertIsDisplayed()
        regra.onNodeWithText("Última atualização: 16/11/2026 às 10:30.").assertIsDisplayed()
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("Manter a minha alteração", substring = true))
        regra.onNode(hasText("Manter a minha alteração", substring = true)).performClick()
        assertEquals(listOf("b"), mantidas)
        regra.onNode(hasText("Descartar a minha alteração", substring = true)).performClick()
        assertEquals(listOf("b"), descartadas)
    }

    @Test fun sairAvisaDasAlteracoesNaoEnviadas() {
        var saiu = false
        regra.setContent {
            TemaApp {
                TelaSair(
                    Estado(email = "a@b.c", fila = listOf(Operacao.Pago("a", "x", 1, true, null))),
                    aoCancelar = {}, aoSair = { saiu = true },
                )
            }
        }
        regra.onNodeWithText("Atenção: 1 alteração ainda não foi enviada e será perdida.").assertIsDisplayed()
        assertFalse(saiu)
        regra.onNodeWithText("Sair da conta e apagar os dados deste celular").performClick()
        assertTrue(saiu)
    }
}
