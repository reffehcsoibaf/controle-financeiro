package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** Documentos guardados: as regras da lista são as do programa Windows (gerenciar.py, Documentos). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentosTest {
    @get:Rule
    val regra = createComposeRule()

    private val brasil = ZoneId.of("America/Sao_Paulo")

    private fun doc(id: String, nome: String, criado: String, lanc: Long? = null, tamanho: Long? = 2048) =
        DocumentoGuardado(id, nome, "u/$id", "application/pdf", tamanho, criado, lanc)

    private val docs = listOf(
        doc("a", "Nota do Mercado.pdf", "2026-10-05T12:00:00+00:00"),
        doc("b", "Recibo Açougue.jpg", "2026-10-20T01:30:00+00:00", lanc = 1),
        doc("c", "Boleto.pdf", "2026-11-02T15:00:00+00:00", lanc = 2),
    )

    // ---- regras ----

    @Test fun diaLocalNaoEODiaDoServidor() {
        // 01:30 UTC de 20/10 ainda é noite de 19/10 em Brasília.
        assertEquals("2026-10-19", Guardados.dataLocal("2026-10-20T01:30:00+00:00", brasil))
        assertEquals("2026-10-05", Guardados.dataLocal("2026-10-05T12:00:00.123456+00:00", brasil))
        // Sem fuso, vale o começo do texto.
        assertEquals("2026-10-05", Guardados.dataLocal("2026-10-05", brasil))
    }

    @Test fun buscaIgnoraAcentoEMaiuscula() {
        assertEquals(listOf("b"), Guardados.filtrar(docs, "acougue", "", "", zona = brasil).map { it.id })
        assertEquals(listOf("a"), Guardados.filtrar(docs, "  MERCADO ", "", "", zona = brasil).map { it.id })
    }

    @Test fun datasUsamODiaLocalEOsMaisNovosVemPrimeiro() {
        // O recibo foi guardado às 22h30 de 19/10 no Brasil.
        assertEquals(listOf("b"), Guardados.filtrar(docs, "", "2026-10-19", "2026-10-19", zona = brasil).map { it.id })
        assertEquals(listOf("c", "b"), Guardados.filtrar(docs, "", "2026-10-10", "", zona = brasil).map { it.id })
        assertEquals(listOf("a"), Guardados.filtrar(docs, "", "", "2026-10-10", zona = brasil).map { it.id })
    }

    @Test fun podeFiltrarPeloLancamento() {
        assertEquals(listOf("c"), Guardados.filtrar(docs, "", "", "", lancamentoId = 2, zona = brasil).map { it.id })
    }

    @Test fun tamanhoComoNoWindows() {
        assertEquals("—", Guardados.tamanhoFalado(null))
        assertEquals("512 B", Guardados.tamanhoFalado(512))
        assertEquals("340 KB", Guardados.tamanhoFalado(340 * 1024L))
        assertEquals("1,5 MB", Guardados.tamanhoFalado((1.5 * 1024 * 1024).toLong()))
    }

    @Test fun caminhoNovoSoTemCaracteresSeguros() {
        assertEquals("u1/99-Nota_do_A_ougue__1_.pdf", Guardados.caminhoNovo("u1", "Nota do Açougue (1).pdf", 99))
        assertTrue(Guardados.caminhoNovo("u1", "a b/ç.pdf", 5).matches(Regex("u1/5-[a-zA-Z0-9._-]+")))
    }

    // ---- telas ----

    private val vinculos = mutableListOf<Pair<String, Long?>>()
    private val excluidos = mutableListOf<String>()
    private val analisados = mutableListOf<String>()
    private val abertos = mutableListOf<String>()

    private fun lancamento(id: Long, desc: String) =
        Lancamento(id, "2026-10-01", null, -10.0, null, null, null, null, desc, null, true)

    private val lancamentos get() = listOf(lancamento(1, "Carne"), lancamento(2, "Luz"), lancamento(3, "Água"))

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
        vincularDocumento = { id, l -> vinculos += id to l },
        excluirDocumento = { excluidos += it },
        analisarDocumento = { analisados += it },
        abrirDocumento = { abertos += it },
    )

    private fun abrir(permissoes: Permissoes = Permissoes()) {
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", permissoes = permissoes, documentos = docs,
                        todos = lancamentos, lancamentos = lancamentos,
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
    }

    private fun abrirLista() {
        abrir()
        regra.onNodeWithText("Configurações").performClick()
        regra.onNodeWithText("Documentos").performClick()
    }

    private fun abrirDoc(nome: String) {
        val frase = hasContentDescription(nome, substring = true)
        regra.onNode(hasScrollAction()).performScrollToNode(frase)
        regra.onNode(frase).performClick()
    }

    @Test fun semPermissaoOBotaoNaoAparece() {
        abrir(Permissoes(ia = true, documentos = false))
        regra.onNodeWithText("Configurações").performClick()
        regra.onNodeWithText("Documentos").assertDoesNotExist()
    }

    @Test fun listaFalaCadaDocumentoComoUmaFraseSo() {
        abrirLista()
        rolarAte("3 documentos.")
        regra.onNodeWithText("3 documentos.").assertExists()
        regra.onNode(hasContentDescription("Recibo Açougue.jpg, guardado em 19 de outubro de 2026", substring = true)).assertExists()
    }

    @Test fun buscaPeloNomeEscondeOsOutros() {
        abrirLista()
        regra.onNodeWithText("Buscar pelo nome").performTextInput("boleto")
        rolarAte("1 documento.")
        regra.onNodeWithText("1 documento.").assertExists()
    }

    @Test fun dataInvalidaEFalada() {
        abrirLista()
        regra.onNodeWithText("Guardado de").performTextInput("99")
        regra.onNodeWithText("Guardado de: data inválida", substring = true).assertExists()
    }

    @Test fun documentoVinculadoOfereceIrAoLancamentoEDesvincular() {
        abrirLista()
        abrirDoc("Boleto.pdf")
        regra.onNodeWithText("Ir ao lançamento").assertExists()
        regra.onNodeWithText("Desvincular").performClick()
        assertEquals(listOf("c" to null), vinculos)
    }

    @Test fun abrirEAnalisarChamamAsAcoes() {
        abrirLista()
        abrirDoc("Nota do Mercado")
        regra.onNodeWithText("Abrir").performClick()
        regra.onNodeWithText("Analisar com IA").performClick()
        assertEquals(listOf("a"), abertos)
        assertEquals(listOf("a"), analisados)
    }

    @Test fun excluirPedeConfirmacaoAntes() {
        abrirLista()
        abrirDoc("Nota do Mercado")
        regra.onNode(hasText("Excluir") and androidx.compose.ui.test.hasClickAction()).performClick()
        regra.onNodeWithText("Excluir o documento Nota do Mercado.pdf?", substring = true).assertExists()
        assertTrue(excluidos.isEmpty())
        regra.onNode(hasText("Excluir") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(listOf("a"), excluidos)
    }

    private fun abrirLancamento(nome: String) {
        val frase = hasContentDescription(nome, substring = true)
        regra.onNode(hasScrollAction()).performScrollToNode(frase)
        regra.onNode(frase).performClick()
    }

    @Test fun detalhesDoLancamentoOferecemVerOuVincular() {
        abrir()
        abrirLancamento("Carne")
        regra.onNodeWithText("Ver documento vinculado").assertExists()
    }

    @Test fun lancamentoSemDocumentoOfereceVincularEOUsaComoAlvo() {
        abrir()
        abrirLancamento("Água")
        regra.onNodeWithText("Vincular um documento").performClick()
        rolarAte("Nenhum documento.")
        regra.onNodeWithText("Nenhum documento.").assertExists()
        rolarAte("Ver todos os documentos")
        regra.onNodeWithText("Ver todos os documentos").performClick()
        abrirDoc("Nota do Mercado")
        regra.onNodeWithText("Vincular ao lançamento", substring = true).performClick()
        assertEquals(listOf("a" to 3L), vinculos)
    }
}
