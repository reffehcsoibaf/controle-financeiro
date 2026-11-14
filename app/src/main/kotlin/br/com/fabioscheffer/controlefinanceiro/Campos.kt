package br.com.fabioscheffer.controlefinanceiro

/** Um campo do lançamento, ligado ou desligado, na posição em que está na lista. */
data class CampoConfig(val chave: String, val visivel: Boolean)

/** O que a pessoa escolhe em Configurações. */
data class Configuracoes(
    val campos: List<CampoConfig> = Campos.padrao(),
    /** Ao filtrar, o aviso diz quantos lançamentos aparecem. */
    val falarQuantidade: Boolean = true,
    /** Ao filtrar, o aviso também diz o saldo. */
    val falarTotal: Boolean = false,
)

/**
 * Os campos que a lista e os detalhes podem mostrar, e a ordem em que aparecem. A mesma ordem vale
 * para a frase que o leitor de tela fala e para o que se vê na tela (como as colunas do Windows).
 */
object Campos {
    class Definicao(val chave: String, val titulo: String)

    val TODOS = listOf(
        Definicao("descricao", "Descrição"),
        Definicao("valor", "Valor"),
        Definicao("itens", "Itens"),
        Definicao("vencimento", "Vencimento"),
        Definicao("situacao", "Situação"),
        Definicao("forma", "Forma de pagamento"),
        Definicao("conta", "Banco ou cartão"),
        Definicao("origem", "Data de origem"),
        Definicao("parcela", "Parcela"),
        Definicao("estabelecimento", "Estabelecimento"),
        Definicao("categoria", "Categoria"),
        Definicao("credor", "Credor"),
        Definicao("devedor", "Devedor"),
    )

    private val POR_CHAVE = TODOS.associateBy { it.chave }

    /** Itens, credor e devedor começam desligados. */
    private val PADRAO_VISIVEIS = listOf(
        "descricao", "valor", "estabelecimento", "origem", "vencimento", "conta", "forma",
        "parcela", "categoria", "situacao",
    )

    fun titulo(chave: String): String = POR_CHAVE[chave]?.titulo ?: chave

    fun conhece(chave: String): Boolean = chave in POR_CHAVE

    fun padrao(): List<CampoConfig> =
        PADRAO_VISIVEIS.map { CampoConfig(it, true) } +
            TODOS.filter { it.chave !in PADRAO_VISIVEIS }.map { CampoConfig(it.chave, false) }

    /**
     * Aceita o que veio guardado: ignora o que não existe mais, repetidos, e campo novo de versão
     * futura entra desligado no fim. Sem nenhum campo ligado, volta ao padrão.
     */
    fun normalizar(salvos: List<CampoConfig>?): List<CampoConfig> {
        val saida = mutableListOf<CampoConfig>()
        val vistos = mutableSetOf<String>()
        for (c in salvos.orEmpty()) if (c.chave in POR_CHAVE && vistos.add(c.chave)) saida += c
        if (saida.none { it.visivel }) return padrao()
        TODOS.filter { it.chave !in vistos }.forEach { saida += CampoConfig(it.chave, false) }
        return saida
    }

    /** Troca o campo com o vizinho: [delta] -1 sobe, +1 desce. Nos extremos, não muda. */
    fun mover(campos: List<CampoConfig>, chave: String, delta: Int): List<CampoConfig> {
        val i = campos.indexOfFirst { it.chave == chave }
        val j = i + delta
        if (i < 0 || j !in campos.indices) return campos
        return campos.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }

    /** "descricao:1,valor:0,..." */
    fun serializar(campos: List<CampoConfig>): String =
        campos.joinToString(",") { it.chave + ":" + if (it.visivel) "1" else "0" }

    fun desserializar(texto: String?): List<CampoConfig> {
        val lidos = texto.orEmpty().split(",").mapNotNull {
            val partes = it.split(":")
            if (partes.size == 2 && partes[0].isNotEmpty()) CampoConfig(partes[0], partes[1] == "1") else null
        }
        return normalizar(lidos)
    }
}
