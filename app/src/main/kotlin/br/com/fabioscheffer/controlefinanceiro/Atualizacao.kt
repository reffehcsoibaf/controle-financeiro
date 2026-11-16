package br.com.fabioscheffer.controlefinanceiro

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Uma versão nova publicada no GitHub (Release com tag android-vX.Y.Z e o APK anexado). */
data class NovaVersao(
    val versao: String,
    val notas: String,
    val urlApk: String,
    val nomeApk: String,
    val tamanho: Long,
    /** SHA-256 do APK em hexadecimal, se o GitHub informou; vazio se não. */
    val sha256: String = "",
)

object Versoes {
    const val PREFIXO_TAG = "android-v"

    /** "android-v0.20.0" vira "0.20.0"; null se a tag não é de uma versão do Android. */
    fun doTag(tag: String): String? =
        tag.takeIf { it.startsWith(PREFIXO_TAG) }?.removePrefix(PREFIXO_TAG)?.takeIf { Regex("""\d+(\.\d+)*""").matches(it) }

    /** Negativo se [a] é mais antiga que [b], zero se iguais, positivo se mais nova. Compara número a número. */
    fun comparar(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** A versão mais nova que existe na lista de Releases do GitHub (JSON), ou null se não há nenhuma do Android. */
    fun maisNova(json: String): NovaVersao? {
        val lista = JSONArray(json)
        var melhor: NovaVersao? = null
        for (i in 0 until lista.length()) {
            val r = lista.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val versao = doTag(r.optString("tag_name")) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: org.json.JSONObject? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
            if (apk == null) continue
            val nova = NovaVersao(
                versao, r.optString("body").trim(), apk.getString("browser_download_url"), apk.getString("name"),
                apk.optLong("size"), apk.optString("digest").removePrefix("sha256:").takeIf { apk.optString("digest").startsWith("sha256:") }.orEmpty(),
            )
            if (melhor == null || comparar(nova.versao, melhor.versao) > 0) melhor = nova
        }
        return melhor
    }
}

/** Fala com o GitHub: procura a versão nova e baixa o APK. Sem login: o repositório é público. */
class GitHubApi(private val repositorio: String = Config.REPOSITORIO_GITHUB) {
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun buscarNovaVersao(): NovaVersao? {
        val pedido = Request.Builder()
            .url("https://api.github.com/repos/$repositorio/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", Config.IDENTIFICACAO)
            .build()
        try {
            cliente.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) throw ErroServidor("O GitHub respondeu ${r.code}.")
                return Versoes.maisNova(r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw SemConexao(e.message.orEmpty())
        }
    }

    /** Baixa o APK para [destino]. Confere o tamanho e, se o GitHub informou, o SHA-256. */
    fun baixar(v: NovaVersao, destino: File) {
        destino.parentFile?.mkdirs()
        val pedido = Request.Builder().url(v.urlApk).header("User-Agent", Config.IDENTIFICACAO).build()
        try {
            cliente.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) throw ErroServidor("O GitHub respondeu ${r.code}.")
                val corpo = r.body ?: throw ErroServidor("O download veio vazio.")
                val sha = MessageDigest.getInstance("SHA-256")
                destino.outputStream().use { saida ->
                    corpo.byteStream().use { entrada ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            saida.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                        }
                    }
                }
                if (v.tamanho > 0 && destino.length() != v.tamanho) {
                    destino.delete()
                    throw ErroServidor("O download não veio completo. Tente de novo.")
                }
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                if (v.sha256.isNotEmpty() && !hex.equals(v.sha256, ignoreCase = true)) {
                    destino.delete()
                    throw ErroServidor("O arquivo baixado não confere com o publicado. Tente de novo.")
                }
            }
        } catch (e: IOException) {
            destino.delete()
            throw SemConexao(e.message.orEmpty())
        }
    }
}
