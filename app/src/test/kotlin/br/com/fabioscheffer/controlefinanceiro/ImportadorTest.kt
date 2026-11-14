package br.com.fabioscheffer.controlefinanceiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportadorTest {
    /** Um preferencias.json do programa Windows, como ele grava (com a posição da tabela e os filtros). */
    private val arquivoDoWindows = """
        {
          "colunas": [
            ["situacao", true], ["origem", true], ["estabelecimento", true], ["descricao", true],
            ["valor", true], ["vencimento", true], ["forma", true], ["conta", true], ["parcela", true],
            ["categoria", true], ["credor", true], ["devedor", true], ["itens", false]
          ],
          "falar_quantidade_filtro": true,
          "falar_total_filtro": true,
          "ordem_tabela": "recentes",
          "tipo_tabela": "",
          "situacao_tabela": "pendente",
          "posicao_tabela": {"lancamento": 1033, "linha": 53, "coluna": 0}
        }
    """.trimIndent()

    @Test fun importaCamposNaOrdemDoWindows() {
        val c = ImportadorWindows.ler(arquivoDoWindows)!!
        assertEquals(
            listOf(
                "situacao", "origem", "estabelecimento", "descricao", "valor", "vencimento", "forma",
                "conta", "parcela", "categoria", "credor", "devedor", "itens",
            ),
            c.campos.map { it.chave },
        )
        assertTrue(c.campos.first { it.chave == "credor" }.visivel)
        assertFalse(c.campos.first { it.chave == "itens" }.visivel)
    }

    @Test fun importaAsOpcoesDeInformarQuantidadeESaldo() {
        val c = ImportadorWindows.ler(arquivoDoWindows)!!
        assertTrue(c.falarQuantidade)
        assertTrue(c.falarTotal)
    }

    @Test fun semAsOpcoesVaiParaOsPadroesDoWindows() {
        val c = ImportadorWindows.ler("""{"colunas": [["valor", true]]}""")!!
        assertTrue(c.falarQuantidade)
        assertFalse(c.falarTotal)
    }

    @Test fun quantidadeDesligadaEFalseNoArquivo() {
        val c = ImportadorWindows.ler("""{"colunas": [["valor", true]], "falar_quantidade_filtro": false}""")!!
        assertFalse(c.falarQuantidade)
    }

    @Test fun naoImportaOFiltroDoMomentoNemAPosicaoDaTabela() {
        // Configuracoes não tem lugar para isso: o resultado só carrega campos e as duas opções.
        val c = ImportadorWindows.ler(arquivoDoWindows)!!
        assertEquals(Campos.TODOS.size, c.campos.size)
    }

    @Test fun campoDesconhecidoEIgnoradoEOQueFaltaEntraDesligadoNoFim() {
        val c = ImportadorWindows.ler("""{"colunas": [["inventada", true], ["valor", true], ["descricao", true]]}""")!!
        assertEquals(listOf("valor", "descricao"), c.campos.take(2).map { it.chave })
        assertEquals(Campos.TODOS.size, c.campos.size)
        assertFalse(c.campos.first { it.chave == "situacao" }.visivel)
    }

    @Test fun marcaDeOrdemDeBytesNoComecoNaoAtrapalha() {
        assertNotNull(ImportadorWindows.ler("﻿" + arquivoDoWindows))
    }

    @Test fun arquivoQueNaoServeDevolveNull() {
        assertNull(ImportadorWindows.ler(""))
        assertNull(ImportadorWindows.ler("isto não é json"))
        assertNull(ImportadorWindows.ler("""{"outra_coisa": 1}"""))
        assertNull(ImportadorWindows.ler("""{"colunas": []}"""))
        assertNull(ImportadorWindows.ler("""{"colunas": [["inventada", true]]}"""))
        assertNull(ImportadorWindows.ler("""{"colunas": [["valor", false], ["descricao", false]]}"""))
        assertNull(ImportadorWindows.ler("""{"colunas": "texto"}"""))
        assertNull(ImportadorWindows.ler("[1, 2, 3]"))
    }
}
