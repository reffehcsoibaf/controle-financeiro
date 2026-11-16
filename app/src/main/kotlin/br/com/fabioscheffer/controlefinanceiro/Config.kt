package br.com.fabioscheffer.controlefinanceiro

/** Mesmo Supabase do site e do programa Windows. A chave é a publicável (pública por desenho). */
object Config {
    const val SUPABASE_URL = "https://zlclakzjktpsbpfkltxa.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_o5Rn3Qp_C3boiYCRqQ5Ykw_2sNd3KiQ"
    const val MODULO = "financeiro"

    /** Onde ficam os arquivos guardados (o mesmo bucket do site e do Windows). */
    const val BUCKET_DOCUMENTOS = "documentos"

    /** Endereço para onde o Supabase devolve o login. Precisa estar na lista de Redirect URLs. */
    const val ESQUEMA = "br.com.fabioscheffer.controlefinanceiro"
    const val URL_RETORNO = "$ESQUEMA://login"

    const val IDENTIFICACAO = "ControleFinanceiroAndroid/" + BuildConfig.VERSION_NAME

    /** Onde ficam as Releases do app (as do Android têm a tag android-vX.Y.Z). */
    const val REPOSITORIO_GITHUB = "reffehcsoibaf/controle-financeiro"

    /** O servidor limita cada resposta; o programa Windows também lê de 1000 em 1000. */
    const val TAMANHO_PAGINA = 1000

    /** O endereço do site que lê documentos com IA (o mesmo do programa Windows). */
    const val URL_LEITURA_IA = "https://controle-financeiro.fabioscheffer.workers.dev/api/ler-documento"
}

/** O que o painel de administração liberou para esta conta (leitura por IA e documentos). */
data class Permissoes(val ia: Boolean = true, val documentos: Boolean = true)
