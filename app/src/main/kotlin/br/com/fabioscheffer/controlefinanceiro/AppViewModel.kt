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

    init {
        loja.ler()?.let { sessao ->
            _estado.update { it.copy(etapa = Etapa.CONECTADO, email = sessao.email) }
            carregar()
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

    fun carregar() {
        trabalhar("Carregando os lançamentos.") { baixar(tokenValido()) }
    }

    /**
     * Grava no servidor e só então muda na tela. Se o lançamento mudou em outro lugar desde que foi
     * lido, nada é gravado: o app recarrega a lista e avisa, em vez de sobrescrever.
     */
    fun marcarPago(id: Long, pago: Boolean) {
        val atual = _estado.value.todos.firstOrNull { it.id == id } ?: return
        trabalhar(if (pago) "Marcando como pago." else "Marcando como pendente.") {
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
                    guardado = api.guardarDocumento(sessao, documento.nome, documento.mime, documento.arquivo)
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
        trabalhar("Excluindo.") {
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
    private fun salvarEdicao(id: Long, entrada: EntradaNovo) {
        val original = _estado.value.todos.firstOrNull { it.id == id } ?: return fecharNovo()
        val dados = when (val r = NovoLancamento.validar(entrada)) {
            is ResultadoNovo.Erro -> return avisar(r.mensagem)
            is ResultadoNovo.Ok -> r.dados.copy(parcela = original.parcela)
        }
        trabalhar("Salvando as alterações.") {
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
                _estado.update { e ->
                    val lista = e.todos.map { if (it.id == id) editado else it }
                    val depois = recalcular(e.copy(todos = lista, novoAberto = false, editandoId = null))
                    val aparece = depois.lancamentos.any { it.id == id }
                    depois.copy(
                        aviso = "Lançamento alterado: ${Formatacao.fala(editado, e.config.campos)}." +
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
        trabalhar(if (todos.size > 1) "Salvando as ${todos.size} parcelas." else "Salvando o lançamento.") {
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
            _estado.update { e ->
                // O reenvio de um lançamento que já tinha chegado devolve o mesmo id: não duplica na lista.
                val ids = criados.map { it.id }.toSet()
                val lista = e.todos.filter { it.id !in ids } + criados
                val depois = recalcular(
                    e.copy(
                        todos = lista, novoAberto = false, uuidNovo = "", leitura = null, extracaoIA = null,
                        documentosLidos = emptyList(),
                        documentos = e.documentos.map { d ->
                            if (ligados.any { it.id == d.id }) d.copy(lancamentoId = criados[0].id) else d
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
                    aviso = resumo + (if (naoLigou) " Não foi possível vincular o arquivo ao lançamento; ele continua em Documentos." else "") + when {
                        aparecem == criados.size -> ""
                        aparecem == 0 -> if (criados.size == 1) " Ele não aparece na lista por causa dos filtros." else " Elas não aparecem na lista por causa dos filtros."
                        else -> " Algumas não aparecem na lista por causa dos filtros."
                    },
                )
            }
        }
    }

    fun sair() {
        loja.limpar()
        _estado.value = estadoInicial("Você saiu da conta.")
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
            recalcular(it.copy(todos = todos, opcoes = opcoes, permissoes = permissoes, contas = contas, documentos = documentos))
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

    private fun trabalhar(mensagem: String, bloco: () -> Unit) {
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
                avisar("Sem conexão com a internet. Tente de novo mais tarde.")
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
