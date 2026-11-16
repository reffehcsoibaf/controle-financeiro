package br.com.fabioscheffer.controlefinanceiro

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Etapa { INICIO, CONECTADO }

/** O que a IA leu de um documento, esperando o formulário aplicar. O [id] muda a cada leitura. */
data class Leitura(val id: Int, val dados: DadosIA, val acrescentar: Boolean = false, val aviso: String = "")

/** Um documento baixado, esperando a tela abri-lo em outro app. O [id] muda a cada pedido. */
data class ArquivoParaAbrir(val id: Int, val caminho: String, val mime: String)

data class Estado(
    val etapa: Etapa = Etapa.INICIO,
    val email: String = "",
    /** Tudo o que veio do servidor. */
    val todos: List<Lancamento> = emptyList(),
    val filtros: Filtros = Filtros(),
    /** O que a lista mostra: [todos] com os [filtros] aplicados, na ordem escolhida. */
    val lancamentos: List<Lancamento> = emptyList(),
    /** Resumo de [lancamentos], como no Windows. */
    val resumo: Resumo = Resumo(),
    /** O que a pessoa escolheu em Configurações. */
    val config: Configuracoes = Configuracoes(),
    /** As listas de opções por campo: banco, forma, estabelecimento, categoria, credor, devedor... */
    val opcoes: Map<String, List<String>> = emptyMap(),
    /** O formulário de novo lançamento está aberto. */
    val novoAberto: Boolean = false,
    /** Identifica este envio no servidor; só muda depois que o lançamento é criado. */
    val uuidNovo: String = "",
    /** Se não for null, o formulário aberto edita este lançamento em vez de criar um novo. */
    val editandoId: Long? = null,
    /** Leitura de documento pronta para o formulário aplicar; o formulário avisa quando aplicou. */
    val leitura: Leitura? = null,
    /** O que a IA sugeriu para o lançamento em andamento: serve para aprender com as correções. */
    val extracaoIA: DadosIA? = null,
    /** Mensagem para a região ativa: o leitor de tela fala quando ela muda. */
    val aviso: String = "",
    val ocupado: Boolean = false,
    /** O que o painel liberou para esta conta: leitura por IA e documentos. */
    val permissoes: Permissoes = Permissoes(),
    /** Tipo, saldo inicial e limite de cada banco ou cartão configurado. */
    val contas: List<ContaConfig> = emptyList(),
    /** Os arquivos guardados na nuvem; vazio se a conta não tem documentos liberados. */
    val documentos: List<DocumentoGuardado> = emptyList(),
    /** Arquivos já guardados que o lançamento em andamento vai ligar a si ao ser salvo. */
    val documentosLidos: List<DocumentoGuardado> = emptyList(),
    val paraAbrir: ArquivoParaAbrir? = null,
    /** O que a IA aprendeu com as correções (carregado ao abrir a tela delas), da mais nova à mais velha. */
    val correcoes: List<Correcao> = emptyList(),
    /** Planilha lida e à espera da confirmação para importar. */
    val importacao: Importacao? = null,
    /** Sem conexão: a lista vem da cópia guardada no celular. */
    val offline: Boolean = false,
    /** Alterações feitas sem internet, esperando para serem enviadas, da mais antiga à mais nova. */
    val fila: List<Operacao> = emptyList(),
    /** Alterações que o servidor não aceitou e que a pessoa precisa decidir. */
    val conflitos: List<Conflito> = emptyList(),
    /** Quando a cópia do celular foi atualizada pela última vez (aaaa-mm-ddThh:mm:ss). */
    val salvoEm: String = "",
) {
    /** Diz quando a lista não mostra tudo; null se nada está escondido. */
    val avisoDeFiltro: String?
        get() = Formatacao.avisoDeFiltro(lancamentos.size, todos.size, Regras.temFiltroDaJanela(filtros))
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val loja = SessaoStore(app)
    private val preferencias = Preferencias(app)
    private val api = SupabaseApi()
    private val _estado = MutableStateFlow(estadoInicial())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val armazem = Armazem(app)

    /** A cópia só é guardada depois de haver dados de verdade, para uma tela vazia não apagar a boa. */
    private var copiaPronta = false

    init {
        loja.ler()?.let { sessao ->
            _estado.update {
                it.copy(
                    etapa = Etapa.CONECTADO, email = sessao.email,
                    fila = armazem.lerFila(), conflitos = armazem.lerConflitos(),
                )
            }
            carregar()
        }
        guardarCopiaSempre()
        sincronizarACadaCincoMinutos()
    }

    /** Toda mudança nos dados vai para a cópia do celular, um instante depois, sem travar a tela. */
    @OptIn(FlowPreview::class)
    private fun guardarCopiaSempre() {
        viewModelScope.launch {
            _estado
                .map { listOf(it.todos, it.opcoes, it.contas, it.permissoes, it.documentos, it.salvoEm) }
                .distinctUntilChanged()
                .debounce(700)
                .collect {
                    val e = _estado.value
                    val usuario = loja.ler()?.userId
                    if (copiaPronta && e.etapa == Etapa.CONECTADO && usuario != null && e.salvoEm.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            try {
                                armazem.salvarCopia(Copia(usuario, e.salvoEm, e.todos, e.opcoes, e.contas, e.permissoes, e.documentos))
                            } catch (ex: Exception) {
                                // A cópia é um extra: se não der para guardar agora, tenta na próxima mudança.
                            }
                        }
                    }
                }
        }
    }

    /** Enquanto o app está aberto, tenta enviar o que ficou esperando e atualizar, sem atrapalhar a pessoa. */
    private fun sincronizarACadaCincoMinutos() {
        viewModelScope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                val e = _estado.value
                if (e.etapa == Etapa.CONECTADO && !e.ocupado && !e.novoAberto && (e.fila.isNotEmpty() || e.offline)) carregar()
            }
        }
    }

    private fun estadoInicial(aviso: String = "") = Estado(
        filtros = preferencias.lerFiltros(),
        config = preferencias.lerConfiguracoes(),
        aviso = aviso,
    )

    /** Devolve o endereço do login com Google para a tela abrir no navegador. */
    fun prepararLogin(): String {
        val par = Pkce.novo()
        loja.guardarVerificador(par.verificador)
        _estado.update { it.copy(aviso = "Abrindo o login com Google no navegador.") }
        return api.urlLogin(par.desafio)
    }

    fun receberRetorno(uri: Uri) {
        val erro = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        val codigo = uri.getQueryParameter("code")
        if (!erro.isNullOrBlank() || codigo.isNullOrBlank()) {
            avisar("O login não foi concluído. Toque em Entrar com Google para tentar de novo.")
            return
        }
        val verificador = loja.tomarVerificador()
        if (verificador == null) {
            avisar("O login foi interrompido. Toque em Entrar com Google para tentar de novo.")
            return
        }
        trabalhar("Entrando.") {
            val sessao = api.trocarCodigo(codigo, verificador)
            loja.salvar(sessao)
            _estado.update { it.copy(etapa = Etapa.CONECTADO, email = sessao.email) }
            baixar(sessao)
        }
    }

    /** Envia o que ficou esperando e atualiza a lista; sem internet, abre a cópia guardada no celular. */
    fun carregar() {
        trabalhar("Carregando os lançamentos.") {
            try {
                val sessao = tokenValido()
                enviarFila(sessao)
                baixar(sessao)
            } catch (e: SemConexao) {
                abrirDaCopia()
            }
        }
    }

    private fun abrirDaCopia() {
        val e = _estado.value
        if (e.todos.isNotEmpty() || e.salvoEm.isNotEmpty()) {
            // Já há dados nesta sessão: continua com eles.
            _estado.update { it.copy(offline = true, aviso = avisoOffline(it)) }
            return
        }
        val usuario = loja.ler()?.userId
        val copia = armazem.lerCopia()?.takeIf { it.usuario == usuario }
        if (copia == null) {
            _estado.update { it.copy(offline = true, aviso = "Sem conexão com a internet, e ainda não há dados guardados neste celular.") }
            return
        }
        copiaPronta = true
        _estado.update {
            recalcular(
                it.copy(
                    todos = copia.todos, opcoes = copia.opcoes, contas = copia.contas, permissoes = copia.permissoes,
                    documentos = copia.documentos, salvoEm = copia.salvoEm, offline = true,
                ),
            ).let { novo -> novo.copy(aviso = avisoOffline(novo)) }
        }
    }

    private fun avisoOffline(e: Estado): String =
        "Sem conexão com a internet. Mostrando os dados guardados em ${Offline.dataFalada(e.salvoEm)}." +
            (if (e.fila.isEmpty()) "" else if (e.fila.size == 1) " 1 alteração aguarda envio." else " ${e.fila.size} alterações aguardam envio.")

    // ---- alterações sem internet ----

    private fun novoTemporario(): Long = minOf(-1L, _estado.value.fila.flatMap { op ->
        if (op is Operacao.Criar) op.idsTemporarios else emptyList()
    }.minOrNull() ?: -1L) - 1

    private fun mudarFila(nova: List<Operacao>) {
        armazem.salvarFila(nova)
        _estado.update { it.copy(fila = nova) }
    }

    private fun mudarConflitos(nova: List<Conflito>) {
        armazem.salvarConflitos(nova)
        _estado.update { it.copy(conflitos = nova) }
    }

    /** Guarda a alteração para enviar depois e já a mostra na lista. */
    private fun enfileirar(op: Operacao) {
        val nova = _estado.value.fila + op
        armazem.salvarFila(nova)
        _estado.update { e ->
            recalcular(e.copy(fila = nova, todos = Offline.aplicar(e.todos, listOf(op)), offline = true)).copy(
                aviso = "Sem conexão. A alteração foi guardada neste celular (${op.oQueFaz.lowercase()}) e será enviada quando a conexão voltar.",
            )
        }
    }

    private fun rotulo(l: Lancamento) = Formatacao.fala(l, _estado.value.config.campos)

    private fun lancamentoDe(op: Operacao): Long? = when (op) {
        is Operacao.Pago -> op.lancamentoId
        is Operacao.Excluir -> op.lancamentoId
        is Operacao.Editar -> op.lancamentoId
        is Operacao.Criar -> null
    }

    /** Resultado de enviar uma alteração: [motivo] vazio se o servidor aceitou; [versao] é a nova versão do lançamento. */
    private class Envio(val motivo: String?, val versao: String? = null)

    private fun executar(sessao: Sessao, op: Operacao): Envio = when (op) {
        is Operacao.Criar -> {
            op.dados.forEachIndexed { n, d -> api.criarLancamento(sessao, d, Offline.codigoDaParcela(op.uuid, n)) }
            Envio(null)
        }
        is Operacao.Pago -> api.marcarPago(sessao, op.lancamentoId, op.pago, op.base)
            ?.let { Envio(null, it) } ?: Envio("O lançamento foi alterado ou excluído em outro lugar.")
        is Operacao.Excluir -> if (api.excluirLancamento(sessao, op.lancamentoId, op.base)) Envio(null)
        else Envio("O lançamento foi alterado ou excluído em outro lugar.")
        is Operacao.Editar -> api.atualizarLancamento(sessao, op.lancamentoId, op.base, op.dados)
            ?.let { Envio(null, it) } ?: Envio("O lançamento foi alterado ou excluído em outro lugar.")
    }

    /**
     * Envia as alterações guardadas, na ordem. O que o servidor não aceita vira conflito, para a pessoa
     * decidir. Sem internet, para onde parou e a fila fica como está (a exceção sobe).
     */
    private fun enviarFila(sessao: Sessao) {
        var fila = _estado.value.fila
        if (fila.isEmpty()) return
        val conflitos = _estado.value.conflitos.toMutableList()
        try {
            while (fila.isNotEmpty()) {
                val op = fila.first()
                val envio = try {
                    executar(sessao, op)
                } catch (e: ErroServidor) {
                    Envio("O servidor recusou: ${e.message}")
                }
                fila = fila.drop(1)
                if (envio.motivo != null) {
                    conflitos += Conflito(op, envio.motivo)
                } else if (envio.versao != null) {
                    // O envio mudou a versão do lançamento: as próximas alterações dele partem da nova.
                    val id = lancamentoDe(op)
                    fila = fila.map { if (lancamentoDe(it) == id) Offline.comBase(it, envio.versao) else it }
                }
                armazem.salvarFila(fila)
                armazem.salvarConflitos(conflitos)
            }
        } finally {
            _estado.update { it.copy(fila = fila, conflitos = conflitos.toList()) }
        }
    }

    /** O botão Sincronizar agora. */
    fun sincronizar() = carregar()

    /** Manter a alteração: parte da versão que o servidor tem agora e tenta de novo. */
    fun manterAlteracao(opId: String) {
        val conflito = _estado.value.conflitos.firstOrNull { it.operacao.id == opId } ?: return
        trabalhar("Enviando a sua alteração.") {
            val sessao = tokenValido()
            baixar(sessao)
            val op = conflito.operacao
            val id = lancamentoDe(op)
            val atual = _estado.value.todos.firstOrNull { it.id == id }
            val outros = _estado.value.conflitos.filter { it.operacao.id != opId }
            if (atual == null) {
                mudarConflitos(outros)
                return@trabalhar avisar("O lançamento não existe mais no servidor, então a sua alteração foi descartada.")
            }
            val envio = try {
                executar(sessao, Offline.comBase(op, atual.updatedAt))
            } catch (e: ErroServidor) {
                Envio("O servidor recusou: ${e.message}")
            }
            mudarConflitos(outros)
            baixar(sessao)
            avisar(if (envio.motivo == null) "A sua alteração foi enviada." else "Não foi possível enviar a sua alteração: ${envio.motivo}")
        }
    }

    /** Fica com o que está no servidor: a alteração guardada neste celular é descartada. */
    fun descartarConflito(opId: String) {
        mudarConflitos(_estado.value.conflitos.filter { it.operacao.id != opId })
        avisar("A alteração foi descartada. Vale o que está no servidor.")
    }

    /** Descarta uma alteração que ainda não foi enviada. Precisa de internet para a lista voltar ao que o servidor tem. */
    fun descartarPendente(opId: String) {
        val antes = _estado.value.fila
        if (antes.none { it.id == opId }) return
        trabalhar("Descartando a alteração.") {
            val sessao = tokenValido()
            mudarFila(antes.filter { it.id != opId })
            try {
                baixar(sessao)
            } catch (e: SemConexao) {
                mudarFila(antes)
                throw e
            }
            avisar("A alteração foi descartada.")
        }
    }

    /**
     * Grava no servidor e só então muda na tela. Se o lançamento mudou em outro lugar desde que foi
     * lido, nada é gravado: o app recarrega a lista e avisa, em vez de sobrescrever. Sem internet, a
     * alteração fica guardada e é enviada depois.
     */
    fun marcarPago(id: Long, pago: Boolean) {
        val atual = _estado.value.todos.firstOrNull { it.id == id } ?: return
        if (id < 0) return avisar("Este lançamento ainda não foi enviado. Espere a sincronização para alterá-lo.")
        trabalhar(if (pago) "Marcando como pago." else "Marcando como pendente.") {
          try {
            val sessao = tokenValido()
            val novo = api.marcarPago(sessao, id, pago, atual.updatedAt)
            if (novo == null) {
                baixar(sessao)
                avisar(
                    "Este lançamento foi alterado em outro lugar, então nada foi mudado. " +
                        "A lista foi atualizada. Confira e tente de novo."
                )
            } else {
                _estado.update { e ->
                    val todos = e.todos.map { if (it.id == id) it.copy(pago = pago, updatedAt = novo) else it }
                    recalcular(e.copy(todos = todos))
                        .copy(aviso = if (pago) "Marcado como pago." else "Marcado como pendente.")
                }
            }
          } catch (e: SemConexao) {
              enfileirar(Operacao.Pago(UUID.randomUUID().toString(), rotulo(atual), id, pago, atual.updatedAt))
          }
        }
    }

    fun abrirNovo() {
        _estado.update {
            it.copy(
                novoAberto = true, uuidNovo = UUID.randomUUID().toString(), aviso = "",
                leitura = null, extracaoIA = null, documentosLidos = emptyList(),
            )
        }
    }

    // ---- leitura de documentos pela IA ----

    private var sequenciaDeLeitura = 0

    /** Lê uma foto, PDF, TXT ou XML escolhido no celular e entrega o resultado ao formulário. */
    fun lerDocumento(uri: Uri, acrescentar: Boolean = false) =
        lerComIA(acrescentar) { Documentos.preparar(getApplication<Application>().contentResolver, uri) }

    /** Outra foto da mesma nota: os itens lidos são somados aos que já estão no formulário. */
    fun acrescentarDocumento(uri: Uri) = lerDocumento(uri, acrescentar = true)

    /** Lê um texto copiado (por exemplo a mensagem de um comprovante). */
    fun lerTexto(texto: String) {
        if (texto.isBlank()) return avisar("Não há texto para ler.")
        lerComIA { Documentos.doTexto(texto) }
    }

    /** Um arquivo ou texto compartilhado com o app abre o Novo lançamento e já lê. */
    fun receberCompartilhado(uri: Uri?, texto: String?) {
        viewModelScope.launch {
            // Ao abrir o app por um compartilhamento, a lista ainda está carregando: espera terminar.
            _estado.first { !it.ocupado }
            if (_estado.value.etapa != Etapa.CONECTADO) {
                return@launch avisar("Entre com o Google antes de compartilhar um documento com o app.")
            }
            abrirNovo()
            if (uri != null) lerDocumento(uri) else if (texto != null) lerTexto(texto)
        }
    }

    private fun lerComIA(acrescentar: Boolean = false, guardar: Boolean = true, preparar: () -> DocumentoPronto) {
        if (!_estado.value.permissoes.ia) return avisar("A leitura por IA está desligada para a sua conta.")
        if (_estado.value.ocupado) return avisar("Aguarde terminar o que está em andamento e tente de novo.")
        trabalhar("Lendo o documento com IA. Isso pode levar alguns segundos.") {
            val documento = preparar()
            val sessao = tokenValido()
            // As correções passadas são só exemplos: sem elas a leitura ainda funciona.
            val correcoes = try {
                api.baixarCorrecoes(sessao)
            } catch (e: ErroServidor) {
                emptyList()
            }
            val dados = api.lerDocumento(sessao, documento, correcoes)
            // Como no Windows, o arquivo lido também é guardado e ligado ao lançamento, a menos que a
            // pessoa diga o contrário. Se guardar falhar, a leitura vale do mesmo jeito.
            var guardado: DocumentoGuardado? = null
            var falhou = false
            if (guardar && _estado.value.permissoes.documentos && documento.arquivo != null) {
                try {
                    guardado = api.guardarDocumento(sessao, documento.nome, documento.mimeGuardado ?: documento.mime, documento.arquivo)
                } catch (e: SessaoExpirada) {
                    throw e
                } catch (e: Exception) {
                    falhou = true
                }
            }
            _estado.update {
                it.copy(
                    leitura = Leitura(
                        ++sequenciaDeLeitura, dados, acrescentar,
                        when {
                            guardado != null -> "O arquivo foi guardado e será vinculado a este lançamento."
                            falhou -> "Não foi possível guardar o arquivo; só a leitura foi feita."
                            !guardar -> "O documento guardado será vinculado a este lançamento."
                            else -> ""
                        },
                    ),
                    // Ao acrescentar, vale o que a primeira leitura sugeriu: é com ela que se aprende.
                    extracaoIA = if (acrescentar) it.extracaoIA ?: dados else dados,
                    documentos = if (guardado != null) it.documentos + guardado else it.documentos,
                    documentosLidos = if (guardado != null) it.documentosLidos + guardado else it.documentosLidos,
                    aviso = "Documento lido.",
                )
            }
        }
    }

    /** O arquivo continua guardado, só não fica ligado a este lançamento. */
    fun naoVincularDocumentos() {
        _estado.update { it.copy(documentosLidos = emptyList(), aviso = "Este lançamento será salvo sem vínculo com o documento.") }
    }

    // ---- documentos guardados ----

    /** Guarda na nuvem um arquivo qualquer do celular. */
    fun guardarArquivo(uri: Uri) {
        if (!_estado.value.permissoes.documentos) return avisar("O envio de documentos está desligado para a sua conta.")
        trabalhar("Guardando o arquivo.") {
            val lido = Documentos.lerParaGuardar(getApplication<Application>().contentResolver, uri)
            val guardado = api.guardarDocumento(tokenValido(), lido.nome, lido.mime, lido.bytes)
            _estado.update { it.copy(documentos = it.documentos + guardado, aviso = "Arquivo guardado: ${lido.nome}.") }
        }
    }

    private fun arquivoLocal(sessao: Sessao, d: DocumentoGuardado): java.io.File {
        val pasta = java.io.File(getApplication<Application>().cacheDir, "documentos/${d.id}").apply { mkdirs() }
        val arquivo = java.io.File(pasta, d.nome.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (!arquivo.exists() || arquivo.length() == 0L) arquivo.writeBytes(api.baixarArquivo(sessao, d.caminho))
        return arquivo
    }

    private fun documentoPorId(id: String) = _estado.value.documentos.firstOrNull { it.id == id }

    /** Baixa o documento e pede à tela para abri-lo em outro app. */
    fun abrirDocumento(id: String) {
        val d = documentoPorId(id) ?: return
        trabalhar("Baixando o documento, aguarde.") {
            val arquivo = arquivoLocal(tokenValido(), d)
            val mime = d.mime ?: "application/octet-stream"
            _estado.update { it.copy(paraAbrir = ArquivoParaAbrir(++sequenciaDeLeitura, arquivo.absolutePath, mime), aviso = "Abrindo ${d.nome}.") }
        }
    }

    fun arquivoAberto() {
        _estado.update { it.copy(paraAbrir = null) }
    }

    /** Grava uma cópia do documento no lugar escolhido pela pessoa. */
    fun salvarCopia(id: String, destino: Uri) {
        val d = documentoPorId(id) ?: return
        trabalhar("Salvando a cópia.") {
            val arquivo = arquivoLocal(tokenValido(), d)
            getApplication<Application>().contentResolver.openOutputStream(destino)?.use { it.write(arquivo.readBytes()) }
                ?: throw ErroDocumento("Não foi possível gravar a cópia.")
            avisar("Cópia salva.")
        }
    }

    /** Liga o documento a um lançamento, ou desliga se [lancamentoId] for null. */
    fun vincularDocumento(id: String, lancamentoId: Long?) {
        val d = documentoPorId(id) ?: return
        trabalhar(if (lancamentoId == null) "Desvinculando." else "Vinculando.") {
            api.vincularDocumento(tokenValido(), id, lancamentoId)
            _estado.update { e ->
                e.copy(
                    documentos = e.documentos.map { if (it.id == id) it.copy(lancamentoId = lancamentoId) else it },
                    aviso = if (lancamentoId == null) "Documento desvinculado." else "Documento vinculado.",
                )
            }
        }
    }

    fun excluirDocumento(id: String) {
        val d = documentoPorId(id) ?: return
        trabalhar("Excluindo o documento.") {
            api.excluirDocumento(tokenValido(), d)
            java.io.File(getApplication<Application>().cacheDir, "documentos/${d.id}").deleteRecursively()
            _estado.update { e -> e.copy(documentos = e.documentos.filter { it.id != id }, aviso = "Documento excluído.") }
        }
    }

    /** Lê com a IA um documento já guardado e abre o Novo lançamento ligado a ele, como no Windows. */
    fun analisarDocumento(id: String) {
        val d = documentoPorId(id) ?: return
        if (!_estado.value.permissoes.ia) return avisar("A leitura por IA está desligada para a sua conta.")
        if (_estado.value.ocupado) return avisar("Aguarde terminar o que está em andamento e tente de novo.")
        abrirNovo()
        _estado.update { it.copy(documentosLidos = listOf(d)) }
        lerComIA(guardar = false) {
            val sessao = tokenValido()
            Documentos.preparar(getApplication<Application>().contentResolver, android.net.Uri.fromFile(arquivoLocal(sessao, d)))
        }
    }

    /** O formulário já usou o que a IA leu. */
    fun leituraAplicada() {
        _estado.update { it.copy(leitura = null) }
    }

    /** Acrescenta às listas os textos novos digitados (campo, texto), como no Windows. */
    fun cadastrarTextos(textos: List<Pair<String, String>>) {
        if (textos.isEmpty()) return
        trabalhar("Cadastrando nas listas.") {
            val sessao = tokenValido()
            textos.forEach { (campo, valor) -> api.cadastrarOpcao(sessao, campo, valor) }
            _estado.update { e ->
                val opcoes = e.opcoes.toMutableMap()
                for ((campo, valor) in textos) {
                    opcoes[campo] = (opcoes[campo].orEmpty() + valor).distinct().sortedBy { Regras.semAcentos(it) }
                }
                e.copy(
                    opcoes = opcoes,
                    aviso = if (textos.size == 1) "1 texto cadastrado nas listas." else "${textos.size} textos cadastrados nas listas.",
                )
            }
        }
    }

    // ---- Excel ----

    /** Lê a planilha escolhida e mostra quantos lançamentos são novos; só grava em [confirmarImportacao]. */
    fun lerPlanilha(nome: String, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            avisar("Não foi possível abrir o arquivo.")
            return
        }
        trabalhar("Lendo a planilha.") {
            val lidos = Planilha.ler(bytes)
            if (lidos.isEmpty()) throw ErroDocumento("A planilha não tem lançamentos.")
            val (novos, repetidos) = Planilha.novosERepetidos(lidos, _estado.value.todos)
            _estado.update {
                it.copy(
                    importacao = Importacao(nome, novos, repetidos),
                    aviso = when {
                        novos.isEmpty() -> "Nenhum lançamento novo: os ${lidos.size} da planilha já existem."
                        else -> "${novos.size} lançamentos novos e $repetidos que já existem."
                    },
                )
            }
        }
    }

    fun cancelarImportacao() = _estado.update { it.copy(importacao = null, aviso = "") }

    fun confirmarImportacao() {
        val imp = _estado.value.importacao ?: return
        if (imp.novos.isEmpty()) {
            cancelarImportacao()
            return
        }
        trabalhar("Importando ${imp.novos.size} lançamentos. Pode demorar um pouco.") {
            val sessao = tokenValido()
            var gravados = 0
            try {
                for (d in imp.novos) {
                    api.criarLancamento(sessao, d, java.util.UUID.randomUUID().toString())
                    gravados++
                }
            } finally {
                // Mesmo se parar no meio, o que já subiu entra na lista e as opções novas são cadastradas.
                val existentes = _estado.value.opcoes
                for ((campo, valores) in Planilha.opcoesDe(imp.novos.take(gravados))) {
                    for (v in valores) {
                        if (v !in existentes[campo].orEmpty()) {
                            try {
                                api.cadastrarOpcao(sessao, campo, v)
                            } catch (e: ErroServidor) {
                                // As opções são um extra: o lançamento já está gravado.
                            }
                        }
                    }
                }
                baixar(sessao)
                _estado.update { it.copy(importacao = null) }
            }
            avisar(
                "${if (gravados == 1) "1 importado" else "$gravados importados"}, " +
                    "${imp.repetidos} já existia${if (imp.repetidos == 1) "" else "m"}.",
            )
        }
    }

    // ---- duplicados ----

    /** Exclui as cópias (o mais antigo de cada grupo fica), uma a uma, com a checagem de versão. */
    fun limparDuplicados() {
        val copias = Duplicados.copias(_estado.value.todos)
        if (copias.isEmpty()) return
        trabalhar("Excluindo as cópias.") {
            val sessao = tokenValido()
            var apagados = 0
            for (c in copias) {
                if (api.excluirLancamento(sessao, c.id, c.updatedAt)) apagados++
            }
            // Excluir um lançamento pode mexer no vínculo de outro: recarrega para ficar certo.
            baixar(sessao)
            val faltou = copias.size - apagados
            avisar(
                (if (apagados == 1) "1 cópia excluída." else "$apagados cópias excluídas.") +
                    if (faltou > 0) " $faltou não foram excluídas porque mudaram em outro lugar. Confira e tente de novo." else "",
            )
        }
    }

    // ---- gerenciar listas ----

    private fun ordenar(valores: List<String>) = valores.distinct().sortedBy { Regras.semAcentos(it) }

    /** Acrescenta uma opção à lista. Devolve o aviso de erro na hora, ou null se vai gravar. */
    fun adicionarOpcao(campo: String, texto: String): String? {
        val valor = texto.trim()
        if (valor.isEmpty()) return "Digite a nova opção."
        if (valor in _estado.value.opcoes[campo].orEmpty()) return "Essa opção já existe: $valor."
        trabalhar("Adicionando a opção.") {
            api.cadastrarOpcao(tokenValido(), campo, valor)
            _estado.update { e ->
                e.copy(
                    opcoes = e.opcoes + (campo to ordenar(e.opcoes[campo].orEmpty() + valor)),
                    aviso = "Opção $valor adicionada.",
                )
            }
        }
        return null
    }

    /** Renomear troca o texto em todos os lançamentos que usam a opção; por isso recarrega tudo. */
    fun renomearOpcao(campo: String, antigo: String, novo: String): String? {
        val valor = novo.trim()
        if (valor.isEmpty() || valor == antigo) return "Digite um nome diferente."
        if (valor in _estado.value.opcoes[campo].orEmpty()) return "Já existe uma opção chamada $valor."
        trabalhar("Renomeando e atualizando os lançamentos. Pode demorar um pouco.") {
            val sessao = tokenValido()
            api.renomearOpcao(sessao, campo, antigo, valor)
            baixar(sessao)
            avisar("Renomeada para $valor. Lançamentos atualizados.")
        }
        return null
    }

    fun excluirOpcao(campo: String, valor: String) {
        trabalhar("Removendo a opção.") {
            api.excluirOpcao(tokenValido(), campo, valor)
            _estado.update { e ->
                e.copy(
                    opcoes = e.opcoes + (campo to e.opcoes[campo].orEmpty().filter { it != valor }),
                    contas = if (campo == "banco") e.contas.filter { it.nome != valor } else e.contas,
                    aviso = "Opção $valor removida.",
                )
            }
        }
    }

    // ---- correções aprendidas pela IA ----

    fun carregarCorrecoes() {
        trabalhar("Carregando as correções.") {
            val lista = api.baixarCorrecoes(tokenValido())
            _estado.update { it.copy(correcoes = lista, aviso = "") }
        }
    }

    fun editarCorrecao(id: Long, errado: String, correto: String, contexto: String): String? {
        val e = errado.trim()
        val c = correto.trim()
        if (e.isEmpty() || c.isEmpty() || e == c) return "Preencha o valor errado e o correto, diferentes entre si."
        val ctx = contexto.trim().ifEmpty { null }
        trabalhar("Salvando a correção.") {
            api.editarCorrecao(tokenValido(), id, e, c, ctx)
            _estado.update { s ->
                s.copy(
                    correcoes = s.correcoes.map { if (it.id == id) it.copy(valorErrado = e, valorCorreto = c, contexto = ctx) else it },
                    aviso = "Correção atualizada.",
                )
            }
        }
        return null
    }

    fun excluirCorrecao(id: Long) {
        trabalhar("Removendo a correção.") {
            api.excluirCorrecao(tokenValido(), id)
            _estado.update { s -> s.copy(correcoes = s.correcoes.filter { it.id != id }, aviso = "Correção removida.") }
        }
    }

    fun apagarCorrecoes() {
        trabalhar("Apagando as correções.") {
            api.apagarCorrecoes(tokenValido())
            _estado.update { it.copy(correcoes = emptyList(), aviso = "Correções apagadas.") }
        }
    }

    // ---- saldos e limites ----

    /** Configura (ou troca a configuração de) um banco ou cartão. */
    fun salvarConta(conta: ContaConfig) {
        trabalhar("Salvando a configuração.") {
            api.salvarConta(tokenValido(), conta)
            _estado.update { e ->
                e.copy(
                    contas = e.contas.filter { it.nome != conta.nome } + conta,
                    aviso = "${conta.nome} configurada.",
                )
            }
        }
    }

    /** Remove só a configuração: o nome continua nas listas, só deixa de ter saldo ou limite calculado. */
    fun removerConta(nome: String) {
        trabalhar("Removendo a configuração.") {
            api.removerConta(tokenValido(), nome)
            _estado.update { e -> e.copy(contas = e.contas.filter { it.nome != nome }, aviso = "Configuração removida.") }
        }
    }

    /** Mostra na lista só os lançamentos desta conta: tudo volta ao início, menos a ordem (como no Windows). */
    fun verLancamentosDaConta(nome: String) {
        preferencias.salvarTipo("")
        preferencias.salvarSituacao("")
        mudarFiltros { Filtros(ordem = it.ordem, banco = nome) }
    }

    fun abrirEdicao(id: Long) {
        if (_estado.value.todos.none { it.id == id }) return
        _estado.update { it.copy(novoAberto = true, editandoId = id, uuidNovo = "", aviso = "") }
    }

    /**
     * Exclui no servidor e só então tira da tela. Se o lançamento mudou em outro lugar, nada é
     * apagado: a lista é recarregada e o app avisa. Com [comVinculado], leva junto o lançamento ligado.
     */
    fun excluirLancamento(id: Long, comVinculado: Boolean) {
        val atual = _estado.value.todos.firstOrNull { it.id == id } ?: return
        val par = atual.vinculadoId?.let { v -> _estado.value.todos.firstOrNull { it.id == v } }
        val alvos = if (comVinculado && par != null) listOf(atual, par) else listOf(atual)
        if (alvos.any { it.id < 0 }) return avisar("Este lançamento ainda não foi enviado. Espere a sincronização para alterá-lo.")
        val aoFicarSemInternet = {
            if (alvos.size == 1 && par == null) {
                enfileirar(Operacao.Excluir(UUID.randomUUID().toString(), rotulo(atual), id, atual.updatedAt))
            } else {
                avisar("Sem conexão com a internet. Excluir dois lançamentos vinculados precisa de internet.")
            }
        }
        trabalhar("Excluindo.", semInternet = aoFicarSemInternet) {
            val sessao = tokenValido()
            val apagados = ArrayList<Long>()
            for (a in alvos) {
                if (!api.excluirLancamento(sessao, a.id, a.updatedAt)) break
                apagados += a.id
            }
            val completo = apagados.size == alvos.size
            // Apagar um desliga o vínculo do outro no servidor, e isso muda a versão dele: recarrega.
            if (!completo || (par != null && !comVinculado)) baixar(sessao)
            _estado.update { e ->
                // Os documentos do lançamento apagado ficam guardados, sem lançamento (o servidor desliga).
                val soltos = e.documentos.map { if (it.lancamentoId in apagados) it.copy(lancamentoId = null) else it }
                val depois = recalcular(e.copy(todos = e.todos.filter { it.id !in apagados }, documentos = soltos))
                depois.copy(
                    aviso = when {
                        !completo -> (if (apagados.isEmpty()) "" else "Só o primeiro foi excluído. ") +
                            "Um lançamento foi alterado em outro lugar, então ele não foi excluído. " +
                            "A lista foi atualizada. Confira e tente de novo."
                        alvos.size > 1 -> "Excluídos os dois lançamentos."
                        else -> "Lançamento excluído: ${Formatacao.fala(atual, e.config.campos)}."
                    },
                )
            }
        }
    }

    fun fecharNovo() {
        _estado.update {
            it.copy(
                novoAberto = false, editandoId = null, aviso = "", leitura = null, extracaoIA = null,
                documentosLidos = emptyList(),
            )
        }
    }

    /**
     * Grava as mudanças de um lançamento. Se ele mudou em outro lugar desde que foi lido, nada é
     * gravado: o formulário fecha, a lista é recarregada e o app avisa, em vez de sobrescrever.
     */
    /** O que a consolidação de lançamentos antigos fez. */
    private class Consolidacao(val apagados: Set<Long>, val docsMovidos: Set<String>, val falhas: Int) {
        fun texto(): String = when {
            apagados.isEmpty() && falhas == 0 -> ""
            else -> (if (apagados.isEmpty()) "" else if (apagados.size == 1) " 1 lançamento antigo consolidado." else " ${apagados.size} lançamentos antigos consolidados.") +
                (if (falhas > 0) " $falhas não foram excluídos porque mudaram em outro lugar; confira e exclua à mão se quiser." else "")
        }
    }

    /**
     * Depois de salvar o lançamento novo com os antigos como itens: leva os documentos deles para o novo
     * e exclui os originais, como o Windows. Se mover um documento falhar, o original fica (nada se perde).
     */
    private fun consolidarOriginais(sessao: Sessao, novoId: Long, ids: List<Long>): Consolidacao {
        val apagados = HashSet<Long>()
        val movidos = HashSet<String>()
        var falhas = 0
        for (oid in ids.distinct().filter { it != novoId }) {
            val o = _estado.value.todos.firstOrNull { it.id == oid } ?: continue
            try {
                val docs = _estado.value.documentos.filter { it.lancamentoId == oid }
                for (d in docs) {
                    api.vincularDocumento(sessao, d.id, novoId)
                    movidos += d.id
                }
                if (api.excluirLancamento(sessao, oid, o.updatedAt)) apagados += oid else falhas++
            } catch (e: SessaoExpirada) {
                throw e
            } catch (e: Exception) {
                falhas++
            }
        }
        return Consolidacao(apagados, movidos, falhas)
    }

    private fun salvarEdicao(id: Long, entrada: EntradaNovo) {
        val original = _estado.value.todos.firstOrNull { it.id == id } ?: return fecharNovo()
        if (id < 0) return avisar("Este lançamento ainda não foi enviado. Espere a sincronização para alterá-lo.")
        val dados = when (val r = NovoLancamento.validar(entrada)) {
            is ResultadoNovo.Erro -> return avisar(r.mensagem)
            is ResultadoNovo.Ok -> r.dados.copy(parcela = original.parcela)
        }
        val aoFicarSemInternet = {
            if (entrada.consolidar.isNotEmpty()) {
                avisar("Sem conexão com a internet. Consolidar lançamentos antigos precisa de internet.")
            } else {
                enfileirar(Operacao.Editar(UUID.randomUUID().toString(), rotulo(original), id, original.updatedAt, dados))
                _estado.update { it.copy(novoAberto = false, editandoId = null) }
            }
        }
        trabalhar("Salvando as alterações.", semInternet = aoFicarSemInternet) {
            val sessao = tokenValido()
            val novo = api.atualizarLancamento(sessao, id, original.updatedAt, dados)
            if (novo == null) {
                baixar(sessao)
                _estado.update {
                    it.copy(
                        novoAberto = false, editandoId = null,
                        aviso = "Este lançamento foi alterado em outro lugar, então nada foi mudado. " +
                            "A lista foi atualizada. Abra o lançamento de novo e refaça a alteração.",
                    )
                }
            } else {
                val editado = Lancamento(
                    id = id, dataOrigem = dados.dataOrigem, dataVencimento = dados.dataVencimento,
                    valor = dados.valor, banco = dados.banco, estabelecimento = dados.estabelecimento,
                    forma = dados.forma, parcela = dados.parcela, descricao = dados.descricao,
                    categoria = dados.categoria, pago = dados.pago, credor = dados.credor,
                    devedor = dados.devedor, itens = dados.itens, pagamentos = dados.pagamentos,
                    updatedAt = novo, vinculadoId = original.vinculadoId,
                )
                val cons = consolidarOriginais(sessao, id, entrada.consolidar)
                _estado.update { e ->
                    val lista = e.todos.filter { it.id !in cons.apagados }.map { if (it.id == id) editado else it }
                    val depois = recalcular(
                        e.copy(
                            todos = lista, novoAberto = false, editandoId = null,
                            documentos = e.documentos.map { d -> if (d.id in cons.docsMovidos) d.copy(lancamentoId = id) else d },
                        )
                    )
                    val aparece = depois.lancamentos.any { it.id == id }
                    depois.copy(
                        aviso = "Lançamento alterado: ${Formatacao.fala(editado, e.config.campos)}." +
                            cons.texto() +
                            if (aparece) "" else " Ele não aparece na lista por causa dos filtros.",
                    )
                }
            }
        }
    }

    /**
     * Confere os dados como o Windows confere e grava no servidor. Só fecha o formulário depois que
     * o servidor aceita; se falhar, o formulário continua aberto, com o que foi digitado, e o mesmo
     * código de envio, para uma nova tentativa não duplicar o lançamento.
     */
    fun criarLancamento(entrada: EntradaNovo) {
        _estado.value.editandoId?.let { return salvarEdicao(it, entrada) }
        val pronto = when (val r = NovoLancamento.validar(entrada)) {
            is ResultadoNovo.Erro -> return avisar(r.mensagem)
            is ResultadoNovo.Ok -> r
        }
        val todos = pronto.todos
        val espelho = pronto.espelho
        val uuid = _estado.value.uuidNovo.ifEmpty { UUID.randomUUID().toString() }
        val aoFicarSemInternet = {
            if (espelho != null || entrada.consolidar.isNotEmpty() || _estado.value.documentosLidos.isNotEmpty()) {
                avisar("Sem conexão com a internet. Pagamento de fatura, lançamento com documento e consolidação precisam de internet.")
            } else {
                val primeiro = novoTemporario()
                val ids = List(todos.size) { primeiro - it }
                val rotuloGuardado = if (todos.size == 1) "${Formatacao.reaisFalado(Math.abs(todos[0].valor))}${todos[0].descricao?.let { d -> ", $d" } ?: ""}" else "${todos.size} parcelas"
                enfileirar(Operacao.Criar(UUID.randomUUID().toString(), rotuloGuardado, uuid, todos, ids))
                _estado.update { it.copy(novoAberto = false, uuidNovo = "", leitura = null, extracaoIA = null, documentosLidos = emptyList()) }
            }
        }
        trabalhar(if (todos.size > 1) "Salvando as ${todos.size} parcelas." else "Salvando o lançamento.", semInternet = aoFicarSemInternet) {
            val sessao = tokenValido()
            val criados = ArrayList<Lancamento>()
            for ((n, dados) in todos.withIndex()) {
                // Cada parcela tem o seu código de envio, sempre o mesmo para o mesmo formulário: se
                // a conexão cair no meio, tentar de novo não repete as que já foram criadas.
                val codigo = if (n == 0) uuid else UUID.nameUUIDFromBytes("$uuid#$n".toByteArray()).toString()
                val (id, atualizadoEm) = api.criarLancamento(sessao, dados, codigo)
                criados += Lancamento(
                    id = id, dataOrigem = dados.dataOrigem, dataVencimento = dados.dataVencimento,
                    valor = dados.valor, banco = dados.banco, estabelecimento = dados.estabelecimento,
                    forma = dados.forma, parcela = dados.parcela, descricao = dados.descricao,
                    categoria = dados.categoria, pago = dados.pago, credor = dados.credor,
                    devedor = dados.devedor, itens = dados.itens, pagamentos = dados.pagamentos,
                    updatedAt = atualizadoEm,
                )
            }
            // Pagamento de fatura: cria a entrada no cartão e liga os dois, como no programa Windows.
            // O espelho tem o seu próprio código de envio, então tentar de novo não o duplica.
            if (espelho != null) {
                val codigo = UUID.nameUUIDFromBytes("$uuid#fatura".toByteArray()).toString()
                val (idEspelho, versaoEspelho) = api.criarLancamento(sessao, espelho, codigo)
                val principal = criados[0]
                val (versaoPrincipal, versaoLigada) = api.vincular(sessao, principal.id, idEspelho)
                criados[0] = principal.copy(vinculadoId = idEspelho, updatedAt = versaoPrincipal ?: principal.updatedAt)
                criados += Lancamento(
                    id = idEspelho, dataOrigem = espelho.dataOrigem, dataVencimento = espelho.dataVencimento,
                    valor = espelho.valor, banco = espelho.banco, estabelecimento = null, forma = espelho.forma,
                    parcela = null, descricao = espelho.descricao, categoria = espelho.categoria,
                    pago = espelho.pago, credor = espelho.credor, devedor = espelho.devedor,
                    updatedAt = versaoLigada ?: versaoEspelho, vinculadoId = principal.id,
                )
            }
            // Os arquivos lidos ficam ligados ao lançamento (à primeira parcela, se for parcelado), como no
            // Windows. Se ligar falhar, o lançamento já está salvo e o arquivo continua guardado.
            val lidos = _estado.value.documentosLidos
            var ligados = emptyList<DocumentoGuardado>()
            var naoLigou = false
            for (doc in lidos) {
                try {
                    api.vincularDocumento(sessao, doc.id, criados[0].id)
                    ligados = ligados + doc
                } catch (e: SessaoExpirada) {
                    throw e
                } catch (e: Exception) {
                    naoLigou = true
                }
            }
            // O que a pessoa mudou do que a IA sugeriu vira exemplo para as próximas leituras. Se falhar,
            // o lançamento já está salvo e isto não vale um erro.
            _estado.value.extracaoIA?.let { ia ->
                try {
                    val base = todos.first()
                    LeituraIA.correcoes(ia, base, base.itens.isNotEmpty()).forEach { api.registrarCorrecao(sessao, it) }
                } catch (e: Exception) {
                    // melhor esforço
                }
            }
            val cons = consolidarOriginais(sessao, criados[0].id, entrada.consolidar)
            _estado.update { e ->
                // O reenvio de um lançamento que já tinha chegado devolve o mesmo id: não duplica na lista.
                val ids = criados.map { it.id }.toSet()
                val lista = e.todos.filter { it.id !in ids && it.id !in cons.apagados } + criados
                val depois = recalcular(
                    e.copy(
                        todos = lista, novoAberto = false, uuidNovo = "", leitura = null, extracaoIA = null,
                        documentosLidos = emptyList(),
                        documentos = e.documentos.map { d ->
                            if (ligados.any { it.id == d.id } || d.id in cons.docsMovidos) d.copy(lancamentoId = criados[0].id) else d
                        },
                    )
                )
                val aparecem = depois.lancamentos.count { it.id in ids }
                val resumo = if (espelho != null) {
                    "Lançamento criado: ${Formatacao.fala(criados[0], e.config.campos)}. " +
                        "Entrada criada no cartão ${espelho.banco} para recuperar o limite."
                } else if (criados.size == 1) {
                    "Lançamento criado: ${Formatacao.fala(criados[0], e.config.campos)}."
                } else {
                    "${criados.size} parcelas criadas, total ${Formatacao.reaisFalado(Regras.somar(criados.map { it.valor }))}."
                }
                depois.copy(
                    aviso = resumo + cons.texto() + (if (naoLigou) " Não foi possível vincular o arquivo ao lançamento; ele continua em Documentos." else "") + when {
                        aparecem == criados.size -> ""
                        aparecem == 0 -> if (criados.size == 1) " Ele não aparece na lista por causa dos filtros." else " Elas não aparecem na lista por causa dos filtros."
                        else -> " Algumas não aparecem na lista por causa dos filtros."
                    },
                )
            }
        }
    }

    /** Sair apaga deste celular a cópia dos dados, as alterações não enviadas e os documentos baixados. */
    fun sair() {
        copiaPronta = false
        loja.limpar()
        try {
            armazem.apagarTudo()
            java.io.File(getApplication<Application>().cacheDir, "documentos").deleteRecursively()
        } catch (e: Exception) {
            // Se algo não puder ser apagado agora, o próximo login troca a cópia de qualquer jeito.
        }
        _estado.value = estadoInicial("Você saiu da conta. Os dados deste celular foram apagados.")
    }

    // ---- filtros e ordem ----

    fun definirOrdem(ordem: String) {
        preferencias.salvarOrdem(ordem)
        mudarFiltros { it.copy(ordem = ordem) }
    }

    fun definirTipo(tipo: String) {
        preferencias.salvarTipo(tipo)
        mudarFiltros { it.copy(tipo = tipo) }
    }

    fun definirSituacao(situacao: String) {
        preferencias.salvarSituacao(situacao)
        mudarFiltros { it.copy(status = situacao) }
    }

    /**
     * Aplica a busca e as datas. Devolve o texto do erro se alguma data não existir; nesse caso
     * nada muda.
     */
    fun aplicarBusca(busca: String, origemDe: String, origemAte: String, vencDe: String, vencAte: String): String? {
        val datas = listOf(
            "Data de origem a partir de" to origemDe, "Data de origem até" to origemAte,
            "Vencimento a partir de" to vencDe, "Vencimento até" to vencAte,
        ).map { (nome, texto) ->
            try {
                Regras.lerData(texto).orEmpty()
            } catch (e: IllegalArgumentException) {
                return "$nome: data inválida. Digite dia, mês e ano, como 25102026."
            }
        }
        mudarFiltros {
            it.copy(
                busca = busca.trim(), origemDe = datas[0], origemAte = datas[1],
                vencDe = datas[2], vencAte = datas[3],
            )
        }
        return null
    }

    /** Filtra por banco, forma, estabelecimento, categoria, credor e devedor (texto exato, sem acento nem maiúscula). */
    fun aplicarListas(banco: String, forma: String, estabelecimento: String, categoria: String, credor: String, devedor: String) {
        val todos = _estado.value.todos
        // O texto vale como foi escolhido da lista; digitado diferente só nas maiúsculas/acentos, usa o da lista.
        fun casar(valor: String, de: (Lancamento) -> List<String?>): String {
            val v = valor.trim()
            if (v.isEmpty()) return ""
            val alvo = Regras.semAcentos(v)
            return todos.flatMap(de).firstOrNull { it != null && Regras.semAcentos(it) == alvo } ?: v
        }
        mudarFiltros {
            it.copy(
                banco = casar(banco) { l -> listOf(l.banco) + l.pagamentos.map { p -> p.banco } },
                forma = casar(forma) { l -> listOf(l.forma) + l.pagamentos.map { p -> p.forma } },
                estabelecimento = casar(estabelecimento) { l -> listOf(l.estabelecimento) },
                categoria = casar(categoria) { l -> listOf(l.categoria) + l.itens.map { i -> i.categoria } },
                credor = casar(credor) { l -> listOf(l.credor) },
                devedor = casar(devedor) { l -> listOf(l.devedor) },
            )
        }
    }

    /** Esvazia a busca e as datas. Ordem, tipo e situação ficam como estão. */
    fun limparFiltros() = mudarFiltros {
        it.copy(
            busca = "", origemDe = "", origemAte = "", vencDe = "", vencAte = "",
            banco = "", forma = "", estabelecimento = "", categoria = "", credor = "", devedor = "",
        )
    }

    private fun mudarFiltros(mudar: (Filtros) -> Filtros) {
        _estado.update { recalcular(it.copy(filtros = mudar(it.filtros))).let { e -> e.copy(aviso = mensagemDaLista(e)) } }
    }

    private fun recalcular(e: Estado): Estado {
        val visiveis = Regras.filtrar(e.todos, e.filtros)
        return e.copy(lancamentos = visiveis, resumo = Regras.resumir(visiveis))
    }

    /** O aviso falado depois de carregar ou filtrar; as Configurações dizem o que entra nele. */
    private fun mensagemDaLista(e: Estado): String {
        if (e.todos.isEmpty()) return "Nenhum lançamento encontrado."
        val quantidade = if (e.config.falarQuantidade) {
            e.avisoDeFiltro ?: "Mostrando todos os ${Formatacao.quantidade(e.todos.size)}."
        } else {
            "Lista atualizada."
        }
        val total = if (e.config.falarTotal) " Saldo, ${Formatacao.reaisFalado(e.resumo.total)}." else ""
        return quantidade + total
    }

    // ---- configurações ----

    /** Liga ou desliga um campo. Pelo menos um precisa ficar ligado. */
    fun alternarCampo(chave: String) = mudarConfig { c ->
        val novos = c.campos.map { if (it.chave == chave) it.copy(visivel = !it.visivel) else it }
        if (novos.none { it.visivel }) {
            return@mudarConfig c to "Pelo menos um campo precisa ficar marcado."
        }
        val campo = novos.first { it.chave == chave }
        c.copy(campos = novos) to "${Campos.titulo(chave)} ${if (campo.visivel) "marcado" else "desmarcado"}."
    }

    /** Sobe ([delta] -1) ou desce (+1) um campo na ordem. */
    fun moverCampo(chave: String, delta: Int) = mudarConfig { c ->
        val novos = Campos.mover(c.campos, chave, delta)
        if (novos == c.campos) return@mudarConfig c to ""
        val posicao = novos.indexOfFirst { it.chave == chave } + 1
        c.copy(campos = novos) to "${Campos.titulo(chave)} agora é o campo $posicao de ${novos.size}."
    }

    fun definirFalarQuantidade(valor: Boolean) = mudarConfig { c ->
        c.copy(falarQuantidade = valor) to
            if (valor) "O aviso ao filtrar vai informar a quantidade." else "O aviso ao filtrar não vai informar a quantidade."
    }

    fun definirFalarTotal(valor: Boolean) = mudarConfig { c ->
        c.copy(falarTotal = valor) to
            if (valor) "O aviso ao filtrar vai informar o saldo." else "O aviso ao filtrar não vai informar o saldo."
    }

    fun restaurarPadrao() = mudarConfig { Configuracoes() to "Configurações restauradas ao padrão." }

    /** Recebe o texto do arquivo escolhido (vazio se não deu para ler) e aplica, ou avisa e não muda nada. */
    fun importarConfiguracoes(texto: String) {
        val nova = ImportadorWindows.ler(texto)
        if (nova == null) {
            avisar("Não consegui usar esse arquivo. Escolha o arquivo de configurações copiado do programa do Windows. Nada foi mudado.")
            return
        }
        mudarConfig { nova to "Configurações importadas do Windows." }
    }

    /** Aplica a mudança, guarda no celular e avisa. O texto vazio significa "sem aviso". */
    private fun mudarConfig(mudar: (Configuracoes) -> Pair<Configuracoes, String>) {
        _estado.update { e ->
            val (nova, aviso) = mudar(e.config)
            if (nova != e.config) preferencias.salvarConfiguracoes(nova)
            e.copy(config = nova, aviso = aviso.ifEmpty { e.aviso })
        }
    }

    // ---- dados ----

    private fun baixar(sessao: Sessao) {
        val permissoes = api.conferirAcesso(sessao)
        val contas = api.baixarContas(sessao)
        val todos = api.baixarLancamentos(sessao)
        val opcoes = api.baixarOpcoes(sessao)
        copiaPronta = true
        // Os documentos são um extra: se não vierem, o resto do app segue funcionando.
        val documentos = if (permissoes.documentos) {
            try {
                api.baixarDocumentos(sessao)
            } catch (e: ErroServidor) {
                emptyList()
            }
        } else {
            emptyList()
        }
        _estado.update {
            recalcular(
                it.copy(
                    todos = Offline.aplicar(todos, it.fila), opcoes = opcoes, permissoes = permissoes, contas = contas,
                    documentos = documentos, offline = false, salvoEm = Offline.agora(),
                ),
            )
                .let { e -> e.copy(aviso = mensagemDaLista(e) + (if (it.todos.isEmpty()) "" else avisoDePermissoes(it.permissoes, permissoes))) }
        }
    }

    /** Diz quando o painel ligou ou desligou uma função da conta desde a última leitura. */
    private fun avisoDePermissoes(antes: Permissoes, agora: Permissoes): String {
        if (antes == agora) return ""
        val frases = mutableListOf<String>()
        if (antes.ia != agora.ia) frases += if (agora.ia) "A leitura por IA foi liberada." else "A leitura por IA foi desligada para a sua conta."
        if (antes.documentos != agora.documentos) frases += if (agora.documentos) "Os documentos foram liberados." else "O envio de documentos foi desligado para a sua conta."
        return " " + frases.joinToString(" ")
    }

    private fun tokenValido(): Sessao {
        var sessao = loja.ler() ?: throw SessaoExpirada("sem sessão")
        if (sessao.expiraEm - System.currentTimeMillis() / 1000 < 60) {
            sessao = api.renovar(sessao.refreshToken)
            loja.salvar(sessao)
        }
        return sessao
    }

    private fun avisar(texto: String) = _estado.update { it.copy(aviso = texto) }

    private fun trabalhar(mensagem: String, semInternet: (() -> Unit)? = null, bloco: () -> Unit) {
        if (_estado.value.ocupado) return
        _estado.update { it.copy(ocupado = true, aviso = mensagem) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { bloco() }
            } catch (e: SessaoExpirada) {
                loja.limpar()
                _estado.value = estadoInicial("Sua sessão expirou. Entre de novo com o Google.")
            } catch (e: AcessoNegado) {
                loja.limpar()
                _estado.value = estadoInicial("Esta conta não tem acesso ao Controle Financeiro.")
            } catch (e: SemConexao) {
                if (semInternet != null) semInternet() else avisar("Sem conexão com a internet. Tente de novo mais tarde.")
            } catch (e: ErroLeituraIA) {
                avisar(e.message ?: "Não foi possível ler o documento.")
            } catch (e: ErroDocumento) {
                avisar(e.message ?: "Não foi possível usar este arquivo.")
            } catch (e: ErroServidor) {
                avisar(e.message ?: "O servidor não respondeu direito.")
            } catch (e: Exception) {
                avisar("Algo deu errado: ${e.javaClass.simpleName}.")
            } finally {
                _estado.update { it.copy(ocupado = false) }
            }
        }
    }
}
