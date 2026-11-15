package br.com.fabioscheffer.controlefinanceiro

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Chamadas ao Supabase, as mesmas que o programa Windows faz. Bloqueiam: chamar fora da thread principal. */
class SupabaseApi(
    private val base: String = Config.SUPABASE_URL,
    private val chave: String = Config.SUPABASE_KEY,
) {
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(TEMPO_REDE, TimeUnit.SECONDS)
        .readTimeout(TEMPO_REDE, TimeUnit.SECONDS)
        .writeTimeout(TEMPO_REDE, TimeUnit.SECONDS)
        .build()

    fun urlLogin(desafio: String): String =
        base + "/auth/v1/authorize?" + consulta(
            "provider" to "google", "redirect_to" to Config.URL_RETORNO,
            "code_challenge" to desafio, "code_challenge_method" to "s256",
        )

    fun trocarCodigo(codigo: String, verificador: String): Sessao =
        sessaoDe(
            pedir(
                "/auth/v1/token?grant_type=pkce", "POST",
                JSONObject().put("auth_code", codigo).put("code_verifier", verificador),
            )
        )

    fun renovar(refreshToken: String): Sessao =
        sessaoDe(
            pedir(
                "/auth/v1/token?grant_type=refresh_token", "POST",
                JSONObject().put("refresh_token", refreshToken),
            )
        )

    /** Como no site e no Windows: só entra quem está habilitado no módulo. */
    fun conferirAcesso(sessao: Sessao): Permissoes {
        val linhas = JSONArray(
            pedir(
                "/rest/v1/profiles_modulos?" + consulta(
                    "select" to "habilitado,ai_enabled,documents_enabled", "user_id" to "eq." + sessao.userId,
                    "modulo" to "eq." + Config.MODULO,
                ),
                token = sessao.accessToken,
            )
        )
        if (linhas.length() == 0 || linhas.getJSONObject(0).opt("habilitado") != true) throw AcessoNegado()
        val o = linhas.getJSONObject(0)
        // Só "false" desliga: sem valor, a função está liberada (como no programa Windows).
        return Permissoes(ia = o.opt("ai_enabled") != false, documentos = o.opt("documents_enabled") != false)
    }

    /** Todos os lançamentos da pessoa, cada um com seus itens e pagamentos. */
    fun baixarLancamentos(sessao: Sessao): List<Lancamento> {
        val itens = tabela("financeiro_itens_lancamento", sessao)
            .groupBy({ it.getLong("lancamento_id") }, { Lancamento.itemDe(it) })
        val pagamentos = tabela("financeiro_pagamentos_lancamento", sessao)
            .groupBy({ it.getLong("lancamento_id") }, { Lancamento.pagamentoDe(it) })
        return tabela("financeiro_lancamentos", sessao).map {
            val id = it.getLong("id")
            Lancamento.fromJson(it, itens[id].orEmpty(), pagamentos[id].orEmpty())
        }
    }

    /**
     * Marca como pago ou pendente. Devolve o novo updated_at, ou null se o lançamento mudou ou sumiu
     * no servidor desde que foi lido: nesse caso nada é sobrescrito (mesma regra do programa Windows).
     */
    fun marcarPago(sessao: Sessao, id: Long, pago: Boolean, baseUpdatedAt: String?): String? {
        val filtro = mutableListOf(
            "id" to "eq.$id", "user_id" to "eq." + sessao.userId, "select" to "updated_at",
        )
        if (!baseUpdatedAt.isNullOrEmpty()) filtro += "updated_at" to "eq.$baseUpdatedAt"
        val linhas = JSONArray(
            pedir(
                "/rest/v1/financeiro_lancamentos?" + consulta(*filtro.toTypedArray()),
                "PATCH", JSONObject().put("pago", pago), sessao.accessToken,
                mapOf("Prefer" to "return=representation"),
            )
        )
        return if (linhas.length() == 0) null else linhas.getJSONObject(0).getString("updated_at")
    }

    /** As listas de opções (banco, forma, categoria…) por campo, em ordem alfabética sem contar acentos. */
    fun baixarOpcoes(sessao: Sessao): Map<String, List<String>> =
        tabela("financeiro_opcoes_listas", sessao)
            .filter { !it.isNull("campo") && !it.isNull("valor") }
            .groupBy({ it.getString("campo") }, { it.getString("valor") })
            .mapValues { (_, valores) -> valores.distinct().sortedBy { Regras.semAcentos(it) } }

    /**
     * Cria um lançamento pela função do servidor (a mesma do programa Windows), que grava tudo numa
     * transação só. O [uuid] identifica este envio: se a resposta se perder e a pessoa tentar de novo,
     * o servidor devolve o que já gravou em vez de criar um segundo. Devolve (id, updated_at).
     */
    fun criarLancamento(sessao: Sessao, d: DadosNovo, uuid: String): Pair<Long, String> {
        val resposta = salvar(sessao, null, null, d, uuid)
        if (resposta.optString("status") != "ok") throw ErroServidor("O servidor não aceitou o lançamento.")
        return resposta.getLong("id") to resposta.getString("updated_at")
    }

    /**
     * Grava as mudanças de um lançamento pela mesma função do servidor. Devolve o novo updated_at, ou
     * null se o lançamento mudou ou sumiu desde que foi lido (nada é sobrescrito).
     */
    fun atualizarLancamento(sessao: Sessao, id: Long, baseUpdatedAt: String?, d: DadosNovo): String? {
        val resposta = salvar(sessao, id, baseUpdatedAt, d, java.util.UUID.randomUUID().toString())
        return when (resposta.optString("status")) {
            "ok" -> resposta.getString("updated_at")
            "conflito" -> null
            else -> throw ErroServidor("O servidor não aceitou a alteração.")
        }
    }

    private val clienteIA = cliente.newBuilder()
        .readTimeout(TEMPO_IA, TimeUnit.SECONDS)
        .callTimeout(TEMPO_IA + 20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Manda o documento para o site, que consulta a IA, como o programa Windows. [tipo] é "imagem",
     * "pdf", "xml" ou "texto"; imagem e PDF vão em base64. As correções passadas servem de exemplo.
     */
    fun lerDocumento(sessao: Sessao, doc: DocumentoPronto, correcoes: List<Correcao>): DadosIA {
        val corpo = JSONObject()
            .put("tipo", doc.tipo).put("conteudo", doc.conteudo).put("mimeType", doc.mime)
            .put("nomeArquivo", doc.nome)
            .put(
                "correcoesConhecidas",
                JSONArray(
                    correcoes.map {
                        JSONObject().put("campo", it.campo).put("valorErrado", it.valorErrado)
                            .put("valorCorreto", it.valorCorreto).put("contexto", it.contexto ?: "")
                    }
                ),
            )
        val requisicao = Request.Builder()
            .url(Config.URL_LEITURA_IA)
            .header("User-Agent", Config.IDENTIFICACAO)
            .header("Authorization", "Bearer " + sessao.accessToken)
            .post(corpo.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            clienteIA.newCall(requisicao).execute().use { resposta ->
                val texto = resposta.body?.string().orEmpty()
                val json = try {
                    JSONObject(texto)
                } catch (e: org.json.JSONException) {
                    throw ErroLeituraIA("O servidor respondeu ${resposta.code}.")
                }
                if (json.optBoolean("ok")) return DadosIA.fromJson(json.optJSONObject("dados") ?: JSONObject())
                if (resposta.code == 401) throw SessaoExpirada(texto.take(300))
                throw ErroLeituraIA(json.optString("erro").ifEmpty { "Falha ao ler o documento." })
            }
        } catch (e: IOException) {
            throw SemConexao(e.message.orEmpty())
        }
    }

    /** As correções que a pessoa já fez, para a IA aprender com elas. */
    fun baixarCorrecoes(sessao: Sessao): List<Correcao> =
        tabela("financeiro_correcoes_ia", sessao).mapNotNull {
            if (it.isNull("campo") || it.isNull("valor_errado") || it.isNull("valor_correto")) null
            else Correcao(
                it.getString("campo"), it.getString("valor_errado"), it.getString("valor_correto"),
                if (it.isNull("contexto")) null else it.getString("contexto"),
            )
        }.reversed()

    /** Guarda uma correção, sem repetir uma que já existe (o servidor não tem restrição única). */
    fun registrarCorrecao(sessao: Sessao, c: Correcao) {
        val achadas = JSONArray(
            pedir(
                "/rest/v1/financeiro_correcoes_ia?" + consulta(
                    "campo" to "eq." + c.campo, "valor_errado" to "eq." + c.valorErrado,
                    "valor_correto" to "eq." + c.valorCorreto, "user_id" to "eq." + sessao.userId,
                    "select" to "id",
                ),
                token = sessao.accessToken,
            )
        )
        if (achadas.length() > 0) return
        val corpo = JSONObject().put("user_id", sessao.userId).put("campo", c.campo)
            .put("valor_errado", c.valorErrado).put("valor_correto", c.valorCorreto)
            .put("contexto", c.contexto ?: JSONObject.NULL)
        pedir(
            "/rest/v1/financeiro_correcoes_ia", "POST", corpo, sessao.accessToken,
            mapOf("Prefer" to "return=minimal"),
        )
    }

    /** Acrescenta um texto à lista de opções (banco, forma...), sem repetir um que já existe. */
    fun cadastrarOpcao(sessao: Sessao, campo: String, valor: String) {
        val achadas = JSONArray(
            pedir(
                "/rest/v1/financeiro_opcoes_listas?" + consulta(
                    "campo" to "eq.$campo", "valor" to "eq.$valor", "user_id" to "eq." + sessao.userId,
                    "select" to "id",
                ),
                token = sessao.accessToken,
            )
        )
        if (achadas.length() > 0) return
        val corpo = JSONObject().put("user_id", sessao.userId).put("campo", campo).put("valor", valor)
        pedir(
            "/rest/v1/financeiro_opcoes_listas", "POST", corpo, sessao.accessToken,
            mapOf("Prefer" to "return=minimal"),
        )
    }

    /**
     * Exclui um lançamento, só se ainda for a versão que o app leu. Devolve false se nada foi apagado
     * (já sumiu ou mudou em outro lugar). O servidor apaga os itens e pagamentos junto, desliga os
     * documentos e desfaz o vínculo do outro lançamento, como no programa Windows.
     */
    fun excluirLancamento(sessao: Sessao, id: Long, baseUpdatedAt: String?): Boolean {
        val filtro = mutableListOf("id" to "eq.$id", "user_id" to "eq." + sessao.userId, "select" to "id")
        if (!baseUpdatedAt.isNullOrEmpty()) filtro += "updated_at" to "eq.$baseUpdatedAt"
        val linhas = JSONArray(
            pedir(
                "/rest/v1/financeiro_lancamentos?" + consulta(*filtro.toTypedArray()),
                "DELETE", null, sessao.accessToken, mapOf("Prefer" to "return=representation"),
            )
        )
        return linhas.length() > 0
    }

    private fun salvar(sessao: Sessao, id: Long?, base: String?, d: DadosNovo, uuid: String): JSONObject {
        fun ou(texto: String?): Any = texto ?: JSONObject.NULL
        val dados = JSONObject()
            .put("data_origem", d.dataOrigem).put("data_vencimento", ou(d.dataVencimento))
            .put("valor", d.valor).put("banco", ou(d.banco)).put("forma", ou(d.forma))
            .put("estabelecimento", ou(d.estabelecimento)).put("categoria", ou(d.categoria))
            .put("credor", ou(d.credor)).put("devedor", ou(d.devedor))
            .put("descricao", ou(d.descricao)).put("pago", d.pago).put("parcela", ou(d.parcela))
        val itens = JSONArray(
            d.itens.map {
                JSONObject().put("valor", it.valor).put("categoria", ou(it.categoria))
                    .put("descricao", ou(it.descricao)).put("data_original", ou(it.dataOriginal))
            }
        )
        val pagamentos = JSONArray(
            d.pagamentos.map {
                JSONObject().put("banco", ou(it.banco)).put("forma", ou(it.forma)).put("valor", it.valor)
            }
        )
        val corpo = JSONObject()
            .put("p_id", id ?: JSONObject.NULL).put("p_base", ou(base))
            .put("p_client_uuid", uuid).put("p_dados", dados)
            .put("p_itens", itens).put("p_pagamentos", pagamentos)
        return JSONObject(
            pedir("/rest/v1/rpc/financeiro_salvar_lancamento", "POST", corpo, sessao.accessToken)
        )
    }

    /** O tipo, o saldo inicial e o limite de cada banco ou cartão configurado. */
    fun baixarContas(sessao: Sessao): List<ContaConfig> =
        tabela("financeiro_contas_config", sessao).mapNotNull {
            if (it.isNull("nome_banco") || it.isNull("tipo")) null
            else ContaConfig(
                it.getString("nome_banco"), it.getString("tipo"),
                if (it.isNull("saldo_inicial")) null else it.optDouble("saldo_inicial"),
                if (it.isNull("limite_total")) null else it.optDouble("limite_total"),
            )
        }

    /** Cria ou troca a configuração de um banco (uma por nome). */
    fun salvarConta(sessao: Sessao, c: ContaConfig) {
        val corpo = JSONObject().put("user_id", sessao.userId).put("nome_banco", c.nome).put("tipo", c.tipo)
            .put("saldo_inicial", c.saldoInicial ?: JSONObject.NULL)
            .put("limite_total", c.limiteTotal ?: JSONObject.NULL)
        pedir(
            "/rest/v1/financeiro_contas_config?" + consulta("on_conflict" to "user_id,nome_banco"),
            "POST", corpo, sessao.accessToken,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    /** Remove só a configuração; o nome continua nas listas. */
    fun removerConta(sessao: Sessao, nome: String) {
        pedir(
            "/rest/v1/financeiro_contas_config?" + consulta("nome_banco" to "eq.$nome", "user_id" to "eq." + sessao.userId),
            "DELETE", null, sessao.accessToken,
        )
    }

    /**
     * Liga dois lançamentos um ao outro (pagamento de fatura e a entrada no cartão). Devolve o novo
     * updated_at de cada um, na ordem em que vieram, pois ligar muda a versão no servidor.
     */
    fun vincular(sessao: Sessao, a: Long, b: Long): Pair<String?, String?> {
        fun ligar(origem: Long, destino: Long): String? {
            val linhas = JSONArray(
                pedir(
                    "/rest/v1/financeiro_lancamentos?" + consulta(
                        "id" to "eq.$origem", "user_id" to "eq." + sessao.userId, "select" to "updated_at",
                    ),
                    "PATCH", JSONObject().put("lancamento_vinculado_id", destino), sessao.accessToken,
                    mapOf("Prefer" to "return=representation"),
                )
            )
            return if (linhas.length() == 0) null else linhas.getJSONObject(0).getString("updated_at")
        }
        return ligar(a, b) to ligar(b, a)
    }

    // ---- documentos guardados ----

    fun baixarDocumentos(sessao: Sessao): List<DocumentoGuardado> =
        tabela("financeiro_documentos_armazenados", sessao).mapNotNull {
            if (it.isNull("id") || it.isNull("nome_arquivo") || it.isNull("caminho")) null
            else DocumentoGuardado(
                id = it.getString("id"), nome = it.getString("nome_arquivo"), caminho = it.getString("caminho"),
                mime = if (it.isNull("mime_type")) null else it.getString("mime_type"),
                tamanho = if (it.isNull("tamanho_bytes")) null else it.optLong("tamanho_bytes"),
                criadoEm = it.optString("criado_em"),
                lancamentoId = if (it.isNull("lancamento_id")) null else it.getLong("lancamento_id"),
            )
        }

    private fun urlDoArquivo(caminho: String) =
        "/storage/v1/object/${Config.BUCKET_DOCUMENTOS}/" +
            caminho.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * Sobe o arquivo para a nuvem e anota na tabela, como o programa Windows. O [id] é decidido aqui
     * para repetir o envio não criar um segundo registro.
     */
    fun guardarDocumento(sessao: Sessao, nome: String, mime: String?, bytes: ByteArray): DocumentoGuardado {
        val id = java.util.UUID.randomUUID().toString()
        val caminho = Guardados.caminhoNovo(sessao.userId, nome)
        val tipo = mime ?: "application/octet-stream"
        val envio = Request.Builder()
            .url(base + urlDoArquivo(caminho))
            .header("apikey", chave).header("User-Agent", Config.IDENTIFICACAO)
            .header("Authorization", "Bearer " + sessao.accessToken)
            .post(bytes.toRequestBody(tipo.toMediaType()))
            .build()
        executar(clienteIA, envio, aceitar = { it == 409 })
        val corpo = JSONObject().put("id", id).put("user_id", sessao.userId).put("nome_arquivo", nome)
            .put("caminho", caminho).put("mime_type", mime ?: JSONObject.NULL).put("tamanho_bytes", bytes.size)
        val linhas = JSONArray(
            pedir(
                "/rest/v1/financeiro_documentos_armazenados?" + consulta("on_conflict" to "id"),
                "POST", corpo, sessao.accessToken,
                mapOf("Prefer" to "resolution=ignore-duplicates,return=representation"),
            )
        )
        val criado = if (linhas.length() > 0) linhas.getJSONObject(0).optString("criado_em") else ""
        return DocumentoGuardado(id, nome, caminho, mime, bytes.size.toLong(), criado)
    }

    /** Baixa o conteúdo de um documento guardado. */
    fun baixarArquivo(sessao: Sessao, caminho: String): ByteArray {
        val pedido = Request.Builder()
            .url(base + urlDoArquivo(caminho))
            .header("apikey", chave).header("User-Agent", Config.IDENTIFICACAO)
            .header("Authorization", "Bearer " + sessao.accessToken)
            .build()
        return executar(clienteIA, pedido)
    }

    /** Liga o documento a um lançamento, ou desliga se [lancamentoId] for null. */
    fun vincularDocumento(sessao: Sessao, docId: String, lancamentoId: Long?) {
        pedir(
            "/rest/v1/financeiro_documentos_armazenados?" + consulta("id" to "eq.$docId", "user_id" to "eq." + sessao.userId),
            "PATCH", JSONObject().put("lancamento_id", lancamentoId ?: JSONObject.NULL), sessao.accessToken,
        )
    }

    /** Apaga o arquivo da nuvem (se já não existir, tudo bem) e depois o registro. */
    fun excluirDocumento(sessao: Sessao, d: DocumentoGuardado) {
        val apagar = Request.Builder()
            .url(base + urlDoArquivo(d.caminho))
            .header("apikey", chave).header("User-Agent", Config.IDENTIFICACAO)
            .header("Authorization", "Bearer " + sessao.accessToken)
            .delete()
            .build()
        executar(cliente, apagar, aceitar = { it == 404 || it == 400 })
        pedir(
            "/rest/v1/financeiro_documentos_armazenados?" + consulta("id" to "eq.${d.id}", "user_id" to "eq." + sessao.userId),
            "DELETE", null, sessao.accessToken,
        )
    }

    /** Faz o pedido e devolve o corpo. Códigos em [aceitar] não são erro (o corpo vem vazio, se for o caso). */
    private fun executar(
        http: OkHttpClient, requisicao: Request, aceitar: (Int) -> Boolean = { false },
    ): ByteArray {
        try {
            http.newCall(requisicao).execute().use { resposta ->
                val corpo = resposta.body?.bytes() ?: ByteArray(0)
                if (resposta.isSuccessful || aceitar(resposta.code)) return corpo
                if (resposta.code == 401) throw SessaoExpirada(String(corpo).take(300))
                throw ErroServidor("O servidor respondeu ${resposta.code}.")
            }
        } catch (e: IOException) {
            throw SemConexao(e.message.orEmpty())
        }
    }

    /** Lê a tabela inteira, de TAMANHO_PAGINA em TAMANHO_PAGINA, pois o servidor limita cada resposta. */
    private fun tabela(nome: String, sessao: Sessao): List<JSONObject> {
        val todas = ArrayList<JSONObject>()
        var deslocamento = 0
        while (true) {
            val pagina = JSONArray(
                pedir(
                    "/rest/v1/$nome?" + consulta(
                        "select" to "*", "user_id" to "eq." + sessao.userId, "order" to "id",
                        "limit" to Config.TAMANHO_PAGINA.toString(), "offset" to deslocamento.toString(),
                    ),
                    token = sessao.accessToken,
                )
            )
            for (i in 0 until pagina.length()) todas.add(pagina.getJSONObject(i))
            if (pagina.length() < Config.TAMANHO_PAGINA) return todas
            deslocamento += Config.TAMANHO_PAGINA
        }
    }

    private fun sessaoDe(texto: String): Sessao {
        val o = JSONObject(texto)
        val usuario = o.getJSONObject("user")
        return Sessao(
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            expiraEm = System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600),
            userId = usuario.getString("id"),
            email = usuario.optString("email", ""),
        )
    }

    private fun consulta(vararg pares: Pair<String, String>) =
        pares.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    private fun pedir(
        caminho: String,
        metodo: String = "GET",
        corpo: JSONObject? = null,
        token: String? = null,
        cabecalhos: Map<String, String> = emptyMap(),
    ): String {
        val requisicao = Request.Builder()
            .url(base + caminho)
            .header("apikey", chave)
            .header("User-Agent", Config.IDENTIFICACAO)
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                cabecalhos.forEach { (k, v) -> header(k, v) }
                val tipo = "application/json; charset=utf-8".toMediaType()
                method(metodo, corpo?.toString()?.toRequestBody(tipo))
            }
            .build()
        try {
            cliente.newCall(requisicao).execute().use { resposta ->
                val texto = resposta.body?.string().orEmpty()
                if (resposta.isSuccessful) return texto
                val codigo = resposta.code
                // Só isto derruba a sessão; outro erro não pode tirar a pessoa da conta.
                if (codigo == 401 || (codigo == 400 && caminho.startsWith("/auth/v1/"))) {
                    throw SessaoExpirada(texto.take(300))
                }
                throw ErroServidor("O servidor respondeu $codigo.")
            }
        } catch (e: IOException) {
            throw SemConexao(e.message.orEmpty())
        }
    }

    private companion object {
        const val TEMPO_REDE = 30L
        const val TEMPO_IA = 180L
    }
}
