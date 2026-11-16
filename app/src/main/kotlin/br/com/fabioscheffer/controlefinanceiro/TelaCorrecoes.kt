package br.com.fabioscheffer.controlefinanceiro

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A correção dita como uma frase só, para o leitor de tela. */
internal fun falaDaCorrecao(c: Correcao): String {
    val base = "${nomeDoCampo(c.campo)}: onde a IA lia ${c.valorErrado}, o certo é ${c.valorCorreto}"
    return if (c.contexto.isNullOrBlank()) "$base." else "$base. Contexto: ${c.contexto}."
}

/**
 * Correções que a IA aprendeu com o que a pessoa corrigiu na leitura dos documentos. Passos: a lista
 * (com Apagar todas), uma correção (editar ou excluir) e as confirmações de excluir.
 */
@Composable
fun TelaCorrecoes(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    var selecionada by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmando by rememberSaveable { mutableStateOf("") } // "", "uma" ou "todas"
    var errado by rememberSaveable { mutableStateOf("") }
    var correto by rememberSaveable { mutableStateOf("") }
    var contexto by rememberSaveable { mutableStateOf("") }
    var erro by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { acoes.carregarCorrecoes() }

    val voltar = {
        erro = ""
        when {
            confirmando.isNotEmpty() -> confirmando = ""
            selecionada != null -> selecionada = null
            else -> aoVoltar()
        }
    }
    BackHandler(onBack = voltar)

    val atual = estado.correcoes.firstOrNull { it.id == selecionada }
    // Depois de excluir, a correção some: volta para a lista.
    LaunchedEffect(atual, selecionada) {
        if (selecionada != null && atual == null && !estado.ocupado) {
            selecionada = null
            confirmando = ""
        }
    }
    LaunchedEffect(estado.correcoes.isEmpty(), confirmando) {
        if (confirmando == "todas" && estado.correcoes.isEmpty()) confirmando = ""
    }
    val foco = remember { FocusRequester() }
    LaunchedEffect(confirmando) { if (confirmando.isNotEmpty()) foco.requestFocus() }

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
            if (confirmando == "todas") {
                item { Titulo("Apagar todas as correções", grande = true) }
                item {
                    BotaoSecundario("Cancelar", { confirmando = "" }, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                }
                item { Text("Apagar as ${estado.correcoes.size} correções? A IA volta a ler sem o que aprendeu. Não dá para desfazer.") }
                item {
                    Botao(
                        "Apagar todas",
                        { acoes.apagarCorrecoes(); confirmando = "" },
                        habilitado = !estado.ocupado,
                    )
                }
            } else if (atual == null) {
                item { Titulo("Correções aprendidas pela IA", grande = true) }
                item { BotaoSecundario("Voltar", aoVoltar) }
                item {
                    Text(
                        when (estado.correcoes.size) {
                            0 -> "Nenhuma correção."
                            1 -> "1 correção."
                            else -> "${estado.correcoes.size} correções."
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (estado.correcoes.isNotEmpty()) {
                    item { BotaoSecundario("Apagar todas", { confirmando = "todas" }, habilitado = !estado.ocupado) }
                }
                items(estado.correcoes, key = { it.id }) { c ->
                    val abrir = {
                        selecionada = c.id
                        errado = c.valorErrado
                        correto = c.valorCorreto
                        contexto = c.contexto.orEmpty()
                        erro = ""
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = abrir)
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = falaDaCorrecao(c)
                                role = Role.Button
                                onClick(label = "Abrir") {
                                    abrir()
                                    true
                                }
                            },
                    ) {
                        Text(nomeDoCampo(c.campo), fontWeight = FontWeight.Bold)
                        Text("${c.valorErrado} → ${c.valorCorreto}")
                        if (!c.contexto.isNullOrBlank()) {
                            Text(c.contexto, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (confirmando == "uma") {
                item { Titulo("Excluir correção", grande = true) }
                item {
                    BotaoSecundario("Cancelar", { confirmando = "" }, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                }
                item { Text("Excluir esta correção? ${falaDaCorrecao(atual)}") }
                item {
                    Botao(
                        "Excluir",
                        { acoes.excluirCorrecao(atual.id); confirmando = "" },
                        habilitado = !estado.ocupado,
                    )
                }
            } else {
                item { Titulo(nomeDoCampo(atual.campo), grande = true) }
                item { BotaoSecundario("Voltar para as correções", voltar) }
                item { Campo("Valor errado", errado, { errado = it }) }
                item { Campo("Valor correto", correto, { correto = it }) }
                item { Campo("Contexto, se quiser", contexto, { contexto = it }) }
                item {
                    Botao(
                        "Salvar correção",
                        { erro = acoes.editarCorrecao(atual.id, errado, correto, contexto).orEmpty() },
                        habilitado = !estado.ocupado,
                    )
                }
                item { BotaoSecundario("Excluir", { confirmando = "uma" }, habilitado = !estado.ocupado) }
            }
        }
    }
}
