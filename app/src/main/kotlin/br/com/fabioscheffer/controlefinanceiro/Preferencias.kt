package br.com.fabioscheffer.controlefinanceiro

import android.content.Context

/**
 * O que a pessoa escolhe e o app lembra ao abrir de novo: ordem, tipo e situação da lista, e as
 * Configurações. A busca e as datas não ficam guardadas, para nenhum lançamento parecer sumido ao
 * abrir (como no Windows).
 */
class Preferencias(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("preferencias", Context.MODE_PRIVATE)

    fun lerFiltros(): Filtros = Filtros(
        ordem = if (prefs.getString(ORDEM, null) == "antigos") "antigos" else "recentes",
        tipo = prefs.getString(TIPO, null)?.takeIf { it == "entrada" || it == "saida" }.orEmpty(),
        status = prefs.getString(SITUACAO, null)?.takeIf { it == "pendente" || it == "pago" }.orEmpty(),
    )

    fun salvarOrdem(ordem: String) = prefs.edit().putString(ORDEM, ordem).apply()
    fun salvarTipo(tipo: String) = prefs.edit().putString(TIPO, tipo).apply()
    fun salvarSituacao(situacao: String) = prefs.edit().putString(SITUACAO, situacao).apply()

    fun lerConfiguracoes(): Configuracoes = Configuracoes(
        campos = Campos.desserializar(prefs.getString(CAMPOS, null)),
        falarQuantidade = prefs.getBoolean(FALAR_QUANTIDADE, true),
        falarTotal = prefs.getBoolean(FALAR_TOTAL, false),
    )

    fun salvarConfiguracoes(c: Configuracoes) = prefs.edit()
        .putString(CAMPOS, Campos.serializar(c.campos))
        .putBoolean(FALAR_QUANTIDADE, c.falarQuantidade)
        .putBoolean(FALAR_TOTAL, c.falarTotal)
        .apply()

    private companion object {
        const val ORDEM = "ordem_lista"
        const val TIPO = "tipo_lista"
        const val SITUACAO = "situacao_lista"
        const val CAMPOS = "campos_lancamento"
        const val FALAR_QUANTIDADE = "falar_quantidade_filtro"
        const val FALAR_TOTAL = "falar_total_filtro"
    }
}
