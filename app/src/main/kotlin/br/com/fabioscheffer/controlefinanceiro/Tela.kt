package br.com.fabioscheffer.controlefinanceiro

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Regras (ver CLAUDE.md): nada que só dê para entender vendo; botões de pelo menos 48 dp;
 * cor nunca é a única informação; nada de janelas sobrepostas; cada tela é um fluxo simples
 * de cima para baixo, com títulos para o leitor de tela pular de seção em seção.
 */
@Composable
fun TemaApp(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            conteudo()
        }
    }
}

/** Tudo o que a tela pode pedir ao app. */
class Acoes(
    val entrar: () -> Unit,
    val atualizar: () -> Unit,
    val sair: () -> Unit,
    val definirOrdem: (String) -> Unit,
    val definirTipo: (String) -> Unit,
    val definirSituacao: (String) -> Unit,
    /** Devolve o texto do erro, ou null se aplicou. */
    val aplicarBusca: (busca: String, origemDe: String, origemAte: String, vencDe: String, vencAte: String) -> String?,
    val limparFiltros: () -> Unit,
    /** Grava no servidor; a tela só muda depois que o servidor aceita. */
    val marcarPago: (id: Long, pago: Boolean) -> Unit,
    // Configurações
    val alternarCampo: (chave: String) -> Unit,
    val moverCampo: (chave: String, delta: Int) -> Unit,
    val definirFalarQuantidade: (Boolean) -> Unit,
    val definirFalarTotal: (Boolean) -> Unit,
    val restaurarPadrao: () -> Unit,
    /** Recebe o texto do arquivo escolhido, ou vazio se não foi possível ler. */
    val importarConfiguracoes: (String) -> Unit,
    // Novo lançamento
    val abrirNovo: () -> Unit,
    val fecharNovo: () -> Unit,
    /** Com um formulário de edição aberto, grava as mudanças em vez de criar. */
    val criarLancamento: (EntradaNovo) -> Unit,
    // Editar lançamento
    val abrirEdicao: (id: Long) -> Unit = {},
    /** Exclui no servidor; com o segundo argumento, leva junto o lançamento vinculado. */
    val excluir: (id: Long, comVinculado: Boolean) -> Unit = { _, _ -> },
    /** Banco, forma, estabelecimento, categoria, credor e devedor, nesta ordem; vazio não filtra. */
    val aplicarListas: (String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _ -> },
    // Leitura de documentos pela IA e listas
    val lerDocumento: (Uri) -> Unit = {},
    /** Outra foto da mesma nota: os itens lidos são somados aos que já estão no formulário. */
    val acrescentarDocumento: (Uri) -> Unit = {},
    // Saldos e limites
    val salvarConta: (ContaConfig) -> Unit = {},
    val removerConta: (String) -> Unit = {},
    val verLancamentosDaConta: (String) -> Unit = {},
    val lerTexto: (String) -> Unit = {},
    /** O formulário já usou o que a IA leu. */
    val leituraAplicada: () -> Unit = {},
    /** Acrescenta às listas os textos novos (campo, texto). */
    val cadastrarTextos: (List<Pair<String, String>>) -> Unit = {},
    // Documentos guardados
    val guardarArquivo: (Uri) -> Unit = {},
    val abrirDocumento: (String) -> Unit = {},
    /** A tela já entregou o documento baixado ao outro app. */
    val arquivoAberto: () -> Unit = {},
    val salvarCopia: (id: String, destino: Uri) -> Unit = { _, _ -> },
    /** Liga o documento ao lançamento, ou desliga se o lançamento for null. */
    val vincularDocumento: (id: String, lancamentoId: Long?) -> Unit = { _, _ -> },
    val excluirDocumento: (String) -> Unit = {},
    /** Lê com a IA um documento já guardado e abre o Novo lançamento ligado a ele. */
    val analisarDocumento: (String) -> Unit = {},
    /** O arquivo lido continua guardado, mas não fica ligado ao lançamento em andamento. */
    val naoVincularDocumentos: () -> Unit = {},
    // Gerenciar listas e correções aprendidas (os que devolvem texto devolvem o erro, ou null)
    val adicionarOpcao: (campo: String, texto: String) -> String? = { _, _ -> null },
    val renomearOpcao: (campo: String, antigo: String, novo: String) -> String? = { _, _, _ -> null },
    val excluirOpcao: (campo: String, valor: String) -> Unit = { _, _ -> },
    val carregarCorrecoes: () -> Unit = {},
    val editarCorrecao: (id: Long, errado: String, correto: String, contexto: String) -> String? = { _, _, _, _ -> null },
    val excluirCorrecao: (Long) -> Unit = {},
    val apagarCorrecoes: () -> Unit = {},
    // Sem internet e sincronização
    val sincronizar: () -> Unit = {},
    val manterAlteracao: (String) -> Unit = {},
    val descartarConflito: (String) -> Unit = {},
    val descartarPendente: (String) -> Unit = {},
    // Atualização do app
    val verificarAtualizacao: () -> Unit = {},
    val baixarAtualizacao: () -> Unit = {},
    val apkEntregue: () -> Unit = {},
    val limparDuplicados: () -> Unit = {},
    val lerPlanilha: (nome: String, bytes: ByteArray?) -> Unit = { _, _ -> },
    val cancelarImportacao: () -> Unit = {},
    val confirmarImportacao: () -> Unit = {},
)

/** As áreas do app. Só entram na barra as que já existem. */
enum class Aba(val titulo: String) {
    LANCAMENTOS("Lançamentos"),
    MAIS("Mais"),
}

@Composable
fun Tela(estado: Estado, versao: String, acoes: Acoes) {
    if (estado.etapa == Etapa.INICIO) {
        TelaEntrada(estado, acoes.entrar)
        return
    }
    // Declarados antes do formulário para sobreviverem a ele: depois de editar, volta-se aos detalhes.
    var aba by rememberSaveable { mutableStateOf(Aba.LANCAMENTOS) }
    var filtrando by rememberSaveable { mutableStateOf(false) }
    var detalheId by rememberSaveable { mutableStateOf<Long?>(null) }
    var configurando by rememberSaveable { mutableStateOf(false) }
    var contasAberto by rememberSaveable { mutableStateOf(false) }
    var excluindoId by rememberSaveable { mutableStateOf<Long?>(null) }
    var documentosAberto by rememberSaveable { mutableStateOf(false) }
    var listasAberto by rememberSaveable { mutableStateOf(false) }
    var correcoesAberto by rememberSaveable { mutableStateOf(false) }
    var guiaAberto by rememberSaveable { mutableStateOf(false) }
    var atualizandoApp by rememberSaveable { mutableStateOf(false) }
    var sincAberto by rememberSaveable { mutableStateOf(false) }
    var saindo by rememberSaveable { mutableStateOf(false) }
    var excelAberto by rememberSaveable { mutableStateOf(false) }
    var duplicadosAberto by rememberSaveable { mutableStateOf(false) }
    // Se a tela de documentos foi aberta a partir de um lançamento, é ele; senão, nenhum.
    var documentosDoLancamento by rememberSaveable { mutableStateOf<Long?>(null) }
    if (estado.novoAberto) {
        TelaNovo(estado, acoes)
        return
    }
    if (duplicadosAberto) {
        TelaDuplicados(estado, acoes, aoVoltar = { duplicadosAberto = false })
        return
    }
    if (excelAberto) {
        TelaExcel(estado, acoes, aoVoltar = { excelAberto = false })
        return
    }
    if (saindo) {
        TelaSair(estado, aoCancelar = { saindo = false }, aoSair = { saindo = false; acoes.sair() })
        return
    }
    if (sincAberto) {
        TelaSincronizacao(estado, acoes, aoVoltar = { sincAberto = false })
        return
    }
    if (atualizandoApp || estado.instalarApk != null) {
        TelaAtualizacao(estado, versao, acoes, aoVoltar = { atualizandoApp = false })
        return
    }
    if (guiaAberto) {
        TelaGuia(aoVoltar = { guiaAberto = false })
        return
    }
    if (listasAberto) {
        TelaListas(estado, acoes, aoVoltar = { listasAberto = false })
        return
    }
    if (correcoesAberto) {
        TelaCorrecoes(estado, acoes, aoVoltar = { correcoesAberto = false })
        return
    }
    if (documentosAberto) {
        TelaDocumentos(
            estado, acoes, documentosDoLancamento,
            aoVoltar = { documentosAberto = false },
            aoIrAoLancamento = { id ->
                documentosAberto = false
                aba = Aba.LANCAMENTOS
                detalheId = id
            },
        )
        return
    }
    if (contasAberto) {
        TelaContas(
            estado, acoes, aoVoltar = { contasAberto = false },
            aoVerLancamentos = { nome ->
                acoes.verLancamentosDaConta(nome)
                contasAberto = false
                aba = Aba.LANCAMENTOS
            },
        )
        return
    }
    if (configurando) {
        TelaConfiguracoes(estado, acoes, aoVoltar = { configurando = false })
        return
    }
    if (filtrando) {
        TelaFiltro(estado, acoes, aoVoltar = { filtrando = false })
        return
    }
    // Procura em todos, e não só na lista filtrada: marcar como pago pode tirar o lançamento da
    // lista (filtro "só pendentes") e a tela de detalhes não deve fechar por isso.
    val detalhe = detalheId?.let { id -> estado.todos.firstOrNull { it.id == id } }
    val aExcluir = excluindoId?.let { id -> estado.todos.firstOrNull { it.id == id } }
    if (aExcluir != null) {
        TelaExcluir(estado, aExcluir, acoes, aoCancelar = { excluindoId = null })
        return
    }
    if (detalhe != null) {
        TelaDetalhe(estado, detalhe, acoes, aoVoltar = { detalheId = null }, aoExcluir = { excluindoId = detalhe.id },
            aoIrPara = { detalheId = it },
            aoVerDocumentos = {
                documentosDoLancamento = detalhe.id
                documentosAberto = true
            })
        return
    }
    Scaffold(
        bottomBar = {
            // Cada item é lido como "Lançamentos, guia 1 de 2, selecionada".
            NavigationBar {
                Aba.entries.forEach { destino ->
                    NavigationBarItem(
                        selected = aba == destino,
                        onClick = { aba = destino },
                        icon = {},
                        label = { Text(destino.titulo) },
                    )
                }
            }
        },
    ) { margens ->
        val modificador = Modifier
            .fillMaxSize()
            .padding(margens)
        when (aba) {
            Aba.LANCAMENTOS -> TelaLancamentos(
                estado, acoes.atualizar, { filtrando = true }, { detalheId = it }, acoes.abrirNovo, modificador,
            )
            Aba.MAIS -> TelaMais(
                estado, versao, { saindo = true }, { configurando = true }, { contasAberto = true },
                {
                    documentosDoLancamento = null
                    documentosAberto = true
                },
                { listasAberto = true },
                { correcoesAberto = true },
                { duplicadosAberto = true },
                { excelAberto = true },
                { sincAberto = true },
                { atualizandoApp = true },
                { guiaAberto = true },
                modificador,
            )
        }
    }
}

@Composable
private fun TelaEntrada(estado: Estado, aoEntrar: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Titulo("Controle Financeiro", grande = true) }
        item { Aviso(estado.aviso) }
        item { Text("Entre com a mesma conta Google que você usa no site e no programa do Windows.") }
        item { Botao("Entrar com Google", aoEntrar, habilitado = !estado.ocupado) }
    }
}

@Composable
private fun TelaLancamentos(
    estado: Estado,
    aoAtualizar: () -> Unit,
    aoFiltrar: () -> Unit,
    aoAbrir: (Long) -> Unit,
    aoNovo: () -> Unit,
    modificador: Modifier,
) {
    Column(modificador) {
        // Fora da lista: um aviso dentro dela deixa de existir quando a pessoa rola para baixo, e
        // o leitor de tela não fala o que não está na tela.
        Aviso(estado.aviso)
        // Faixa fixa: as ações não rolam junto com a lista, que pode ter milhares de linhas.
        Botao(
            "Novo lançamento", aoNovo, habilitado = !estado.ocupado,
            modificador = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Botao("Atualizar", aoAtualizar, habilitado = !estado.ocupado, modificador = Modifier.weight(1f))
            BotaoSecundario("Filtrar e ordenar", aoFiltrar, modificador = Modifier.weight(1f))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Lançamentos", grande = true) }
            val sincronizacao = avisoDeSincronizacao(estado)
            if (sincronizacao.isNotEmpty()) item { Text(sincronizacao) }
            item { Titulo("Resumo") }
            item { BlocoResumo(estado.resumo, estado.avisoDeFiltro) }
            item { Titulo("Lista de lançamentos") }
            if (estado.lancamentos.isEmpty()) {
                item {
                    Text(
                        when {
                            estado.ocupado -> "Carregando."
                            estado.todos.isNotEmpty() -> "Nenhum lançamento com estes filtros."
                            else -> "Nenhum lançamento para mostrar."
                        }
                    )
                }
            }
            items(estado.lancamentos, key = { it.id }) { LinhaLancamento(it, estado.config.campos) { aoAbrir(it.id) } }
        }
    }
}

/** Uma tela própria, sem janela sobreposta. O botão Voltar do celular também volta. */
@Composable
private fun TelaFiltro(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    val f = estado.filtros
    var busca by rememberSaveable { mutableStateOf(f.busca) }
    var origemDe by rememberSaveable { mutableStateOf(dataDigitada(f.origemDe)) }
    var origemAte by rememberSaveable { mutableStateOf(dataDigitada(f.origemAte)) }
    var vencDe by rememberSaveable { mutableStateOf(dataDigitada(f.vencDe)) }
    var vencAte by rememberSaveable { mutableStateOf(dataDigitada(f.vencAte)) }
    var banco by rememberSaveable { mutableStateOf(f.banco) }
    var forma by rememberSaveable { mutableStateOf(f.forma) }
    var estabelecimento by rememberSaveable { mutableStateOf(f.estabelecimento) }
    var categoria by rememberSaveable { mutableStateOf(f.categoria) }
    var credor by rememberSaveable { mutableStateOf(f.credor) }
    var devedor by rememberSaveable { mutableStateOf(f.devedor) }
    var erro by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = aoVoltar)

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
    // Fixo no alto: o erro de uma data e a contagem "Mostrando X de Y" precisam ser falados mesmo
    // quando a pessoa está lá embaixo, perto do botão Aplicar.
    Aviso(erro.ifEmpty { estado.aviso })
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Titulo("Filtrar e ordenar", grande = true) }
        item { BotaoSecundario("Voltar para os lançamentos", aoVoltar) }
        item {
            GrupoOpcoes(
                "Ordem",
                listOf("recentes" to "Mais recentes primeiro", "antigos" to "Mais antigos primeiro"),
                f.ordem, acoes.definirOrdem,
            )
        }
        item {
            GrupoOpcoes(
                "Tipo",
                listOf("" to "Entradas e saídas", "entrada" to "Só entradas", "saida" to "Só saídas"),
                f.tipo, acoes.definirTipo,
            )
        }
        item {
            GrupoOpcoes(
                "Situação",
                listOf("" to "Todos", "pendente" to "Só pendentes", "pago" to "Só pagos"),
                f.status, acoes.definirSituacao,
            )
        }
        item { Titulo("Busca e datas") }
        item {
            Text(
                "Para as datas, digite só os números: dia, mês e ano, como 25102026. " +
                    "Deixe vazio para não filtrar."
            )
        }
        item { Campo("Buscar", busca, { busca = it }) }
        item { CampoData("Data de origem a partir de", origemDe) { origemDe = it } }
        item { CampoData("Data de origem até", origemAte) { origemAte = it } }
        item { CampoData("Vencimento a partir de", vencDe) { vencDe = it } }
        item { CampoData("Vencimento até", vencAte) { vencAte = it } }
        item { Titulo("Banco, forma e outros") }
        item {
            Text(
                "Mostra só os lançamentos que têm exatamente o que você escolher. " +
                    "Deixe vazio para não filtrar."
            )
        }
        item { ComboCampo("Banco ou cartão", banco, estado.opcoes["banco"].orEmpty()) { banco = it } }
        item { ComboCampo("Forma de pagamento", forma, estado.opcoes["forma"].orEmpty()) { forma = it } }
        item {
            ComboCampo("Estabelecimento", estabelecimento, estado.opcoes["estabelecimento"].orEmpty()) {
                estabelecimento = it
            }
        }
        item { ComboCampo("Categoria", categoria, estado.opcoes["categoria"].orEmpty()) { categoria = it } }
        item { ComboCampo("Credor", credor, estado.opcoes["credor"].orEmpty()) { credor = it } }
        item { ComboCampo("Devedor", devedor, estado.opcoes["devedor"].orEmpty()) { devedor = it } }
        item {
            Botao("Aplicar filtros", {
                val problema = acoes.aplicarBusca(busca, origemDe, origemAte, vencDe, vencAte)
                if (problema == null) {
                    acoes.aplicarListas(banco, forma, estabelecimento, categoria, credor, devedor)
                    erro = ""
                    aoVoltar()
                } else {
                    erro = problema
                }
            })
        }
        item {
            BotaoSecundario("Limpar filtros", {
                busca = ""
                origemDe = ""
                origemAte = ""
                vencDe = ""
                vencAte = ""
                banco = ""
                forma = ""
                estabelecimento = ""
                categoria = ""
                credor = ""
                devedor = ""
                erro = ""
                acoes.limparFiltros()
            })
        }
    }
    }
}

/** Detalhes de um lançamento, em tela própria. O botão Voltar do celular também volta. */
@Composable
private fun TelaDetalhe(
    estado: Estado,
    l: Lancamento,
    acoes: Acoes,
    aoVoltar: () -> Unit,
    aoExcluir: () -> Unit = {},
    aoIrPara: (Long) -> Unit = {},
    aoVerDocumentos: () -> Unit = {},
) {
    BackHandler(onBack = aoVoltar)
    val area = LocalClipboardManager.current
    var copiado by remember(l.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Aviso(copiado.ifEmpty { estado.aviso })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Detalhes do lançamento", grande = true) }
            item { BotaoSecundario("Voltar para a lista", aoVoltar) }
            item {
                // Desligado enquanto o servidor responde, para um toque duplo não gravar duas vezes.
                Botao(
                    if (l.pago) "Marcar como pendente" else "Marcar como pago",
                    { acoes.marcarPago(l.id, !l.pago) },
                    habilitado = !estado.ocupado,
                )
            }
            item { BotaoSecundario("Editar lançamento", { acoes.abrirEdicao(l.id) }, habilitado = !estado.ocupado) }
            item {
                BotaoSecundario(
                    "Copiar detalhes",
                    {
                        val linhas = Formatacao.camposDoDetalhe(l, estado.config.campos).map { (r, v) -> "$r: $v" } +
                            Formatacao.itensDoDetalhe(l) + Formatacao.pagamentosDoDetalhe(l)
                        area.setText(AnnotatedString(linhas.joinToString("\n")))
                        copiado = "Detalhes copiados."
                    },
                )
            }
            item { BotaoSecundario("Excluir lançamento", aoExcluir, habilitado = !estado.ocupado) }
            val par = l.vinculadoId?.takeIf { v -> estado.todos.any { it.id == v } }
            if (par != null) item { BotaoSecundario("Ir ao lançamento vinculado", { aoIrPara(par) }) }
            if (estado.permissoes.documentos) {
                val quantos = estado.documentos.count { it.lancamentoId == l.id }
                item {
                    BotaoSecundario(
                        when (quantos) {
                            0 -> "Vincular um documento"
                            1 -> "Ver documento vinculado"
                            else -> "Ver os $quantos documentos vinculados"
                        },
                        aoVerDocumentos,
                    )
                }
            }
            items(Formatacao.camposDoDetalhe(l, estado.config.campos)) { (rotulo, valor) -> Text("$rotulo: $valor") }
            if (l.itens.isNotEmpty()) {
                item { Titulo("Itens") }
                items(Formatacao.itensDoDetalhe(l)) { Text(it) }
            }
            if (l.pagamentos.isNotEmpty()) {
                item { Titulo("Pagamentos") }
                items(Formatacao.pagamentosDoDetalhe(l)) { Text(it) }
            }
        }
    }
}

/**
 * Confirmação de exclusão, em tela própria. O foco começa em Cancelar, para um toque sem querer não
 * apagar nada, e a tela diz exatamente o que será apagado. Se há um lançamento vinculado, a pessoa
 * escolhe entre excluir os dois ou só este, como no programa Windows.
 */
@Composable
private fun TelaExcluir(estado: Estado, l: Lancamento, acoes: Acoes, aoCancelar: () -> Unit) {
    BackHandler(onBack = aoCancelar)
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { foco.requestFocus() }
    val par = l.vinculadoId?.let { v -> estado.todos.firstOrNull { it.id == v } }
    val campos = estado.config.campos
    val junto = listOfNotNull(
        if (l.itens.isNotEmpty()) (if (l.itens.size == 1) "o item" else "os ${l.itens.size} itens") else null,
        if (l.pagamentos.isNotEmpty()) {
            if (l.pagamentos.size == 1) "o pagamento" else "os ${l.pagamentos.size} pagamentos"
        } else {
            null
        },
    )
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
            item { Titulo("Excluir lançamento", grande = true) }
            item {
                BotaoSecundario(
                    "Cancelar", aoCancelar,
                    modificador = Modifier.fillMaxWidth().focusRequester(foco),
                )
            }
            item { Text("Você vai excluir: ${Formatacao.fala(l, campos)}.") }
            if (junto.isNotEmpty()) {
                item { Text(junto.joinToString(" e ").replaceFirstChar { it.uppercase() } + (if (l.itens.size + l.pagamentos.size > 1) " dele também serão apagados." else " dele também será apagado.")) }
            }
            if (par != null) {
                item { Text("Ele está vinculado a outro lançamento: ${Formatacao.fala(par, campos)}.") }
            }
            item { Text("Não dá para desfazer.") }
            if (par != null) {
                item { Botao("Excluir os dois", { acoes.excluir(l.id, true) }, habilitado = !estado.ocupado) }
                item { BotaoSecundario("Excluir só este", { acoes.excluir(l.id, false) }, habilitado = !estado.ocupado) }
            } else {
                item { Botao("Excluir", { acoes.excluir(l.id, false) }, habilitado = !estado.ocupado) }
            }
        }
    }
}

@Composable
private fun TelaMais(
    estado: Estado,
    versao: String,
    aoSair: () -> Unit,
    aoConfigurar: () -> Unit,
    aoContas: () -> Unit,
    aoDocumentos: () -> Unit,
    aoListas: () -> Unit,
    aoCorrecoes: () -> Unit,
    aoDuplicados: () -> Unit,
    aoExcel: () -> Unit,
    aoSincronizar: () -> Unit,
    aoAtualizarApp: () -> Unit,
    aoGuia: () -> Unit,
    modificador: Modifier,
) {
    Column(modificador) {
        Aviso(estado.aviso)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Mais", grande = true) }
            item { Titulo("Preferências") }
            item { Botao("Saldos e limites", aoContas) }
            if (estado.permissoes.documentos) item { Botao("Documentos", aoDocumentos) }
            item { Botao("Gerenciar listas", aoListas) }
            if (estado.permissoes.ia) item { Botao("Correções aprendidas pela IA", aoCorrecoes) }
            item { Botao("Configurações", aoConfigurar) }
            item { Botao("Excel: exportar e importar", aoExcel) }
            item { Botao("Sincronização", aoSincronizar) }
            item { Botao("Limpar lançamentos duplicados", aoDuplicados) }
            item { Botao(if (estado.atualizacao != null) "Atualizar o app: há versão nova" else "Atualizar o app", aoAtualizarApp) }
            item { Botao("Guia de uso", aoGuia) }
            item { Titulo("Conta") }
            item { Text("Conectado como ${estado.email}") }
            item { BotaoSecundario("Sair da conta", aoSair) }
            item { Titulo("Sobre") }
            item { Text("Controle Financeiro para Android") }
            item { Text("Versão $versao") }
        }
    }
}

private const val LIMITE_ARQUIVO_BYTES = 100_000

/** Lê o arquivo escolhido. Devolve null se for grande demais (não é um arquivo de configurações) ou der erro. */
private fun lerArquivoPequeno(contexto: Context, uri: Uri): String? = try {
    contexto.contentResolver.openInputStream(uri)?.use { entrada ->
        val buffer = ByteArray(LIMITE_ARQUIVO_BYTES + 1)
        var lidos = 0
        while (lidos < buffer.size) {
            val n = entrada.read(buffer, lidos, buffer.size - lidos)
            if (n < 0) break
            lidos += n
        }
        if (lidos > LIMITE_ARQUIVO_BYTES) null else String(buffer, 0, lidos, Charsets.UTF_8)
    }
} catch (e: Exception) {
    null
}

private fun dataDigitada(iso: String): String = if (iso.isEmpty()) "" else Formatacao.dataCurta(iso)

@Composable
internal fun Titulo(texto: String, grande: Boolean = false) {
    Text(
        texto,
        style = if (grande) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

/** Região ativa: o TalkBack fala o texto novo sempre que ele muda. */
@Composable
internal fun Aviso(texto: String) {
    Text(
        texto,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun Botao(
    texto: String,
    aoClicar: () -> Unit,
    habilitado: Boolean = true,
    modificador: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = aoClicar,
        enabled = habilitado,
        modifier = modificador.heightIn(min = 48.dp),
    ) { Text(texto) }
}

@Composable
internal fun BotaoSecundario(
    texto: String,
    aoClicar: () -> Unit,
    habilitado: Boolean = true,
    modificador: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedButton(
        onClick = aoClicar,
        enabled = habilitado,
        modifier = modificador.heightIn(min = 48.dp),
    ) { Text(texto) }
}

/** Opções em lista, na própria tela: cada uma é lida como "botão de opção, selecionado". */
@Composable
internal fun GrupoOpcoes(
    titulo: String,
    opcoes: List<Pair<String, String>>,
    selecionada: String,
    aoEscolher: (String) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        Titulo(titulo)
        opcoes.forEach { (valor, rotulo) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selecionada == valor,
                        onClick = { aoEscolher(valor) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selecionada == valor, onClick = null)
                Text(rotulo, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
internal fun Campo(
    rotulo: String,
    valor: String,
    aoMudar: (String) -> Unit,
    teclado: KeyboardOptions = KeyboardOptions.Default,
    umaLinha: Boolean = true,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = umaLinha,
        keyboardOptions = teclado,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    )
}

private fun hoje(): String = Formatacao.dataCurta(java.time.LocalDate.now().toString())

/**
 * Campo de texto com sugestões das listas cadastradas. As sugestões aparecem na própria tela, logo
 * abaixo do campo (sem janela sobreposta), conforme se digita; o botão abre as opções para escolher.
 * O texto digitado vale mesmo que não esteja na lista, como no Windows.
 */
@Composable
private fun ComboCampo(rotulo: String, valor: String, opcoes: List<String>, aoMudar: (String) -> Unit) {
    var verOpcoes by rememberSaveable(rotulo) { mutableStateOf(false) }
    val digitado = Regras.semAcentos(valor).trim()
    val jaEscolhida = opcoes.any { Regras.semAcentos(it) == digitado }
    val limite = if (verOpcoes) 30 else 5
    val sugestoes = if (verOpcoes || (digitado.isNotEmpty() && !jaEscolhida)) {
        NovoLancamento.sugestoes(opcoes, valor, limite)
    } else {
        emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Campo(rotulo, valor, aoMudar)
        if (opcoes.isNotEmpty()) {
            BotaoSecundario(
                if (verOpcoes) "Esconder opções de $rotulo" else "Ver opções de $rotulo",
                { verOpcoes = !verOpcoes },
            )
        }
        sugestoes.forEach { opcao ->
            BotaoSecundario("Usar $opcao", {
                aoMudar(opcao)
                verOpcoes = false
            })
        }
        if (verOpcoes && sugestoes.size == limite) {
            Text("Mostrando as $limite primeiras opções. Digite no campo para filtrar.")
        }
    }
}

private val SaverItens = Saver<List<EntradaItem>, String>(
    save = { lista ->
        JSONArray(lista.map { JSONArray(listOf(it.valor, it.categoria, it.descricao, it.dataOriginal, it.origemId)) }).toString()
    },
    restore = { texto ->
        val a = JSONArray(texto)
        List(a.length()) { i ->
            val x = a.getJSONArray(i)
            EntradaItem(x.getString(0), x.getString(1), x.getString(2), x.optString(3), x.optLong(4))
        }
    },
)

private val SaverPagamentos = Saver<List<EntradaPagamento>, String>(
    save = { lista ->
        JSONArray(lista.map { JSONArray(listOf(it.banco, it.forma, it.valor)) }).toString()
    },
    restore = { texto ->
        val a = JSONArray(texto)
        List(a.length()) { i ->
            val x = a.getJSONArray(i)
            EntradaPagamento(x.getString(0), x.getString(1), x.getString(2))
        }
    },
)

/** Uma frase só, para a região ativa: o texto novo é falado sempre que muda. */
@Composable
private fun TextoAvisado(texto: String) {
    Text(texto, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun falaDoItem(n: Int, i: EntradaItem): String {
    val valor = Regras.lerValor(i.valor)
    val partes = listOfNotNull(
        i.descricao.trim().ifEmpty { null }, i.categoria.trim().ifEmpty { null },
        if (valor < 0) "desconto de ${Formatacao.reaisFalado(abs(valor))}" else Formatacao.reaisFalado(valor),
    )
    return "Item $n: ${partes.joinToString(", ")}"
}

private fun resumoDosItens(itens: List<EntradaItem>): String {
    if (itens.isEmpty()) return "Nenhum item. O valor do lançamento é o que você digitar no campo Valor."
    val soma = NovoLancamento.somaDosItens(itens) ?: return "${itens.size} itens, com um valor inválido."
    return (if (itens.size == 1) "1 item" else "${itens.size} itens") +
        ", total ${Formatacao.reaisFalado(soma)}. O valor do lançamento é a soma dos itens."
}

/**
 * Novo lançamento, em tela própria. Salvar e Cancelar ficam fixos embaixo, sempre ao alcance, e
 * Alt+S salva (como nos outros módulos). Erros e o resultado vão para o aviso fixo no alto.
 *
 * Itens, pagamento dividido e parcelar ficam depois dos campos, cada um com o seu título; os itens e
 * as parcelas começam recolhidos e abrem com um botão, sem janela por cima.
 */
@Composable
private fun TelaNovo(estado: Estado, acoes: Acoes) {
    // (o botão Voltar do formulário é tratado mais abaixo, depois do estado da consolidação)
    // Editando: o formulário começa com o que já está gravado; criando, vazio.
    val emEdicao = estado.editandoId != null
    val inicial = remember {
        estado.editandoId?.let { id -> estado.todos.firstOrNull { it.id == id } }?.let(NovoLancamento::entradaDe)
    }
    var entrada by rememberSaveable { mutableStateOf(inicial?.entrada ?: false) }
    var valor by rememberSaveable { mutableStateOf(inicial?.valor ?: "") }
    var origem by rememberSaveable { mutableStateOf(inicial?.origem ?: hoje()) }
    var vencimento by rememberSaveable { mutableStateOf(inicial?.vencimento ?: "") }
    var banco by rememberSaveable { mutableStateOf(inicial?.banco ?: "") }
    var forma by rememberSaveable { mutableStateOf(inicial?.forma ?: "") }
    var estabelecimento by rememberSaveable { mutableStateOf(inicial?.estabelecimento ?: "") }
    var categoria by rememberSaveable { mutableStateOf(inicial?.categoria ?: "") }
    var credor by rememberSaveable { mutableStateOf(inicial?.credor ?: "") }
    var devedor by rememberSaveable { mutableStateOf(inicial?.devedor ?: "") }
    var descricao by rememberSaveable { mutableStateOf(inicial?.descricao ?: "") }
    var pago by rememberSaveable { mutableStateOf(inicial?.pago ?: false) }
    // Itens
    var itens by rememberSaveable(stateSaver = SaverItens) { mutableStateOf(inicial?.itens ?: emptyList()) }
    var itensAbertos by rememberSaveable { mutableStateOf(inicial?.itens?.isNotEmpty() == true) }
    var itemEditando by rememberSaveable { mutableStateOf(-1) }
    var itemValor by rememberSaveable { mutableStateOf("") }
    var itemCategoria by rememberSaveable { mutableStateOf("") }
    var itemDescricao by rememberSaveable { mutableStateOf("") }
    // Pagamento dividido
    var dividir by rememberSaveable { mutableStateOf(inicial?.dividir ?: false) }
    var pagamentos by rememberSaveable(stateSaver = SaverPagamentos) { mutableStateOf(inicial?.pagamentos ?: emptyList()) }
    var pagEditando by rememberSaveable { mutableStateOf(-1) }
    var pagBanco by rememberSaveable { mutableStateOf("") }
    var pagForma by rememberSaveable { mutableStateOf("") }
    var pagValor by rememberSaveable { mutableStateOf("") }
    // Parcelar
    var parcelas by rememberSaveable { mutableStateOf("") }
    var parcelasAbertas by rememberSaveable { mutableStateOf(false) }
    var centavosNaPrimeira by rememberSaveable { mutableStateOf(false) }
    // O que acontece só aqui (item adicionado, pagamento removido...) é falado por este aviso.
    var avisoLocal by rememberSaveable { mutableStateOf("") }
    // Consolidar: a tela de escolher lançamentos antigos toma o lugar do formulário, que continua guardado.
    var consolidando by rememberSaveable { mutableStateOf(false) }
    // Se o lançamento veio de um documento, o valor dele: os itens não passam disso. Negativo: sem limite.
    var valorDocumento by rememberSaveable { mutableStateOf(-1.0) }
    BackHandler(onBack = { if (consolidando) consolidando = false else acoes.fecharNovo() })
    // Pagamento de fatura: a categoria Cartão de crédito oferece o cartão que recebe a entrada. Com um
    // só cartão configurado, ele já vem escolhido; "Não vincular" cria só este lançamento.
    var cartaoAQuitar by rememberSaveable { mutableStateOf("") }
    val ehFatura = !emEdicao && itens.isEmpty() && Regras.categoriaEFatura(categoria)
    val cartoes = if (ehFatura) Contas.cartoesParaQuitar(estado.contas, if (dividir) "" else banco) else emptyList()
    LaunchedEffect(cartoes) {
        if (cartaoAQuitar !in cartoes) cartaoAQuitar = if (cartoes.size == 1) cartoes[0] else ""
    }

    val atual = EntradaNovo(
        entrada, valor, origem, vencimento, banco, forma, estabelecimento, categoria,
        credor, devedor, descricao, pago, itens, dividir, pagamentos, parcelas, centavosNaPrimeira,
        cartaoAQuitar = if (cartoes.isNotEmpty()) cartaoAQuitar else "",
        consolidar = itens.map { it.origemId }.filter { it > 0 }.distinct(),
    )
    val salvar = {
        avisoLocal = ""
        acoes.criarLancamento(atual)
    }

    // O que a IA leu entra no formulário uma vez só; a pessoa confere e corrige antes de salvar.
    val leitura = estado.leitura
    LaunchedEffect(leitura?.id) {
        if (leitura != null) {
            val r = if (leitura.acrescentar) {
                LeituraIA.acrescentar(leitura.dados, atual, estado.opcoes)
            } else {
                LeituraIA.aplicar(leitura.dados, atual, estado.opcoes)
            }
            // Os itens não podem passar do valor lido: com 2 ou mais produtos, vale a soma deles; senão o valor total.
            val ia = leitura.dados
            val doDocumento = if (ia.itens.size >= 2) ia.itens.sumOf { abs(it.valor) } else abs(ia.valor ?: 0.0)
            if (doDocumento > 0) {
                val maisDoQueJaTinha = if (ia.itens.isNotEmpty()) ia.itens.sumOf { abs(it.valor) } else doDocumento
                valorDocumento = if (leitura.acrescentar && valorDocumento >= 0) valorDocumento + maisDoQueJaTinha else doDocumento
            }
            entrada = r.entrada.entrada
            valor = r.entrada.valor
            origem = r.entrada.origem
            vencimento = r.entrada.vencimento
            banco = r.entrada.banco
            forma = r.entrada.forma
            estabelecimento = r.entrada.estabelecimento
            categoria = r.entrada.categoria
            credor = r.entrada.credor
            devedor = r.entrada.devedor
            descricao = r.entrada.descricao
            itens = r.entrada.itens
            itensAbertos = r.entrada.itens.isNotEmpty()
            dividir = r.entrada.dividir
            pagamentos = r.entrada.pagamentos
            avisoLocal = listOf(r.texto, leitura.aviso).filter { it.isNotEmpty() }.joinToString(" ")
            acoes.leituraAplicada()
        }
    }
    val contexto = LocalContext.current
    // Se a próxima leitura soma itens aos que já estão no formulário, em vez de começar de novo.
    var maisItens by rememberSaveable { mutableStateOf(false) }
    val lerUri = { uri: Uri -> if (maisItens) acoes.acrescentarDocumento(uri) else acoes.lerDocumento(uri) }
    val escolherDocumento = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lerUri(uri)
    }
    val textosNovos = NovoLancamento.textosNovos(atual, estado.opcoes)
    // A foto do documento: quem tira é a câmera do sistema, por isso o app não pede permissão de
    // câmera. O endereço do arquivo fica guardado, pois a tela pode ser recriada com a câmera aberta.
    var fotoUri by rememberSaveable { mutableStateOf("") }
    val fotografar = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { tirou ->
        if (tirou && fotoUri.isNotEmpty()) lerUri(Uri.parse(fotoUri))
        else avisoLocal = "Nenhuma foto foi tirada."
    }

    fun limparFormularioDeItem() {
        itemEditando = -1
        itemValor = ""
        itemCategoria = ""
        itemDescricao = ""
    }

    fun salvarItem() {
        val lido = try {
            Regras.lerValor(itemValor)
        } catch (e: IllegalArgumentException) {
            avisoLocal = "Informe o valor do item em números, por exemplo 12,50."
            return
        }
        val assinado = lido
        val editando = itemEditando
        if (valorDocumento >= 0) {
            val outros = itens.filterIndexed { i, _ -> i != editando }
                .sumOf { abs(NovoLancamento.valorOuZero(it.valor)) }
            val limite = maxOf(0.0, valorDocumento - outros)
            if (abs(assinado) > limite + 0.005) {
                avisoLocal = "A soma dos itens não pode passar do valor do documento. " +
                    "Máximo para este item: ${Formatacao.reaisFalado(limite)}."
                return
            }
        }
        val novo = EntradaItem(
            Formatacao.numero(assinado), itemCategoria.trim(), itemDescricao.trim(),
            itens.getOrNull(editando)?.dataOriginal.orEmpty(),
        )
        itens = if (editando in itens.indices) {
            itens.toMutableList().also { it[editando] = novo }
        } else {
            itens + novo
        }
        limparFormularioDeItem()
        avisoLocal = (if (editando >= 0) "Item alterado. " else "Item adicionado. ") + resumoDosItens(itens)
    }

    fun editarItem(n: Int) {
        val i = itens[n]
        val lido = Regras.lerValor(i.valor)
        itemEditando = n
        itemValor = Formatacao.numero(lido)
        itemCategoria = i.categoria
        itemDescricao = i.descricao
        avisoLocal = "Editando o item ${n + 1}. Altere os campos e use Salvar alterações."
    }

    fun removerItem(n: Int) {
        itens = itens.filterIndexed { i, _ -> i != n }
        if (itemEditando == n) limparFormularioDeItem() else if (itemEditando > n) itemEditando -= 1
        avisoLocal = "Item removido. " + resumoDosItens(itens)
    }

    fun limparFormularioDePagamento() {
        pagEditando = -1
        pagBanco = ""
        pagForma = ""
        pagValor = ""
    }

    fun resumoDosPagamentos(): String {
        if (pagamentos.isEmpty()) return "Nenhum pagamento adicionado."
        val soma = NovoLancamento.somaDosPagamentos(pagamentos)
        val total = NovoLancamento.valorBruto(atual)
        return (if (pagamentos.size == 1) "1 pagamento" else "${pagamentos.size} pagamentos") +
            (if (soma != null) ", total ${Formatacao.reaisFalado(soma)}" else ", com um valor inválido") +
            (if (total != null) " de ${Formatacao.reaisFalado(abs(total))} do lançamento." else ".")
    }

    fun salvarPagamento() {
        val lido = try {
            Regras.lerValor(pagValor)
        } catch (e: IllegalArgumentException) {
            0.0
        }
        if (pagBanco.isBlank()) {
            avisoLocal = "Escolha o banco ou cartão do pagamento."
            return
        }
        if (lido <= 0) {
            avisoLocal = "Informe um valor maior que zero para o pagamento."
            return
        }
        val novo = EntradaPagamento(pagBanco.trim(), pagForma.trim(), Formatacao.numero(lido))
        val editando = pagEditando
        pagamentos = if (editando in pagamentos.indices) {
            pagamentos.toMutableList().also { it[editando] = novo }
        } else {
            pagamentos + novo
        }
        limparFormularioDePagamento()
        avisoLocal = (if (editando >= 0) "Pagamento alterado. " else "Pagamento adicionado. ") + resumoDosPagamentos()
    }

    fun editarPagamento(n: Int) {
        val p = pagamentos[n]
        pagEditando = n
        pagBanco = p.banco
        pagForma = p.forma
        pagValor = p.valor
        avisoLocal = "Editando o pagamento ${n + 1}. Altere os campos e use Salvar alterações."
    }

    fun removerPagamento(n: Int) {
        pagamentos = pagamentos.filterIndexed { i, _ -> i != n }
        if (pagEditando == n) limparFormularioDePagamento() else if (pagEditando > n) pagEditando -= 1
        avisoLocal = "Pagamento removido. " + resumoDosPagamentos()
    }

    val previa = if (parcelas.isBlank()) null else NovoLancamento.previa(atual)
    val soma = NovoLancamento.somaDosItens(itens)

    // Lançamentos antigos viram itens: os itens deles entram aqui, e os originais saem ao salvar.
    fun receberConsolidados(escolhidos: List<Lancamento>) {
        val jaTinha = itens.isNotEmpty()
        itens = itens + escolhidos.flatMap { Consolidar.itensDe(it) }
        itensAbertos = true
        if (!jaTinha) {
            val negativo = escolhidos.any { it.valor < 0 }
            val positivo = escolhidos.any { it.valor > 0 }
            if (negativo != positivo) entrada = positivo
        }
        if (credor.isBlank()) escolhidos.mapNotNull { it.credor?.takeIf { c -> c.isNotBlank() } }.distinct()
            .singleOrNull()?.let { credor = it }
        if (devedor.isBlank()) escolhidos.mapNotNull { it.devedor?.takeIf { c -> c.isNotBlank() } }.distinct()
            .singleOrNull()?.let { devedor = it }
        avisoLocal = (if (escolhidos.size == 1) "1 lançamento importado como itens." else "${escolhidos.size} lançamentos importados como itens.") +
            " Serão excluídos ao salvar. " + resumoDosItens(itens)
    }
    if (consolidando) {
        TelaConsolidar(
            estado,
            excluir = atual.consolidar.toSet() + setOfNotNull(estado.editandoId),
            aoCancelar = { consolidando = false },
            aoImportar = { escolhidos ->
                receberConsolidados(escolhidos)
                consolidando = false
            },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .onPreviewKeyEvent { evento ->
                // Consome o apertar e o soltar: se só o soltar fosse consumido, o campo em foco
                // receberia um "s" ao apertar a tecla. Salva uma vez só, ao soltar.
                if (evento.isAltPressed && evento.key == Key.S) {
                    if (evento.type == KeyEventType.KeyUp) salvar()
                    true
                } else {
                    false
                }
            }
    ) {
        Aviso(avisoLocal.ifEmpty { estado.aviso })
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo(if (emEdicao) "Editar lançamento" else "Novo lançamento", grande = true) }
            if (!emEdicao && estado.permissoes.ia) {
                item { Titulo("Ler documento com IA") }
                item {
                    BotaoSecundario(
                        "Fotografar documento",
                        {
                            maisItens = false
                            try {
                                val uri = Documentos.novaFoto(contexto)
                                fotoUri = uri.toString()
                                fotografar.launch(uri)
                            } catch (e: Exception) {
                                avisoLocal = "Não foi possível abrir a câmera."
                            }
                        },
                        habilitado = !estado.ocupado,
                    )
                }
                item {
                    BotaoSecundario(
                        "Escolher documento",
                        {
                            maisItens = false
                            escolherDocumento.launch(
                                arrayOf("image/*", "application/pdf", "text/*", "application/xml", Documentos.XLSX_MIME)
                            )
                        },
                        habilitado = !estado.ocupado,
                    )
                }
                // Para uma nota que não coube numa foto só: cada foto nova soma os itens aos já lidos.
                if (itens.isNotEmpty()) {
                    item {
                        BotaoSecundario(
                            "Fotografar mais itens",
                            {
                                maisItens = true
                                try {
                                    val uri = Documentos.novaFoto(contexto)
                                    fotoUri = uri.toString()
                                    fotografar.launch(uri)
                                } catch (e: Exception) {
                                    avisoLocal = "Não foi possível abrir a câmera."
                                }
                            },
                            habilitado = !estado.ocupado,
                        )
                    }
                    item {
                        BotaoSecundario(
                            "Escolher mais itens de um arquivo",
                            {
                                maisItens = true
                                escolherDocumento.launch(
                                    arrayOf("image/*", "application/pdf", "text/*", "application/xml", Documentos.XLSX_MIME)
                                )
                            },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
                item {
                    BotaoSecundario(
                        "Ler o texto copiado",
                        {
                            val copiado = (contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                                ?.coerceToText(contexto)?.toString().orEmpty()
                            if (copiado.isBlank()) avisoLocal = "Não há texto copiado." else acoes.lerTexto(copiado)
                        },
                        habilitado = !estado.ocupado,
                    )
                }
                // O arquivo lido já foi guardado e será ligado ao lançamento; isto desfaz só o vínculo.
                if (estado.documentosLidos.isNotEmpty()) {
                    item {
                        BotaoSecundario(
                            "Não vincular o documento",
                            { acoes.naoVincularDocumentos() },
                            habilitado = !estado.ocupado,
                        )
                    }
                }
            }
            item {
                GrupoOpcoes(
                    "Tipo", listOf("saida" to "Saída", "entrada" to "Entrada"),
                    if (entrada) "entrada" else "saida",
                ) { entrada = it == "entrada" }
            }
            item {
                if (itens.isEmpty()) {
                    Campo(
                        "Valor", valor, { valor = it },
                        KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                } else {
                    Text(
                        "Valor: " + (if (soma != null) Formatacao.reaisFalado(abs(soma)) else "inválido") +
                            ", a soma dos itens.",
                    )
                }
            }
            item { CampoData("Data de origem", origem) { origem = it } }
            item { CampoData("Vencimento (opcional)", vencimento) { vencimento = it } }
            if (!dividir) {
                item { ComboCampo("Banco ou cartão", banco, estado.opcoes["banco"].orEmpty()) { banco = it } }
                item { ComboCampo("Forma de pagamento", forma, estado.opcoes["forma"].orEmpty()) { forma = it } }
            }
            item {
                ComboCampo("Estabelecimento", estabelecimento, estado.opcoes["estabelecimento"].orEmpty()) {
                    estabelecimento = it
                }
            }
            if (itens.isEmpty()) {
                item { ComboCampo("Categoria", categoria, estado.opcoes["categoria"].orEmpty()) { categoria = it } }
                if (ehFatura) {
                    item {
                        if (cartoes.isEmpty()) {
                            Text("Para quitar um cartão, configure o cartão em Mais, Saldos e limites.")
                        } else {
                            GrupoOpcoes(
                                "Cartão a quitar",
                                listOf("" to "Não vincular") + cartoes.map { it to it },
                                cartaoAQuitar,
                            ) { cartaoAQuitar = it }
                        }
                    }
                }
            }
            item { ComboCampo("Credor", credor, estado.opcoes["credor"].orEmpty()) { credor = it } }
            item { ComboCampo("Devedor", devedor, estado.opcoes["devedor"].orEmpty()) { devedor = it } }
            if (itens.isEmpty()) {
                item { Campo("Descrição", descricao, { descricao = it }, umaLinha = false) }
            }
            item { OpcaoMarcavel("Pago", pago) { pago = it } }
            if (textosNovos.isNotEmpty()) {
                item {
                    BotaoSecundario(
                        "Cadastrar textos novos nas listas (${textosNovos.size})",
                        {
                            avisoLocal = ""
                            acoes.cadastrarTextos(textosNovos)
                        },
                        habilitado = !estado.ocupado,
                    )
                }
            }

            // ---- Itens ----
            item { Titulo("Itens do lançamento") }
            item { TextoAvisado(resumoDosItens(itens)) }
            item {
                BotaoSecundario(
                    if (itensAbertos) "Recolher os itens" else "Abrir os itens",
                    { itensAbertos = !itensAbertos },
                )
            }
            if (itensAbertos) {
                item { BotaoSecundario("Importar lançamentos como itens", { consolidando = true }, habilitado = !estado.ocupado) }
                if (atual.consolidar.isNotEmpty()) {
                    item {
                        Text(
                            "Ao salvar, ${if (atual.consolidar.size == 1) "1 lançamento antigo será excluído" else "${atual.consolidar.size} lançamentos antigos serão excluídos"}, " +
                                "e os documentos deles passam para este.",
                        )
                    }
                }
                itemsIndexed(itens) { n, i ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(falaDoItem(n + 1, i))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BotaoSecundario("Editar item ${n + 1}", { editarItem(n) }, modificador = Modifier.weight(1f))
                            BotaoSecundario("Remover item ${n + 1}", { removerItem(n) }, modificador = Modifier.weight(1f))
                        }
                    }
                }
                item { Titulo(if (itemEditando >= 0) "Editar o item ${itemEditando + 1}" else "Novo item") }
                item {
                    Campo(
                        "Valor do item (com sinal de menos é desconto)", itemValor, { itemValor = it },
                        KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    ComboCampo("Categoria do item", itemCategoria, estado.opcoes["categoria"].orEmpty()) {
                        itemCategoria = it
                    }
                }
                item { Campo("Descrição do item", itemDescricao, { itemDescricao = it }) }
                item {
                    Botao(
                        if (itemEditando >= 0) "Salvar alterações do item ${itemEditando + 1}" else "Adicionar item",
                        { salvarItem() },
                    )
                }
                if (itemEditando >= 0) {
                    item {
                        BotaoSecundario("Cancelar a edição do item", {
                            limparFormularioDeItem()
                            avisoLocal = "Edição cancelada."
                        })
                    }
                }
            }

            // ---- Pagamento dividido ----
            item { Titulo("Pagamento dividido") }
            item {
                OpcaoMarcavel("Dividir pagamento", dividir) {
                    dividir = it
                    if (!it) limparFormularioDePagamento()
                }
            }
            if (dividir) {
                item { TextoAvisado(resumoDosPagamentos()) }
                itemsIndexed(pagamentos) { n, p ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val valorPag = NovoLancamento.somaDosPagamentos(listOf(p))
                        Text(
                            "Pagamento ${n + 1}: ${p.banco}" +
                                (if (p.forma.isNotBlank()) ", ${p.forma}" else "") +
                                (if (valorPag != null) ", ${Formatacao.reaisFalado(valorPag)}" else ""),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BotaoSecundario("Editar pagamento ${n + 1}", { editarPagamento(n) }, modificador = Modifier.weight(1f))
                            BotaoSecundario("Remover pagamento ${n + 1}", { removerPagamento(n) }, modificador = Modifier.weight(1f))
                        }
                    }
                }
                item { Titulo(if (pagEditando >= 0) "Editar o pagamento ${pagEditando + 1}" else "Novo pagamento") }
                item {
                    ComboCampo("Banco ou cartão do pagamento", pagBanco, estado.opcoes["banco"].orEmpty()) {
                        pagBanco = it
                    }
                }
                item {
                    ComboCampo("Forma do pagamento", pagForma, estado.opcoes["forma"].orEmpty()) { pagForma = it }
                }
                item {
                    Campo(
                        "Valor do pagamento", pagValor, { pagValor = it },
                        KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    Botao(
                        if (pagEditando >= 0) "Salvar alterações do pagamento ${pagEditando + 1}" else "Adicionar pagamento",
                        { salvarPagamento() },
                    )
                }
                if (pagEditando >= 0) {
                    item {
                        BotaoSecundario("Cancelar a edição do pagamento", {
                            limparFormularioDePagamento()
                            avisoLocal = "Edição cancelada."
                        })
                    }
                }
            }

            // ---- Parcelar ---- (só ao criar: editar altera este lançamento, sem refazer parcelas)
            if (!emEdicao) item { Titulo("Parcelar") }
            if (!emEdicao) item {
                TextoAvisado(
                    when {
                        parcelas.isBlank() -> "Sem parcelas: será um lançamento só."
                        previa?.first == null -> previa?.second.orEmpty()
                        else -> resumoDaPrevia(previa.first!!, itens.size, if (dividir) pagamentos.size else 0)
                    }
                )
            }
            if (!emEdicao) item {
                BotaoSecundario(
                    if (parcelasAbertas) "Recolher as parcelas" else "Abrir as parcelas",
                    { parcelasAbertas = !parcelasAbertas },
                )
            }
            if (!emEdicao && parcelasAbertas) {
                item {
                    Campo(
                        "Número de parcelas, de 2 a ${NovoLancamento.MAXIMO_PARCELAS}. Deixe em branco para um lançamento só",
                        parcelas, { parcelas = it.filter { c -> c.isDigit() } },
                        KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                item {
                    GrupoOpcoes(
                        "Diferença de centavos",
                        listOf("ultima" to "Na última parcela", "primeira" to "Na primeira parcela"),
                        if (centavosNaPrimeira) "primeira" else "ultima",
                    ) { centavosNaPrimeira = it == "primeira" }
                }
                previa?.first?.let { linhas ->
                    items(linhas) { (rotulo, venc, v) ->
                        Text("Parcela $rotulo, vencimento ${Formatacao.dataCurta(venc)}, ${Formatacao.reaisFalado(v)}")
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotaoSecundario("Cancelar", acoes.fecharNovo, modificador = Modifier.weight(1f))
            Botao("Salvar", salvar, habilitado = !estado.ocupado, modificador = Modifier.weight(1f))
        }
    }
}

private fun resumoDaPrevia(linhas: List<Triple<String, String, Double>>, itens: Int, pagamentos: Int): String {
    val total = Regras.somar(linhas.map { it.third })
    var texto = "${linhas.size} parcelas, total ${Formatacao.reaisFalado(total)}. " +
        "Primeira: ${Formatacao.reaisFalado(linhas.first().third)}. Última: ${Formatacao.reaisFalado(linhas.last().third)}."
    if (itens > 0) texto += " Cada parcela leva os $itens itens, em valores proporcionais."
    if (pagamentos > 0) texto += " O pagamento segue dividido em $pagamentos formas em cada parcela."
    return "$texto As parcelas começam como pendentes."
}

@Composable
internal fun CampoData(rotulo: String, valor: String, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    )
}

/** Visualmente são linhas; para o leitor de tela é uma frase só, de uma vez. */
@Composable
private fun BlocoResumo(r: Resumo, avisoDeFiltro: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = Formatacao.resumoFalado(r, avisoDeFiltro) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (avisoDeFiltro != null) Text(avisoDeFiltro, fontWeight = FontWeight.Bold)
        LinhaResumo("Lançamentos", r.quantidade.toString())
        LinhaResumo("Entradas", Formatacao.numero(r.entradas))
        LinhaResumo("Saídas", Formatacao.numero(abs(r.saidas)))
        LinhaResumo("Saldo", Formatacao.numero(r.total))
        LinhaResumo("Pago", Formatacao.numero(r.pago))
        LinhaResumo("Pendente", Formatacao.numero(r.pendente))
    }
}

@Composable
private fun LinhaResumo(rotulo: String, valor: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo)
        Text(valor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinhaLancamento(l: Lancamento, campos: List<CampoConfig>, aoAbrir: () -> Unit) {
    val fala = Formatacao.fala(l, campos)
    val ligados = campos.filter { it.visivel }.map { it.chave }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = aoAbrir)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
            // O leitor de tela lê a frase montada em Formatacao.fala, não os pedaços visuais, e
            // sabe que a linha abre os detalhes ("toque duas vezes para abrir os detalhes").
            .clearAndSetSemantics {
                contentDescription = fala
                role = Role.Button
                onClick(label = "Abrir os detalhes") {
                    aoAbrir()
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if ("descricao" in ligados) Text(Formatacao.titulo(l), fontWeight = FontWeight.Bold)
            val detalhes = Formatacao.detalhes(l, campos)
            if (detalhes.isNotEmpty()) {
                Text(detalhes, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Valor e situação ficam à direita, na ordem em que a pessoa os colocou.
        Column(horizontalAlignment = Alignment.End) {
            ligados.filter { it == "valor" || it == "situacao" }.forEach { chave ->
                if (chave == "valor") {
                    // O sinal (+ ou -) e a palavra "Pago"/"Pendente" dizem tudo; a cor não é necessária.
                    Text(Formatacao.valorComSinal(l.valor), fontWeight = FontWeight.Bold)
                } else {
                    Text(Formatacao.situacao(l), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    HorizontalDivider()
}

/** Configurações: quais informações de cada lançamento aparecem e em que ordem. */
@Composable
private fun TelaConfiguracoes(estado: Estado, acoes: Acoes, aoVoltar: () -> Unit) {
    BackHandler(onBack = aoVoltar)
    val campos = estado.config.campos
    val contexto = LocalContext.current
    // O seletor de arquivos é a tela do próprio Android; ao escolher, o app lê o arquivo.
    val seletor = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) acoes.importarConfiguracoes(lerArquivoPequeno(contexto, uri).orEmpty())
    }
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
            item { Titulo("Configurações", grande = true) }
            item { BotaoSecundario("Voltar", aoVoltar) }
            item { Titulo("Informações de cada lançamento") }
            item {
                Text(
                    "Marque as informações que você quer ver em cada lançamento. " +
                        "A ordem abaixo é a ordem dos elementos exibidos."
                )
            }
            itemsIndexed(campos, key = { _, c -> c.chave }) { i, c ->
                LinhaCampo(c, i, campos.size, acoes)
            }
            item { Titulo("Ao filtrar a lista") }
            item {
                OpcaoMarcavel(
                    "Informar a quantidade de lançamentos", estado.config.falarQuantidade,
                    acoes.definirFalarQuantidade,
                )
            }
            item {
                OpcaoMarcavel(
                    "Informar o saldo em valores", estado.config.falarTotal, acoes.definirFalarTotal,
                )
            }
            item { Titulo("Importar") }
            item { Text("Escolha o arquivo de configurações copiado do programa do Windows.") }
            item {
                BotaoSecundario("Importar configurações do Windows", { seletor.launch(arrayOf("*/*")) })
            }
            item { Titulo("Padrão") }
            item { BotaoSecundario("Restaurar o padrão", acoes.restaurarPadrao) }
        }
    }
}

/** Um campo: a caixa para ligar e os dois botões para mudar de lugar, cada um com o nome do campo. */
@Composable
private fun LinhaCampo(c: CampoConfig, posicao: Int, total: Int, acoes: Acoes) {
    val nome = Campos.titulo(c.chave)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OpcaoMarcavel("$nome (posição ${posicao + 1} de $total)", c.visivel) { acoes.alternarCampo(c.chave) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotaoSecundario(
                "Mover $nome para cima", { acoes.moverCampo(c.chave, -1) },
                habilitado = posicao > 0, modificador = Modifier.weight(1f),
            )
            BotaoSecundario(
                "Mover $nome para baixo", { acoes.moverCampo(c.chave, 1) },
                habilitado = posicao < total - 1, modificador = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
    }
}

/** Caixa de seleção com o texto ao lado; a linha inteira é o alvo do toque. */
@Composable
internal fun OpcaoMarcavel(rotulo: String, marcada: Boolean, aoMudar: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = marcada, role = Role.Checkbox, onValueChange = aoMudar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = marcada, onCheckedChange = null)
        Text(rotulo, modifier = Modifier.padding(start = 12.dp))
    }
}
