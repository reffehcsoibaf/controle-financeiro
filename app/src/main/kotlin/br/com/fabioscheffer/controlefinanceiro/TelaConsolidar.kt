package br.com.fabioscheffer.controlefinanceiro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Os lançamentos que a tela de consolidar mostra: os filtros são os do programa Windows. */
object Consolidar {
    fun filtrar(
        todos: List<Lancamento>, excluir: Set<Long>, busca: String, credor: String, devedor: String,
        incluirPagos: Boolean, origemDe: String, origemAte: String, vencDe: String, vencAte: String,
    ): List<Lancamento> {
        val termo = Regras.semAcentos(busca).trim()
        val c = Regras.semAcentos(credor).trim()
        val d = Regras.semAcentos(devedor).trim()
        return todos.filter { l ->
            val origem = l.dataOrigem.orEmpty()
            val venc = l.dataVencimento.orEmpty()
            when {
                l.id in excluir -> false
                !incluirPagos && l.pago -> false
                origemDe.isNotEmpty() && (origem.isEmpty() || origem < origemDe) -> false
                origemAte.isNotEmpty() && (origem.isEmpty() || origem > origemAte) -> false
                vencDe.isNotEmpty() && (venc.isEmpty() || venc < vencDe) -> false
                vencAte.isNotEmpty() && (venc.isEmpty() || venc > vencAte) -> false
                c.isNotEmpty() && c !in Regras.semAcentos(l.credor) -> false
                d.isNotEmpty() && d !in Regras.semAcentos(l.devedor) -> false
                termo.isNotEmpty() && termo !in Regras.semAcentos(
                    listOf(l.credor, l.devedor, l.descricao, l.banco, l.estabelecimento, l.categoria)
                        .joinToString(" ") { it.orEmpty() },
                ) -> false
                else -> true
            }
        }.sortedByDescending { it.dataOrigem.orEmpty() }
    }

    /** Os itens que um lançamento antigo vira: os dele, ou um só com o valor e a descrição dele. Sempre positivos. */
    fun itensDe(l: Lancamento): List<EntradaItem> {
        val fontes = l.itens.ifEmpty { listOf(Item(l.valor, l.categoria, l.descricao)) }
        return fontes.map {
            EntradaItem(
                Formatacao.numero(Math.abs(it.valor)), it.categoria.orEmpty(),
                it.descricao.orEmpty().ifBlank { "(sem descrição)" }, l.dataOrigem.orEmpty(), l.id,
            )
        }
    }
}

/**
 * Escolher lançamentos antigos para virarem itens do que está sendo digitado. Aparece no lugar do
 * formulário (que continua guardado por baixo); só filtra e marca, e quem usa o resultado é o formulário.
 */
@Composable
fun TelaConsolidar(
    estado: Estado,
    excluir: Set<Long>,
    aoCancelar: () -> Unit,
    aoImportar: (List<Lancamento>) -> Unit,
) {
    var busca by rememberSaveable { mutableStateOf("") }
    var credor by rememberSaveable { mutableStateOf("") }
    var devedor by rememberSaveable { mutableStateOf("") }
    var pagos by rememberSaveable { mutableStateOf(false) }
    var origemDe by rememberSaveable { mutableStateOf("") }
    var origemAte by rememberSaveable { mutableStateOf("") }
    var vencDe by rememberSaveable { mutableStateOf("") }
    var vencAte by rememberSaveable { mutableStateOf("") }
    var marcadosTexto by rememberSaveable { mutableStateOf("") }
    var aviso by rememberSaveable { mutableStateOf("") }
    val marcados = marcadosTexto.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    val campos = estado.config.campos

    val (od, eod) = dataDoFiltro(origemDe, "Origem, de")
    val (oa, eoa) = dataDoFiltro(origemAte, "Origem, até")
    val (vd, evd) = dataDoFiltro(vencDe, "Vencimento, de")
    val (va, eva) = dataDoFiltro(vencAte, "Vencimento, até")
    val erro = eod ?: eoa ?: evd ?: eva ?: ""
    val lista = Consolidar.filtrar(estado.todos, excluir, busca, credor, devedor, pagos, od, oa, vd, va)
    val escolhidos = estado.todos.filter { it.id in marcados }

    BackHandler(onBack = aoCancelar)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Aviso(erro.ifEmpty { aviso })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Importar lançamentos como itens", grande = true) }
            item {
                Text(
                    "Os lançamentos marcados viram itens do lançamento em andamento. Os originais serão " +
                        "excluídos ao salvar, e os documentos deles passam para este.",
                )
            }
            item { Campo("Buscar", busca, { busca = it }) }
            item { Campo("Credor", credor, { credor = it }) }
            item { Campo("Devedor", devedor, { devedor = it }) }
            item { Text("Para as datas, digite só os números: dia, mês e ano, como 25102026.") }
            item { CampoData("Data de origem, de", origemDe) { origemDe = it } }
            item { CampoData("Data de origem, até", origemAte) { origemAte = it } }
            item { CampoData("Vencimento, de", vencDe) { vencDe = it } }
            item { CampoData("Vencimento, até", vencAte) { vencAte = it } }
            item { OpcaoMarcavel("Incluir lançamentos já pagos", pagos) { pagos = it } }
            item {
                Text(
                    when (lista.size) {
                        0 -> "Nenhum lançamento."
                        1 -> "1 lançamento."
                        else -> "${lista.size} lançamentos."
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            items(lista, key = { it.id }) { l ->
                val marcado = l.id in marcados
                val alternar = {
                    val novos = if (marcado) marcados - l.id else marcados + l.id
                    marcadosTexto = novos.joinToString(",")
                    val total = Regras.somar(estado.todos.filter { it.id in novos }.map { Math.abs(it.valor) })
                    aviso = (if (marcado) "Desmarcado. " else "Marcado. ") + when (novos.size) {
                        0 -> "Nenhum marcado."
                        1 -> "1 marcado, total ${Formatacao.reaisFalado(total)}."
                        else -> "${novos.size} marcados, total ${Formatacao.reaisFalado(total)}."
                    }
                }
                val fala = Formatacao.fala(l, campos)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = alternar)
                        .heightIn(min = 48.dp)
                        .padding(vertical = 6.dp)
                        .clearAndSetSemantics {
                            contentDescription = fala
                            role = Role.Checkbox
                            toggleableState = if (marcado) ToggleableState.On else ToggleableState.Off
                            onClick(label = if (marcado) "Desmarcar" else "Marcar") {
                                alternar()
                                true
                            }
                        },
                ) {
                    Text(if (marcado) "Marcado" else "Não marcado", fontWeight = FontWeight.Bold)
                    Text(fala, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotaoSecundario("Cancelar", aoCancelar, modificador = Modifier.weight(1f))
            Botao(
                if (escolhidos.size == 1) "Importar 1 lançamento" else "Importar ${escolhidos.size} lançamentos",
                {
                    if (escolhidos.isEmpty()) aviso = "Marque ao menos um lançamento." else aoImportar(escolhidos)
                },
                modificador = Modifier.weight(1f),
            )
        }
    }
}
