package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leitura de documentos pela IA: as regras de preenchimento são as do programa Windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeituraIATest {
    @get:Rule
    val regra = createComposeRule()

    private val opcoes = mapOf(
        "banco" to listOf("Next", "Nubank"),
        "estabelecimento" to listOf("Mercado Central"),
        "categoria" to listOf("Alimentação", "Casa"),
        "forma" to listOf("Pix", "Crédito"),
    )

    private fun json(texto: String) = DadosIA.fromJson(JSONObject(texto))

    @Test fun leOsCamposDoServidor() {
        val d = json(
            """{"valor":"12,5","data":"2026-11-10","tipo_transacao":"debito","banco":"Nubank",
            "itens":[{"valor":3,"descricao":"Arroz","categoria_sugerida":"Alimentação"},{"valor":null}],
            "pagamentos":[{"banco":"Next","forma_pagamento":"Pix","valor":"5.5"}],"credor":null}"""
        )
        assertEquals(12.5, d.valor!!, 0.0)
        assertEquals("debito", d.tipoTransacao)
        assertEquals(1, d.itens.size)
        assertEquals("Arroz", d.itens[0].descricao)
        assertEquals(PagamentoIA("Next", "Pix", 5.5), d.pagamentos[0])
        assertNull(d.credor)
    }

    @Test fun preencheOsCamposQueBatemComAsListas() {
        val d = json(
            """{"valor":-45.9,"data":"2026-11-10","data_vencimento":"2026-12-05","tipo_transacao":"debito",
            "banco":"nubank","estabelecimento":"MERCADO CENTRAL","categoria_sugerida":"alimentacao",
            "forma_pagamento":"pix","descricao":"Compra"}"""
        )
        val r = LeituraIA.aplicar(d, EntradaNovo(origem = "01012026"), opcoes)
        val e = r.entrada
        assertEquals("45,90", e.valor)
        assertEquals("10112026", e.origem)
        assertEquals("05122026", e.vencimento)
        assertFalse(e.entrada)
        assertEquals("Nubank", e.banco)
        assertEquals("Mercado Central", e.estabelecimento)
        assertEquals("Alimentação", e.categoria)
        assertEquals("Pix", e.forma)
        assertEquals("Compra", e.descricao)
        assertTrue(r.foraDasListas.isEmpty())
        assertTrue(r.texto.startsWith("Documento lido. Preenchido:"))
    }

    @Test fun textoForaDasListasEntraComoTextoNovoEFicaAvisado() {
        val r = LeituraIA.aplicar(json("""{"estabelecimento":"Padaria Nova","tipo_transacao":"debito"}"""), EntradaNovo(), opcoes)
        assertEquals("Padaria Nova", r.entrada.estabelecimento)
        assertEquals(listOf("estabelecimento" to "Padaria Nova"), r.foraDasListas)
        assertTrue(r.texto.contains("Fora das suas listas"))
        assertTrue(r.texto.contains("Padaria Nova"))
    }

    @Test fun creditoViraEntradaESemCertezaPedeParaConferirOTipo() {
        assertTrue(LeituraIA.aplicar(json("""{"tipo_transacao":"credito"}"""), EntradaNovo(), opcoes).entrada.entrada)
        val sem = LeituraIA.aplicar(json("""{"valor":10}"""), EntradaNovo(entrada = true), opcoes)
        assertTrue(sem.entrada.entrada) // não muda o que já estava
        assertTrue(sem.texto.contains("confira o Tipo"))
    }

    @Test fun doisOuMaisItensViramItensESomamOValor() {
        val d = json(
            """{"valor":99,"categoria_sugerida":"Casa","itens":[
            {"valor":10.5,"descricao":"A","categoria_sugerida":"casa"},
            {"valor":-2,"descricao":"Desc"}]}"""
        )
        val e = LeituraIA.aplicar(d, EntradaNovo(valor = "1"), opcoes).entrada
        assertEquals("", e.valor)
        assertEquals(listOf("10,50", "2,00"), e.itens.map { it.valor })
        assertEquals("Casa", e.itens[0].categoria)
        // A categoria do lançamento fica nos itens.
        assertEquals("", e.categoria)
    }

    @Test fun umItemSoNaoViraLista() {
        val e = LeituraIA.aplicar(json("""{"valor":7,"itens":[{"valor":7,"descricao":"X"}]}"""), EntradaNovo(), opcoes).entrada
        assertTrue(e.itens.isEmpty())
        assertEquals("7,00", e.valor)
    }

    @Test fun doisOuMaisPagamentosDividemOPagamento() {
        val d = json(
            """{"banco":"Next","forma_pagamento":"Pix","pagamentos":[
            {"banco":"Next Bradesco","forma_pagamento":"Pix","valor":20},
            {"banco":"Nubank","forma_pagamento":"Crédito","valor":30}]}"""
        )
        val e = LeituraIA.aplicar(d, EntradaNovo(), opcoes).entrada
        assertTrue(e.dividir)
        assertEquals(
            listOf(EntradaPagamento("Next", "Pix", "20,00"), EntradaPagamento("Nubank", "Crédito", "30,00")),
            e.pagamentos,
        )
        // Banco e forma ficam nas partes.
        assertEquals("", e.banco)
        assertEquals("", e.forma)
    }

    @Test fun bancoNextBradescoValeNext() {
        assertEquals("Next", LeituraIA.opcaoEquivalente("banco", "Next Bradesco", listOf("Itaú", "Next")))
        assertNull(LeituraIA.opcaoEquivalente("categoria", "Next Bradesco", listOf("Next")))
    }

    @Test fun correcaoSoQuandoAPessoaMudouOQueAIASugeriu() {
        val ia = DadosIA(banco = "Nubank", estabelecimento = "Mercado", categoria = "Casa", credor = "Zé")
        val salvo = DadosNovo(
            "2026-11-10", null, -10.0, "nubank", null, "Mercado Central", "Alimentação", null, null, null, false,
        )
        val c = LeituraIA.correcoes(ia, salvo, usouItens = false)
        assertEquals(
            listOf(
                Correcao("estabelecimento", "Mercado", "Mercado Central", "Mercado Central"),
                Correcao("categoria", "Casa", "Alimentação", "Mercado Central"),
            ),
            c,
        )
        // Com itens, a categoria do lançamento não conta.
        assertEquals(1, LeituraIA.correcoes(ia, salvo, usouItens = true).size)
    }

    @Test fun textosNovosIgnoramAcentoEMaiuscula() {
        val e = EntradaNovo(banco = "nubank", estabelecimento = "Padaria Nova", categoria = "Lazer", itens = emptyList())
        assertEquals(
            listOf("estabelecimento" to "Padaria Nova", "categoria" to "Lazer"),
            NovoLancamento.textosNovos(e, opcoes),
        )
        // Com itens, vale a categoria dos itens; com pagamento dividido, as partes.
        val com = EntradaNovo(
            categoria = "Ignorada", itens = listOf(EntradaItem("1", "Viagem", "")),
            dividir = true, pagamentos = listOf(EntradaPagamento("Inter", "Pix", "1")),
        )
        assertEquals(
            listOf("categoria" to "Viagem", "banco" to "Inter"),
            NovoLancamento.textosNovos(com, opcoes),
        )
    }

    private fun comItens() = EntradaNovo(
        itens = listOf(EntradaItem("10,00", "Casa", "Arroz"), EntradaItem("5,50", "", "Feijão")),
        estabelecimento = "Mercado Central",
    )

    @Test fun acrescentarSomaOsItensEConfereComOTotalDaNota() {
        val d = json("""{"valor":30,"itens":[{"valor":10,"descricao":"Leite"},{"valor":4.5,"descricao":"Pão"}]}""")
        val r = LeituraIA.acrescentar(d, comItens(), opcoes)
        assertEquals(4, r.entrada.itens.size)
        assertEquals(listOf("Arroz", "Feijão", "Leite", "Pão"), r.entrada.itens.map { it.descricao })
        assertTrue(r.texto.startsWith("Acrescentados 2 itens. Agora são 4 itens"))
        // 10 + 5,50 + 10 + 4,50 = 30: bate com o total lido na foto.
        assertTrue(r.texto.contains("Bate com o total da nota"))
    }

    @Test fun acrescentarAvisaQuandoOTotalNaoBate() {
        val d = json("""{"valor":99,"itens":[{"valor":1,"descricao":"Sal"}]}""")
        val r = LeituraIA.acrescentar(d, comItens(), opcoes)
        assertTrue(r.texto.startsWith("Acrescentado 1 item."))
        assertTrue(r.texto.contains("diferente da soma"))
    }

    @Test fun acrescentarSemTotalPedeParaConferirComANota() {
        val r = LeituraIA.acrescentar(json("""{"itens":[{"valor":1,"descricao":"Sal"}]}"""), comItens(), opcoes)
        assertTrue(r.texto.contains("Confira a soma com o total impresso"))
    }

    @Test fun acrescentarAvisaDeLinhaQuePodeTerSidoLidaNasDuasFotos() {
        val d = json("""{"itens":[{"valor":5.5,"descricao":"feijão"},{"valor":2,"descricao":"Sal"}]}""")
        val r = LeituraIA.acrescentar(d, comItens(), opcoes)
        assertEquals(4, r.entrada.itens.size) // não remove sozinho
        assertTrue(r.texto.contains("possível repetição entre as fotos"))
        assertTrue(r.texto.contains("feijão"))
    }

    @Test fun acrescentarNaoMexeNoQueJaEstavaPreenchidoMasCompletaOQueFaltava() {
        val d = json(
            """{"estabelecimento":"Outro","credor":"Zé","banco":"Nubank","forma_pagamento":"Pix",
            "itens":[{"valor":1,"descricao":"Sal"}]}"""
        )
        val e = LeituraIA.acrescentar(d, comItens(), opcoes).entrada
        assertEquals("Mercado Central", e.estabelecimento)
        assertEquals("Zé", e.credor)
        assertEquals("Nubank", e.banco)
        assertEquals("Pix", e.forma)
    }

    @Test fun acrescentarComPagamentosDaFotoDoFimDivideOPagamento() {
        val d = json(
            """{"itens":[{"valor":1,"descricao":"Sal"}],"pagamentos":[
            {"banco":"Nubank","forma_pagamento":"Crédito","valor":10},{"banco":"Next","forma_pagamento":"Pix","valor":6.5}]}"""
        )
        val e = LeituraIA.acrescentar(d, comItens(), opcoes).entrada
        assertTrue(e.dividir)
        assertEquals(2, e.pagamentos.size)
    }

    @Test fun acrescentarSemItensNaFotoNaoMudaNada() {
        val r = LeituraIA.acrescentar(json("""{"valor":5}"""), comItens(), opcoes)
        assertEquals(comItens(), r.entrada)
        assertTrue(r.texto.startsWith("Não encontrei itens"))
    }

    @Test fun acrescentarSemItensNoFormularioVaiComoPrimeiraLeitura() {
        val d = json("""{"itens":[{"valor":1,"descricao":"A"},{"valor":2,"descricao":"B"}]}""")
        val r = LeituraIA.acrescentar(d, EntradaNovo(), opcoes)
        assertEquals(2, r.entrada.itens.size)
        assertTrue(r.texto.startsWith("Documento lido."))
    }

    @Test fun botoesDeMaisItensSoAparecemQuandoHaItens() {
        regra.setContent {
            TemaApp { Tela(Estado(etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true), "0.0.0", acoes()) }
        }
        regra.onAllNodesWithText("Fotografar mais itens").assertCountEquals(0)
    }

    @Test fun depoisDeLerItensOsBotoesDeMaisItensAparecem() {
        val dados = DadosIA(itens = listOf(ItemIA(1.0, "A", null), ItemIA(2.0, "B", null)))
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true,
                        leitura = Leitura(1, dados), extracaoIA = dados,
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.waitForIdle()
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("Fotografar mais itens"))
        regra.onNodeWithText("Fotografar mais itens").assertExists()
        regra.onNodeWithText("Escolher mais itens de um arquivo").assertExists()
    }

    @Test fun imagemQueJaCabeNaoEReduzida() {
        assertEquals(1.0, Documentos.escalaParaLimite(4000, 3000), 0.0)
    }

    @Test fun limiteEPorAreaEntaoNotaComprimidaNaoPerdeALetra() {
        // 50 megapixels cai para 12; a razão dos lados se mantém.
        val e = Documentos.escalaParaLimite(5000, 10000)
        assertEquals(12_000_000.0, 5000 * e * 10000 * e, 1000.0)
        // Uma nota 1:6 em 2000 x 12000 (24 MP) fica com altura 8.485, e não com os 2.200 de antes.
        val nota = Documentos.escalaParaLimite(2000, 12000)
        assertTrue(12000 * nota > 8000)
    }

    @Test fun tipoDoArquivo() {
        assertEquals("imagem", Documentos.tipoDe("foto.JPG", null))
        assertEquals("imagem", Documentos.tipoDe("x", "image/png"))
        assertEquals("pdf", Documentos.tipoDe("nota.pdf", "application/octet-stream"))
        assertEquals("xml", Documentos.tipoDe("nfe.xml", "text/xml"))
        assertEquals("texto", Documentos.tipoDe("a.txt", null))
        assertEquals("planilha", Documentos.tipoDe("planilha.xlsx", "application/vnd.ms-excel"))
        assertNull(Documentos.tipoDe("planilha.xls", "application/vnd.ms-excel"))
    }

    // ---- na tela ----

    private val lidos = mutableListOf<String>()
    private var aplicadas = 0
    private val cadastrados = mutableListOf<List<Pair<String, String>>>()

    private fun acoes() = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
        lerTexto = { lidos += it }, leituraAplicada = { aplicadas++ },
        cadastrarTextos = { cadastrados += it },
    )

    @Test fun formularioTemOsDoisBotoesDeLeituraSoAoCriar() {
        regra.setContent {
            TemaApp { Tela(Estado(etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true), "0.0.0", acoes()) }
        }
        regra.onNodeWithText("Fotografar documento").assertExists()
        regra.onNodeWithText("Escolher documento").assertExists()
        regra.onNodeWithText("Ler o texto copiado").assertExists()
    }

    @Test fun arquivoDeFotoApagaAsAnteriores() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val primeira = Documentos.arquivoDeFoto(app)
        assertEquals("fotos", primeira.parentFile!!.name)
        java.io.File(app.cacheDir, "fotos/velha.jpg").writeText("x")
        Documentos.arquivoDeFoto(app)
        assertFalse(java.io.File(app.cacheDir, "fotos/velha.jpg").exists())
    }

    @Test fun naEdicaoNaoHaLeituraDeDocumento() {
        val l = Lancamento(1, "2026-10-25", null, -5.0, null, null, null, null, "X", null, false)
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(etapa = Etapa.CONECTADO, email = "a@b.c", todos = listOf(l), novoAberto = true, editandoId = 1),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.onAllNodesWithText("Escolher documento").assertCountEquals(0)
    }

    @Test fun leituraProntaPreencheOFormularioEFalaOResultado() {
        val dados = DadosIA(valor = 33.0, data = "2026-11-10", tipoTransacao = "debito", estabelecimento = "Padaria Nova")
        regra.setContent {
            TemaApp {
                Tela(
                    Estado(
                        etapa = Etapa.CONECTADO, email = "a@b.c", novoAberto = true, opcoes = opcoes,
                        leitura = Leitura(1, dados), extracaoIA = dados,
                    ),
                    "0.0.0", acoes(),
                )
            }
        }
        regra.waitForIdle()
        assertEquals(1, aplicadas)
        regra.onNodeWithText("Documento lido. Preenchido:", substring = true).assertExists()
        regra.onNodeWithText("Fora das suas listas", substring = true).assertExists()
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("33,00"))
        regra.onNodeWithText("33,00").assertExists()
        // O texto novo do estabelecimento pode ser guardado nas listas.
        regra.onNode(hasScrollAction()).performScrollToNode(hasText("Cadastrar textos novos nas listas (1)"))
        regra.onNodeWithText("Cadastrar textos novos nas listas (1)").performClick()
        assertEquals(listOf(listOf("estabelecimento" to "Padaria Nova")), cadastrados)
    }
}
