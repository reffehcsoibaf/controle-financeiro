package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONException
import org.json.JSONObject

/**
 * Lê o `preferencias.json` do programa Windows (pasta %APPDATA%\ControleFinanceiro) e o transforma
 * nas Configurações do app. Só entra o que é configuração: quais campos aparecem e a ordem deles,
 * e as duas opções de informar quantidade e saldo ao filtrar. Ordem, tipo e situação da lista são
 * o filtro do momento e não são importados, assim como a posição da tabela, que só vale no Windows.
 *
 * O formato é o mesmo que um dia pode ir para o servidor: só muda de onde o texto vem.
 */
object ImportadorWindows {
    /** Devolve null se o texto não for um arquivo de configurações do Windows com campos válidos. */
    fun ler(texto: String): Configuracoes? {
        val o = try {
            JSONObject(texto.trim().removePrefix("﻿"))
        } catch (e: JSONException) {
            return null
        }
        val colunas = o.optJSONArray("colunas") ?: return null
        val campos = (0 until colunas.length()).mapNotNull { i ->
            val par = colunas.optJSONArray(i)
            if (par == null || par.length() != 2) null else CampoConfig(par.optString(0), par.optBoolean(1, false))
        }
        // Sem nenhum campo conhecido e ligado, não é um arquivo que sirva.
        if (campos.none { it.visivel && Campos.conhece(it.chave) }) return null
        return Configuracoes(
            campos = Campos.normalizar(campos),
            // Como no Windows: só "false" desliga a quantidade, e só "true" liga o saldo.
            falarQuantidade = o.opt("falar_quantidade_filtro") != false,
            falarTotal = o.opt("falar_total_filtro") == true,
        )
    }
}
