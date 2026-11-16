package br.com.fabioscheffer.controlefinanceiro

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** O tamanho do arquivo em MB, com vírgula: "12,3 MB". */
internal fun tamanhoEmMb(bytes: Long): String =
    String.format(java.util.Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024.0))

/** Atualizar o app: diz a versão instalada, procura uma nova no GitHub e entrega o APK ao instalador. */
@Composable
fun TelaAtualizacao(estado: Estado, versao: String, acoes: Acoes, aoVoltar: () -> Unit) {
    BackHandler(onBack = aoVoltar)
    val contexto = LocalContext.current
    var local by rememberSaveable { mutableStateOf("") }

    // O APK baixado vai para o instalador do sistema. Na primeira vez o Android pede para permitir que
    // este app instale outros: a pessoa permite nas configurações e volta.
    val apk = estado.instalarApk
    LaunchedEffect(apk?.id) {
        if (apk != null) {
            try {
                if (!contexto.packageManager.canRequestPackageInstalls()) {
                    val ajustes = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + contexto.packageName))
                    contexto.startActivity(ajustes)
                    local = "Para instalar, permita que este app instale outros apps, volte e toque em Baixar e instalar de novo."
                } else {
                    val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", File(apk.caminho))
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, apk.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    contexto.startActivity(intent)
                    local = ""
                }
            } catch (e: Exception) {
                local = "Não foi possível abrir o instalador."
            }
            acoes.apkEntregue()
        }
    }

    val nova = estado.atualizacao
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Aviso(local.ifEmpty { estado.aviso })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Atualizar o app", grande = true) }
            item { BotaoSecundario("Voltar", aoVoltar) }
            item { Text("Versão instalada: $versao.") }
            if (nova != null) {
                item { Titulo("Versão ${nova.versao}") }
                item { Text("Há uma versão nova. Tamanho: ${tamanhoEmMb(nova.tamanho)}.") }
                item {
                    Botao("Baixar e instalar", { acoes.baixarAtualizacao() }, habilitado = !estado.ocupado)
                }
                if (nova.notas.isNotBlank()) {
                    item { Titulo("Novidades") }
                    for (linha in nova.notas.lines().map { it.trim().trimStart('#', ' ') }.filter { it.isNotEmpty() }) {
                        item { Text(linha) }
                    }
                }
                item { BotaoSecundario("Verificar de novo", { acoes.verificarAtualizacao() }, habilitado = !estado.ocupado) }
            } else {
                item {
                    Text(
                        if (estado.atualizacaoVerificada) "Você está com a versão mais recente."
                        else "Ainda não foi verificado se há versão nova.",
                    )
                }
                item { Botao("Verificar atualizações", { acoes.verificarAtualizacao() }, habilitado = !estado.ocupado) }
            }
        }
    }
}
