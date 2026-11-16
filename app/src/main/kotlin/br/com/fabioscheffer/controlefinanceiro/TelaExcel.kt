package br.com.fabioscheffer.controlefinanceiro

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Exportar os lançamentos da lista para Excel e importar uma planilha no mesmo formato. */
@Composable
fun TelaExcel(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    val contexto = LocalContext.current
    var local by rememberSaveable { mutableStateOf("") }
    val voltar = { if (estado.importacao != null) acoes.cancelarImportacao() else aoVoltar() }
    BackHandler(onBack = voltar)

    val gravar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Documentos.XLSX_MIME)) { uri ->
        if (uri != null) {
            local = try {
                val bytes = Planilha.exportar(estado.lancamentos)
                contexto.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Planilha salva com ${estado.lancamentos.size} lançamentos."
            } catch (e: Exception) {
                "Não foi possível salvar a planilha."
            }
        }
    }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val lido = Documentos.lerParaGuardar(contexto.contentResolver, uri)
                local = ""
                acoes.lerPlanilha(lido.nome, lido.bytes)
            } catch (e: ErroDocumento) {
                local = e.message ?: "Não foi possível abrir o arquivo."
            }
        }
    }
    val imp = estado.importacao
    val foco = remember { FocusRequester() }
    LaunchedEffect(imp != null) { if (imp != null) foco.requestFocus() }

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
            if (imp != null) {
                item { Titulo("Importar do Excel", grande = true) }
                item { BotaoSecundario("Cancelar", { acoes.cancelarImportacao() }, modificador = Modifier.fillMaxWidth().focusRequester(foco)) }
                item { Text("Arquivo: ${imp.nome}") }
                item {
                    Text(
                        "${imp.novos.size} lançamentos novos e ${imp.repetidos} que já existem. " +
                            "Os que já existem são ignorados.",
                    )
                }
                if (imp.novos.isNotEmpty()) {
                    item {
                        Botao(
                            if (imp.novos.size == 1) "Importar 1 lançamento" else "Importar ${imp.novos.size} lançamentos",
                            { acoes.confirmarImportacao() },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
            } else {
                item { Titulo("Excel", grande = true) }
                item { BotaoSecundario("Voltar", aoVoltar) }
                item { Titulo("Exportar") }
                item {
                    Text(
                        "Grava os ${estado.lancamentos.size} lançamentos que aparecem na lista agora, respeitando os filtros, " +
                            "no mesmo formato do site.",
                    )
                }
                item { Botao("Exportar para Excel", { gravar.launch("lancamentos.xlsx") }, habilitado = !estado.ocupado) }
                item { Titulo("Importar") }
                item {
                    Text(
                        "Aceita planilha XLSX no formato exportado pelo app, pelo site ou pelo programa do Windows. " +
                            "Lançamentos que já existem são ignorados, e o app diz quantos são novos antes de importar.",
                    )
                }
                item {
                    BotaoSecundario(
                        "Importar do Excel",
                        { escolher.launch(arrayOf(Documentos.XLSX_MIME, "application/octet-stream")) },
                        habilitado = !estado.ocupado,
                    )
                }
            }
        }
    }
}
