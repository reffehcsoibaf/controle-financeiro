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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Saldos e limites, em tela própria com três passos: a lista, uma conta, e configurar ou remover.
 * O Voltar do celular desfaz um passo de cada vez. Nada aqui depende só de cor: a conta que passou do
 * saldo ou do limite diz "Atenção" no texto.
 */
@Composable
fun TelaContas(
    estado: Estado,
    acoes: Acoes,
    aoVoltar: () -> Unit,
    aoVerLancamentos: (String) -> Unit,
) {
    var selecionada by rememberSaveable { mutableStateOf<String?>(null) }
    // "detalhe", "configurar" ou "remover"
    var modo by rememberSaveable { mutableStateOf("detalhe") }
    var tipo by rememberSaveable { mutableStateOf(CARTAO_CREDITO) }
    var valor by rememberSaveable { mutableStateOf("") }
    var erro by rememberSaveable { mutableStateOf("") }
    val situacoes = Contas.situacoes(estado.contas, estado.todos, estado.opcoes["banco"].orEmpty())
    val atual = situacoes.firstOrNull { it.nome == selecionada }
    val voltar = {
        erro = ""
        when {
            selecionada != null && modo != "detalhe" -> modo = "detalhe"
            selecionada != null -> selecionada = null
            else -> aoVoltar()
        }
    }
    BackHandler(onBack = voltar)
    val foco = remember { FocusRequester() }
    LaunchedEffect(modo, selecionada) { if (modo == "remover" && selecionada != null) foco.requestFocus() }

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
            if (atual == null) {
                item { Titulo("Saldos e limites", grande = true) }
                item { BotaoSecundario("Voltar", aoVoltar) }
                item {
                    Text(
                        "Conta corrente: o saldo conta só o que está pago. Cartão de crédito: o limite conta " +
                            "também o que está pendente, porque costuma ser fatura em aberto."
                    )
                }
                if (situacoes.isEmpty()) item { Text("Nenhum banco ou cartão cadastrado nas listas.") }
                items(situacoes) { s ->
                    val abrir = {
                        selecionada = s.nome
                        modo = "detalhe"
                        erro = ""
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = abrir)
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = s.fala
                                role = Role.Button
                                onClick(label = "Abrir") {
                                    abrir()
                                    true
                                }
                            },
                    ) {
                        Text(s.nome, fontWeight = FontWeight.Bold)
                        Text(s.nomeDoTipo, style = MaterialTheme.typography.bodySmall)
                        if (s.detalhe.isNotEmpty()) Text(s.detalhe, style = MaterialTheme.typography.bodySmall)
                        if (s.alerta) Text(textoDeAlerta(s), fontWeight = FontWeight.Bold)
                    }
                }
            } else when (modo) {
                "configurar" -> {
                    val rotuloValor = if (tipo == CARTAO_CREDITO) "Limite total" else "Saldo inicial"
                    item { Titulo("Configurar: ${atual.nome}", grande = true) }
                    item {
                        GrupoOpcoes(
                            "Tipo",
                            listOf(CARTAO_CREDITO to "Cartão de crédito", CONTA_CORRENTE to "Conta corrente"),
                            tipo,
                        ) { tipo = it }
                    }
                    item {
                        Campo(
                            rotuloValor, valor, { valor = it },
                            KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    item {
                        Botao(
                            "Salvar",
                            {
                                val lido = try {
                                    Regras.lerValor(valor)
                                } catch (e: IllegalArgumentException) {
                                    null
                                }
                                if (lido == null) {
                                    erro = "Informe o ${rotuloValor.lowercase()} em números."
                                } else {
                                    erro = ""
                                    acoes.salvarConta(
                                        ContaConfig(
                                            atual.nome, tipo,
                                            saldoInicial = if (tipo == CONTA_CORRENTE) lido else null,
                                            limiteTotal = if (tipo == CARTAO_CREDITO) lido else null,
                                        ),
                                    )
                                    modo = "detalhe"
                                }
                            },
                            habilitado = !estado.ocupado,
                        )
                    }
                    item { BotaoSecundario("Cancelar", voltar) }
                }
                "remover" -> {
                    item { Titulo("Remover configuração", grande = true) }
                    item {
                        Text(
                            "Remover a configuração de ${atual.nome}? O nome continua nas listas, só deixa de " +
                                "ter saldo ou limite calculado."
                        )
                    }
                    item {
                        BotaoSecundario("Cancelar", voltar, modificador = Modifier.fillMaxWidth().focusRequester(foco))
                    }
                    item {
                        Botao(
                            "Remover",
                            {
                                acoes.removerConta(atual.nome)
                                modo = "detalhe"
                            },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
                else -> {
                    item { Titulo(atual.nome, grande = true) }
                    item { BotaoSecundario("Voltar para a lista", voltar) }
                    item { Text(atual.nomeDoTipo) }
                    item { Text(atual.detalhe.ifEmpty { "Sem saldo nem limite calculado. Use Configurar." }) }
                    if (atual.alerta) item { Text(textoDeAlerta(atual), fontWeight = FontWeight.Bold) }
                    item { Botao("Ver lançamentos desta conta", { aoVerLancamentos(atual.nome) }) }
                    item {
                        BotaoSecundario("Configurar", {
                            val c = estado.contas.firstOrNull { it.nome == atual.nome }
                            tipo = if (c?.tipo == CONTA_CORRENTE) CONTA_CORRENTE else CARTAO_CREDITO
                            val inicial = if (tipo == CARTAO_CREDITO) c?.limiteTotal else c?.saldoInicial
                            valor = inicial?.let { Formatacao.numero(it) }.orEmpty()
                            erro = ""
                            modo = "configurar"
                        })
                    }
                    if (atual.tipo.isNotEmpty()) {
                        item { BotaoSecundario("Remover configuração", { modo = "remover" }, habilitado = !estado.ocupado) }
                    }
                }
            }
        }
    }
}

private fun textoDeAlerta(s: SituacaoConta) =
    if (s.tipo == CARTAO_CREDITO) "Atenção: passou do limite." else "Atenção: saldo negativo."
