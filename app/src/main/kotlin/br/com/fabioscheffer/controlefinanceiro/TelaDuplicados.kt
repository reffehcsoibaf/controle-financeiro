package br.com.fabioscheffer.controlefinanceiro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** Limpar lançamentos duplicados: mostra as cópias, e o foco começa em Cancelar. */
@Composable
fun TelaDuplicados(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    BackHandler(onBack = aoVoltar)
    val copias = Duplicados.copias(estado.todos)
    val foco = remember { FocusRequester() }
    LaunchedEffect(copias.isNotEmpty()) { if (copias.isNotEmpty()) foco.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Aviso(estado.aviso)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Limpar lançamentos duplicados", grande = true) }
            if (copias.isEmpty()) {
                item { BotaoSecundario("Voltar", aoVoltar, modificador = Modifier.fillMaxWidth().focusRequester(foco)) }
                item { Text("Nenhum lançamento duplicado encontrado.") }
            } else {
                item {
                    BotaoSecundario("Cancelar", aoVoltar, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                }
                item {
                    Text(
                        if (copias.size == 1) "Encontrei 1 lançamento duplicado. Ele será excluído, e o mais antigo do grupo fica."
                        else "Encontrei ${copias.size} lançamentos duplicados. Eles serão excluídos, e o mais antigo de cada grupo fica.",
                    )
                }
                item { Text("Não dá para desfazer.") }
                item {
                    Botao(
                        if (copias.size == 1) "Excluir a cópia" else "Excluir as ${copias.size} cópias",
                        { acoes.limparDuplicados() },
                        habilitado = !estado.ocupado,
                    )
                }
                item { Titulo("Cópias que serão excluídas") }
                items(copias, key = { it.id }) { Text(Formatacao.fala(it, estado.config.campos)) }
            }
        }
    }
}
