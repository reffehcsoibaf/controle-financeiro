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

/** Frase curta para quem abre a lista: diz quando os dados não estão em dia. Vazia se está tudo certo. */
fun avisoDeSincronizacao(e: Estado): String {
    val partes = ArrayList<String>()
    e.atualizacao?.let { partes += "Há uma versão nova do app: ${it.versao}. Veja em Configurações, Atualizar o app." }
    if (e.offline) partes += "Sem conexão. Mostrando os dados guardados em ${Offline.dataFalada(e.salvoEm)}."
    if (e.fila.isNotEmpty()) {
        partes += if (e.fila.size == 1) "1 alteração aguarda envio." else "${e.fila.size} alterações aguardam envio."
    }
    if (e.conflitos.isNotEmpty()) {
        partes += (if (e.conflitos.size == 1) "1 alteração não foi aceita pelo servidor" else "${e.conflitos.size} alterações não foram aceitas pelo servidor") +
            ". Veja em Configurações, Sincronização."
    }
    return partes.joinToString(" ")
}

/** Sincronização: o estado da cópia do celular, as alterações esperando envio e os conflitos. */
@Composable
fun TelaSincronizacao(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    BackHandler(onBack = aoVoltar)
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
            item { Titulo("Sincronização", grande = true) }
            item { BotaoSecundario("Voltar", aoVoltar) }
            item {
                Text(
                    if (estado.offline) "Sem conexão com a internet."
                    else "Conectado.",
                )
            }
            item {
                Text(
                    if (estado.salvoEm.isEmpty()) "Os dados ainda não foram guardados neste celular."
                    else "Última atualização: ${Offline.dataFalada(estado.salvoEm)}.",
                )
            }
            item {
                Text(
                    "Sem internet, o app abre com os dados guardados e deixa você marcar como pago, editar, excluir e criar " +
                        "lançamentos. As alterações ficam guardadas e são enviadas quando a conexão voltar.",
                )
            }
            item { Botao("Sincronizar agora", { acoes.sincronizar() }, habilitado = !estado.ocupado) }

            item { Titulo("Alterações a enviar") }
            if (estado.fila.isEmpty()) {
                item { Text("Nenhuma alteração esperando.") }
            }
            items(estado.fila, key = { it.id }) { op ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${op.oQueFaz}: ${op.rotulo}")
                    BotaoSecundario(
                        "Descartar: ${op.oQueFaz}, ${op.rotulo}",
                        { acoes.descartarPendente(op.id) },
                        habilitado = !estado.ocupado,
                    )
                }
            }

            item { Titulo("Alterações não aceitas") }
            if (estado.conflitos.isEmpty()) {
                item { Text("Nenhuma.") }
            } else {
                item {
                    Text(
                        "O lançamento foi alterado ou excluído em outro lugar antes de a sua alteração chegar. " +
                            "Escolha o que fazer com cada uma.",
                    )
                }
            }
            items(estado.conflitos, key = { it.operacao.id }) { c ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${c.operacao.oQueFaz}: ${c.operacao.rotulo}. ${c.motivo}")
                    if (c.operacao !is Operacao.Criar) {
                        Botao(
                            "Manter a minha alteração: ${c.operacao.oQueFaz}, ${c.operacao.rotulo}",
                            { acoes.manterAlteracao(c.operacao.id) },
                            habilitado = !estado.ocupado,
                        )
                    }
                    BotaoSecundario(
                        "Descartar a minha alteração: ${c.operacao.oQueFaz}, ${c.operacao.rotulo}",
                        { acoes.descartarConflito(c.operacao.id) },
                    )
                }
            }
        }
    }
}

/** Sair da conta: apaga os dados deste celular, por isso pede confirmação, com o foco em Cancelar. */
@Composable
fun TelaSair(estado: Estado, aoCancelar: () -> Unit, aoSair: () -> Unit) {
    BackHandler(onBack = aoCancelar)
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // O botão fica numa lista: espera o primeiro quadro para ele já existir.
        androidx.compose.runtime.withFrameNanos { }
        try {
            foco.requestFocus()
        } catch (e: IllegalStateException) {
            // Sem o botão na tela ainda, o foco fica onde o sistema pôs.
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Aviso("")
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Sair da conta", grande = true) }
            item { BotaoSecundario("Cancelar", aoCancelar, modificador = Modifier.fillMaxWidth().focusRequester(foco)) }
            item { Text("Conta em uso: ${estado.email}.") }
            item {
                Text(
                    "Sair apaga deste celular a cópia dos dados e os documentos baixados. Os dados online continuam guardados.",
                )
            }
            val pendentes = estado.fila.size + estado.conflitos.size
            if (pendentes > 0) {
                item {
                    Text(
                        (if (pendentes == 1) "Atenção: 1 alteração ainda não foi enviada e será perdida."
                        else "Atenção: $pendentes alterações ainda não foram enviadas e serão perdidas."),
                    )
                }
            }
            item { Botao("Sair da conta e apagar os dados deste celular", aoSair) }
        }
    }
}
