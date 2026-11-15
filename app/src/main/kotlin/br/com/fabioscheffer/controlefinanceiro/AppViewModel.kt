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
data class Leitura(val id: Int, val dados: DadosIA, val acrescentar: Boolean = false)

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
                leitura = null, extracaoIA = null,
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

    private fun lerComIA(acrescentar: Boolean = false, preparar: () -> DocumentoPronto) {
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
            _estado.update {
                it.copy(
                    leitura = Leitura(++sequenciaDeLeitura, dados, acrescentar),
                    // Ao acrescentar, vale o que a primeira leitura sugeriu: é com ela que se aprende.
                    extracaoIA = if (acrescentar) it.extracaoIA ?: dados else dados,
                    aviso = "Documento lido.",
                )
            }
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
                val depois = recalcular(e.copy(todos = e.todos.filter { it.id !in apagados }))
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
        _estado.update { it.copy(novoAberto = false, editandoId = null, aviso = "", leitura = null, extracaoIA = null) }
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
        val todos = when (val r = NovoLancamento.validar(entrada)) {
            is ResultadoNovo.Erro -> return avisar(r.mensagem)
            is ResultadoNovo.Ok -> r.todos
        }
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
                val depois = recalcular(e.copy(todos = lista, novoAberto = false, uuidNovo = "", leitura = null, extracaoIA = null))
                val aparecem = depois.lancamentos.count { it.id in ids }
                val resumo = if (criados.size == 1) {
                    "Lançamento criado: ${Formatacao.fala(criados[0], e.config.campos)}."
                } else {
                    "${criados.size} parcelas criadas, total ${Formatacao.reaisFalado(Regras.somar(criados.map { it.valor }))}."
                }
                depois.copy(
                    aviso = resumo + when {
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
        api.conferirAcesso(sessao)
        val todos = api.baixarLancamentos(sessao)
        val opcoes = api.baixarOpcoes(sessao)
        _estado.update {
            recalcular(it.copy(todos = todos, opcoes = opcoes)).let { e -> e.copy(aviso = mensagemDaLista(e)) }
        }
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
