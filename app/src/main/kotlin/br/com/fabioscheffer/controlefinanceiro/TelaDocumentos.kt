package br.com.fabioscheffer.controlefinanceiro

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Documentos guardados, em tela própria. Três passos: a lista (com busca por nome e por dia), um
 * documento (abrir, salvar cópia, analisar com IA, vincular, excluir) e a confirmação de excluir.
 * Com [alvo], a tela foi aberta a partir de um lançamento: mostra os documentos dele e deixa
 * vincular a ele qualquer documento guardado.
 */
@Composable
fun TelaDocumentos(
    estado: Estado,
    acoes: Acoes,
    alvo: Long?,
    aoVoltar: () -> Unit,
    aoIrAoLancamento: (Long) -> Unit,
) {
    var selecionado by rememberSaveable { mutableStateOf<String?>(null) }
    var excluindo by rememberSaveable { mutableStateOf(false) }
    var busca by rememberSaveable { mutableStateOf("") }
    var de by rememberSaveable { mutableStateOf("") }
    var ate by rememberSaveable { mutableStateOf("") }
    var verTodos by rememberSaveable { mutableStateOf(alvo == null) }
    var copiando by rememberSaveable { mutableStateOf("") }
    val contexto = LocalContext.current
    val campos = estado.config.campos
    val lancamentoDe = { id: Long? -> id?.let { v -> estado.todos.firstOrNull { it.id == v } } }

    val voltar = {
        when {
            excluindo -> excluindo = false
            selecionado != null -> selecionado = null
            else -> aoVoltar()
        }
    }
    BackHandler(onBack = voltar)

    val escolherArquivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) acoes.guardarArquivo(uri)
    }
    val escolherDestino = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && copiando.isNotEmpty()) acoes.salvarCopia(copiando, uri)
    }
    // O documento baixado abre em outro app; a tela avisa quando já entregou.
    val paraAbrir = estado.paraAbrir
    LaunchedEffect(paraAbrir?.id) {
        if (paraAbrir != null) {
            try {
                val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", File(paraAbrir.caminho))
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, paraAbrir.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                contexto.startActivity(Intent.createChooser(intent, "Abrir documento"))
            } catch (e: Exception) {
                // Sem app para abrir esse tipo de arquivo: a pessoa ainda pode salvar uma cópia.
            }
            acoes.arquivoAberto()
        }
    }

    val doc = estado.documentos.firstOrNull { it.id == selecionado }
    // Depois de excluir, o documento some: volta para a lista.
    LaunchedEffect(doc, selecionado) { if (selecionado != null && doc == null) selecionado = null }
    val foco = remember { FocusRequester() }
    LaunchedEffect(excluindo) { if (excluindo) foco.requestFocus() }
    val (deIso, erroDe) = dataDoFiltro(de, "Guardado de")
    val (ateIso, erroAte) = dataDoFiltro(ate, "Guardado até")
    val erro = erroDe ?: erroAte ?: ""

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Aviso(erro.ifEmpty { estado.aviso })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (doc == null) {
                item { Titulo(if (alvo != null && !verTodos) "Documentos do lançamento" else "Documentos", grande = true) }
                item { BotaoSecundario("Voltar", aoVoltar) }
                if (alvo != null) {
                    lancamentoDe(alvo)?.let { item { Text("Lançamento: ${Formatacao.fala(it, campos)}.") } }
                    item {
                        BotaoSecundario(
                            if (verTodos) "Ver só os documentos deste lançamento" else "Ver todos os documentos",
                            { verTodos = !verTodos },
                        )
                    }
                }
                item {
                    Botao(
                        "Guardar arquivo",
                        { escolherArquivo.launch(arrayOf("*/*")) },
                        habilitado = !estado.ocupado,
                    )
                }
                item { Titulo("Buscar") }
                item { Campo("Buscar pelo nome", busca, { busca = it }) }
                item { Text("Para as datas, digite só os números: dia, mês e ano, como 25102026.") }
                item { CampoData("Guardado de", de) { de = it } }
                item { CampoData("Guardado até", ate) { ate = it } }
                val lista = Guardados.filtrar(
                    estado.documentos, busca, deIso, ateIso, if (alvo != null && !verTodos) alvo else null,
                )
                item {
                    Text(
                        when (lista.size) {
                            0 -> "Nenhum documento."
                            1 -> "1 documento."
                            else -> "${lista.size} documentos."
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(lista, key = { it.id }) { d ->
                    val par = lancamentoDe(d.lancamentoId)
                    val abrir = { selecionado = d.id }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = abrir)
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = Guardados.fala(d, par, campos)
                                role = Role.Button
                                onClick(label = "Abrir") {
                                    abrir()
                                    true
                                }
                            },
                    ) {
                        Text(d.nome, fontWeight = FontWeight.Bold)
                        Text(
                            "${Formatacao.dataCurta(Guardados.dataLocal(d.criadoEm))} · ${Guardados.tamanhoFalado(d.tamanho)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (par != null) "Lançamento: ${Formatacao.fala(par, campos)}"
                            else if (d.lancamentoId != null) "Vinculado a um lançamento" else "Sem lançamento",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (excluindo) {
                item { Titulo("Excluir documento", grande = true) }
                item {
                    BotaoSecundario("Cancelar", { excluindo = false }, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                }
                item { Text("Excluir o documento ${doc.nome}? Essa ação não pode ser desfeita.") }
                item {
                    Botao(
                        "Excluir",
                        {
                            acoes.excluirDocumento(doc.id)
                            excluindo = false
                            selecionado = null
                        },
                        habilitado = !estado.ocupado,
                    )
                }
            } else {
                val par = lancamentoDe(doc.lancamentoId)
                item { Titulo(doc.nome, grande = true) }
                item { BotaoSecundario("Voltar para a lista", voltar) }
                item { Text("Guardado em ${Formatacao.dataFalada(Guardados.dataLocal(doc.criadoEm))}.") }
                item { Text("Tamanho: ${Guardados.tamanhoFalado(doc.tamanho)}.") }
                item {
                    Text(
                        if (par != null) "Lançamento: ${Formatacao.fala(par, campos)}."
                        else if (doc.lancamentoId != null) "Vinculado a um lançamento." else "Sem lançamento."
                    )
                }
                item { Botao("Abrir", { acoes.abrirDocumento(doc.id) }, habilitado = !estado.ocupado) }
                item {
                    BotaoSecundario(
                        "Salvar cópia",
                        {
                            copiando = doc.id
                            escolherDestino.launch(doc.nome)
                        },
                        habilitado = !estado.ocupado,
                    )
                }
                if (estado.permissoes.ia) {
                    item {
                        BotaoSecundario(
                            "Analisar com IA",
                            { acoes.analisarDocumento(doc.id) },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
                if (par != null) {
                    item { BotaoSecundario("Ir ao lançamento", { aoIrAoLancamento(par.id) }) }
                }
                val alvoAtual = lancamentoDe(alvo)
                if (alvoAtual != null && doc.lancamentoId != alvoAtual.id) {
                    item {
                        BotaoSecundario(
                            "Vincular ao lançamento ${Formatacao.titulo(alvoAtual)}",
                            { acoes.vincularDocumento(doc.id, alvoAtual.id) },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
                if (doc.lancamentoId != null) {
                    item {
                        BotaoSecundario(
                            "Desvincular",
                            { acoes.vincularDocumento(doc.id, null) },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
                item { BotaoSecundario("Excluir", { excluindo = true }, habilitado = !estado.ocupado) }
            }
        }
    }
}

/** A data digitada como AAAA-MM-DD e o erro, se houver: data que não vale não filtra e é avisada. */
private fun dataDoFiltro(texto: String, nome: String): Pair<String, String?> = try {
    Regras.lerData(texto).orEmpty() to null
} catch (e: IllegalArgumentException) {
    "" to "$nome: data inválida. Digite dia, mês e ano, como 25102026."
}
