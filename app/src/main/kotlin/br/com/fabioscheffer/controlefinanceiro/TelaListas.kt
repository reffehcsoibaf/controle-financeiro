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
import androidx.compose.ui.unit.dp

/**
 * Gerenciar as listas de opções (banco, forma, estabelecimento, categoria, credor, devedor), em tela
 * própria. Passos: escolher a lista, ver as opções (e adicionar), uma opção (renomear ou excluir) e a
 * confirmação de excluir. Renomear troca o texto em todos os lançamentos; é o mesmo que o Windows faz.
 */
@Composable
fun TelaListas(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    var campo by rememberSaveable { mutableStateOf<String?>(null) }
    var opcao by rememberSaveable { mutableStateOf<String?>(null) }
    var excluindo by rememberSaveable { mutableStateOf(false) }
    var nova by rememberSaveable { mutableStateOf("") }
    var novoNome by rememberSaveable { mutableStateOf("") }
    var erro by rememberSaveable { mutableStateOf("") }

    val voltar = {
        erro = ""
        when {
            excluindo -> excluindo = false
            opcao != null -> opcao = null
            campo != null -> campo = null
            else -> aoVoltar()
        }
    }
    BackHandler(onBack = voltar)

    val valores = campo?.let { estado.opcoes[it].orEmpty() }.orEmpty()
    // Depois de renomear ou excluir, a opção some da lista: volta para ela.
    LaunchedEffect(valores, opcao) {
        if (opcao != null && opcao !in valores) {
            opcao = null
            excluindo = false
        }
    }
    val foco = remember { FocusRequester() }
    LaunchedEffect(excluindo) { if (excluindo) foco.requestFocus() }

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
            val nomeLista = campo?.let { nomeDoCampo(it) }.orEmpty()
            val atual = opcao
            if (campo == null) {
                item { Titulo("Gerenciar listas", grande = true) }
                item { BotaoSecundario("Voltar", aoVoltar) }
                item { Text("Escolha a lista que quer mudar.") }
                items(LISTAS_DE_OPCOES, key = { it.first }) { (chave, nome) ->
                    val n = estado.opcoes[chave].orEmpty().size
                    Botao("$nome, ${if (n == 1) "1 opção" else "$n opções"}", { campo = chave; nova = "" })
                }
            } else if (atual == null) {
                item { Titulo(nomeLista, grande = true) }
                item { BotaoSecundario("Voltar para as listas", voltar) }
                item { Titulo("Adicionar") }
                item { Campo("Nova opção", nova, { nova = it }) }
                item {
                    Botao(
                        "Adicionar à lista",
                        {
                            val e = acoes.adicionarOpcao(campo!!, nova)
                            erro = e.orEmpty()
                            if (e == null) nova = ""
                        },
                        habilitado = !estado.ocupado,
                    )
                }
                item {
                    Text(
                        when (valores.size) {
                            0 -> "Nenhuma opção."
                            1 -> "1 opção."
                            else -> "${valores.size} opções."
                        },
                    )
                }
                items(valores, key = { it }) { v ->
                    Text(
                        v,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Abrir", role = Role.Button) {
                                opcao = v
                                novoNome = v
                                erro = ""
                            }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 12.dp),
                    )
                }
            } else if (excluindo) {
                item { Titulo("Excluir opção", grande = true) }
                item {
                    BotaoSecundario("Cancelar", { excluindo = false }, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                }
                item { Text("Excluir $atual da lista de ${nomeLista.lowercase()}?") }
                item { Text("Os lançamentos que já usam essa opção continuam com o texto como está.") }
                if (campo == "banco") {
                    item { Text("A configuração de saldo e limite desse banco ou cartão também será removida.") }
                }
                item {
                    Botao(
                        "Excluir",
                        {
                            acoes.excluirOpcao(campo!!, atual)
                            excluindo = false
                        },
                        habilitado = !estado.ocupado,
                    )
                }
            } else {
                item { Titulo(atual, grande = true) }
                item { BotaoSecundario("Voltar para $nomeLista", voltar) }
                item { Titulo("Renomear") }
                item { Text("Renomear troca o texto em todos os lançamentos que usam essa opção.") }
                item { Campo("Novo nome", novoNome, { novoNome = it }) }
                item {
                    Botao(
                        "Renomear",
                        { erro = acoes.renomearOpcao(campo!!, atual, novoNome).orEmpty() },
                        habilitado = !estado.ocupado,
                    )
                }
                item { BotaoSecundario("Excluir", { excluindo = true }, habilitado = !estado.ocupado) }
            }
        }
    }
}
