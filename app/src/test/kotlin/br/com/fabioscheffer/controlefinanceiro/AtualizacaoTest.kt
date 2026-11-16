package br.com.fabioscheffer.controlefinanceiro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtualizacaoTest {
    @get:Rule
    val regra = createComposeRule()

    @Test fun soTagsDoAndroidContam() {
        assertEquals("0.20.0", Versoes.doTag("android-v0.20.0"))
        assertNull(Versoes.doTag("2026.11.07.0"))
        assertNull(Versoes.doTag("android-vX"))
    }

    @Test fun comparaNumeroANumero() {
        assertTrue(Versoes.comparar("0.20.0", "0.19.0") > 0)
        assertTrue(Versoes.comparar("0.9.0", "0.10.0") < 0)
        assertEquals(0, Versoes.comparar("1.0", "1.0.0"))
        assertTrue(Versoes.comparar("1.0.1", "1.0") > 0)
    }

    private fun release(tag: String, apk: Boolean = true, rascunho: Boolean = false, digest: String = "") =
        """{"tag_name":"$tag","draft":$rascunho,"prerelease":false,"body":"## Novidades\n- A\n- B","assets":[""" +
            (if (apk) """{"name":"app.apk","browser_download_url":"https://x/app-$tag.apk","size":1234,"digest":"$digest"}""" else "") +
            "]}"

    @Test fun escolheAMaisNovaDoAndroidIgnorandoOResto() {
        val json = "[" + listOf(
            release("2026.11.07.0"), // do Windows
            release("android-v0.19.0"),
            release("android-v0.21.0"),
            release("android-v0.22.0", rascunho = true),
            release("android-v0.23.0", apk = false),
        ).joinToString(",") + "]"
        val v = Versoes.maisNova(json)!!
        assertEquals("0.21.0", v.versao)
        assertEquals("https://x/app-android-v0.21.0.apk", v.urlApk)
        assertEquals(1234L, v.tamanho)
        assertTrue(v.notas.contains("Novidades"))
    }

    @Test fun guardaOSha256QuandoOGitHubInforma() {
        val v = Versoes.maisNova("[" + release("android-v0.21.0", digest = "sha256:abc123") + "]")!!
        assertEquals("abc123", v.sha256)
        assertEquals("", Versoes.maisNova("[" + release("android-v0.21.0") + "]")!!.sha256)
    }

    @Test fun semReleaseDoAndroidNaoHaNada() {
        assertNull(Versoes.maisNova("[" + release("2026.11.07.0") + "]"))
        assertNull(Versoes.maisNova("[]"))
    }

    @Test fun tamanhoEmMegabytes() {
        assertEquals("12,5 MB", tamanhoEmMb((12.5 * 1024 * 1024).toLong()))
    }

    private fun acoes(verificar: () -> Unit = {}, baixar: () -> Unit = {}) = Acoes(
        entrar = {}, atualizar = {}, sair = {},
        definirOrdem = {}, definirTipo = {}, definirSituacao = {},
        aplicarBusca = { _, _, _, _, _ -> null }, limparFiltros = {},
        marcarPago = { _, _ -> },
        alternarCampo = {}, moverCampo = { _, _ -> },
        definirFalarQuantidade = {}, definirFalarTotal = {}, restaurarPadrao = {},
        importarConfiguracoes = {},
        abrirNovo = {}, fecharNovo = {}, criarLancamento = {},
        verificarAtualizacao = verificar, baixarAtualizacao = baixar,
    )

    @Test fun semVersaoNovaDizQueEstaEmDia() {
        var verificou = false
        regra.setContent {
            TemaApp {
                TelaAtualizacao(
                    Estado(atualizacaoVerificada = true), "0.20.0", acoes(verificar = { verificou = true }), aoVoltar = {},
                )
            }
        }
        regra.onNodeWithText("Versão instalada: 0.20.0.").assertIsDisplayed()
        regra.onNodeWithText("Você está com a versão mais recente.").assertIsDisplayed()
        regra.onNodeWithText("Verificar atualizações").performClick()
        assertTrue(verificou)
    }

    @Test fun comVersaoNovaMostraONumeroEBaixa() {
        var baixou = false
        regra.setContent {
            TemaApp {
                TelaAtualizacao(
                    Estado(atualizacao = NovaVersao("0.21.0", "- Coisa nova", "https://x", "a.apk", 2_000_000), atualizacaoVerificada = true),
                    "0.20.0", acoes(baixar = { baixou = true }), aoVoltar = {},
                )
            }
        }
        regra.onNodeWithText("Versão 0.21.0").assertIsDisplayed()
        regra.onNodeWithText("Baixar e instalar").performClick()
        assertTrue(baixou)
    }
}
