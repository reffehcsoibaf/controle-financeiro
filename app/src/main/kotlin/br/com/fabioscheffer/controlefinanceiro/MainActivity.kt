package br.com.fabioscheffer.controlefinanceiro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val modelo: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            tratarRetorno(intent)
            tratarCompartilhado(intent)
        }
        val acoes = Acoes(
            entrar = ::abrirLogin,
            atualizar = modelo::carregar,
            sair = modelo::sair,
            definirOrdem = modelo::definirOrdem,
            definirTipo = modelo::definirTipo,
            definirSituacao = modelo::definirSituacao,
            aplicarBusca = modelo::aplicarBusca,
            limparFiltros = modelo::limparFiltros,
            marcarPago = modelo::marcarPago,
            alternarCampo = modelo::alternarCampo,
            moverCampo = modelo::moverCampo,
            definirFalarQuantidade = modelo::definirFalarQuantidade,
            definirFalarTotal = modelo::definirFalarTotal,
            restaurarPadrao = modelo::restaurarPadrao,
            importarConfiguracoes = modelo::importarConfiguracoes,
            abrirNovo = modelo::abrirNovo,
            fecharNovo = modelo::fecharNovo,
            criarLancamento = modelo::criarLancamento,
            abrirEdicao = modelo::abrirEdicao,
            excluir = modelo::excluirLancamento,
            aplicarListas = modelo::aplicarListas,
            lerDocumento = modelo::lerDocumento,
            acrescentarDocumento = modelo::acrescentarDocumento,
            lerTexto = modelo::lerTexto,
            leituraAplicada = modelo::leituraAplicada,
            cadastrarTextos = modelo::cadastrarTextos,
        )
        setContent {
            val estado by modelo.estado.collectAsStateWithLifecycle()
            TemaApp {
                Tela(estado = estado, versao = BuildConfig.VERSION_NAME, acoes = acoes)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarRetorno(intent)
        tratarCompartilhado(intent)
    }

    private fun abrirLogin() {
        val url = modelo.prepararLogin()
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    }

    /** Um documento ou texto compartilhado de outro app abre o Novo lançamento e já lê. */
    private fun tratarCompartilhado(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val arquivo = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val texto = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (arquivo == null && texto.isNullOrBlank()) return
        modelo.receberCompartilhado(arquivo, texto)
        // Evita ler de novo se a tela for recriada.
        intent.action = null
    }

    /** O Android reabre o app aqui quando o Supabase devolve o login para o nosso endereço. */
    private fun tratarRetorno(intent: Intent?) {
        val dados = intent?.data ?: return
        if (dados.scheme == Config.ESQUEMA && dados.host == "login") {
            modelo.receberRetorno(dados)
            // Evita tratar o mesmo código de novo se a tela for recriada.
            intent.data = null
        }
    }
}
