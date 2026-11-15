package br.com.fabioscheffer.controlefinanceiro

import java.time.OffsetDateTime
import java.time.ZoneId

/** Um arquivo guardado na nuvem (tabela financeiro_documentos_armazenados), ligado ou não a um lançamento. */
data class DocumentoGuardado(
    val id: String,
    val nome: String,
    val caminho: String,
    val mime: String?,
    val tamanho: Long?,
    /** Quando foi guardado, como o servidor devolve (UTC). */
    val criadoEm: String,
    val lancamentoId: Long? = null,
)

/** Regras da lista de documentos, as mesmas do programa Windows (gerenciar.py, Documentos). */
object Guardados {
    /** O servidor guarda em UTC: à noite, no Brasil, já seria o dia seguinte. Devolve AAAA-MM-DD local. */
    fun dataLocal(iso: String?, zona: ZoneId = ZoneId.systemDefault()): String = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zona).toLocalDate().toString()
    } catch (e: Exception) {
        iso.orEmpty().take(10)
    }

    /** "340 KB", "1,5 MB", "—" se não se sabe. */
    fun tamanhoFalado(bytes: Long?): String = when {
        bytes == null || bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${Math.round(bytes / 1024.0)} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)).replace('.', ',')
    }

    /**
     * Filtra por parte do nome (sem acento nem maiúscula) e pelo dia em que foi guardado. [de] e [ate]
     * são AAAA-MM-DD ou vazio. Se [lancamentoId] não for null, só os documentos desse lançamento.
     */
    fun filtrar(
        todos: List<DocumentoGuardado>, busca: String, de: String, ate: String, lancamentoId: Long? = null,
        zona: ZoneId = ZoneId.systemDefault(),
    ): List<DocumentoGuardado> {
        val termo = Regras.semAcentos(busca).trim()
        return todos.filter { d ->
            val dia = dataLocal(d.criadoEm, zona)
            (lancamentoId == null || d.lancamentoId == lancamentoId) &&
                (termo.isEmpty() || termo in Regras.semAcentos(d.nome)) &&
                (de.isEmpty() || dia >= de) && (ate.isEmpty() || dia <= ate)
        }.sortedByDescending { it.criadoEm }
    }

    /** O que o leitor de tela fala ao chegar numa linha da lista. */
    fun fala(d: DocumentoGuardado, lancamento: Lancamento?, campos: List<CampoConfig>): String {
        val vinculo = when {
            lancamento != null -> "vinculado a ${Formatacao.fala(lancamento, campos)}"
            d.lancamentoId != null -> "vinculado a um lançamento"
            else -> "sem lançamento"
        }
        return "${d.nome}, guardado em ${Formatacao.dataFalada(dataLocal(d.criadoEm))}, " +
            "${tamanhoFalado(d.tamanho)}, $vinculo."
    }

    /** O nome com que o arquivo vai para a nuvem: só letras, números, ponto, traço e sublinhado. */
    fun caminhoNovo(userId: String, nome: String, agora: Long = System.currentTimeMillis()): String =
        "$userId/$agora-" + nome.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
