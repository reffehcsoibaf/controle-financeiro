package br.com.fabioscheffer.controlefinanceiro

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Sessao(
    val accessToken: String,
    val refreshToken: String,
    /** Segundos desde 1970. */
    val expiraEm: Long,
    val userId: String,
    val email: String,
) {
    fun toJson(): String = JSONObject()
        .put("access_token", accessToken).put("refresh_token", refreshToken)
        .put("expires_at", expiraEm).put("user_id", userId).put("email", email)
        .toString()

    companion object {
        fun fromJson(texto: String): Sessao {
            val o = JSONObject(texto)
            return Sessao(
                o.getString("access_token"), o.getString("refresh_token"),
                o.getLong("expires_at"), o.getString("user_id"), o.optString("email", ""),
            )
        }
    }
}

/**
 * Guarda a sessão cifrada com uma chave do Android Keystore (AES-GCM), que não sai do aparelho.
 * O verificador do PKCE também fica aqui, para sobreviver se o Android fechar o app durante o login.
 */
class SessaoStore(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("sessao", Context.MODE_PRIVATE)

    fun salvar(sessao: Sessao) {
        prefs.edit().putString(CHAVE_SESSAO, cifrar(sessao.toJson())).apply()
    }

    fun ler(): Sessao? {
        val guardado = prefs.getString(CHAVE_SESSAO, null) ?: return null
        return try {
            Sessao.fromJson(decifrar(guardado))
        } catch (e: Exception) {
            // Chave perdida ou dado ilegível: a pessoa entra de novo.
            limpar()
            null
        }
    }

    fun limpar() {
        prefs.edit().remove(CHAVE_SESSAO).apply()
    }

    fun guardarVerificador(verificador: String) {
        prefs.edit().putString(CHAVE_VERIFICADOR, verificador).apply()
    }

    fun tomarVerificador(): String? {
        val v = prefs.getString(CHAVE_VERIFICADOR, null)
        prefs.edit().remove(CHAVE_VERIFICADOR).apply()
        return v
    }

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gerador.generateKey()
    }

    private fun cifrar(texto: String): String {
        val c = Cipher.getInstance(TRANSFORMACAO).apply { init(Cipher.ENCRYPT_MODE, chave()) }
        val cifrado = c.doFinal(texto.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(cifrado)
    }

    private fun decifrar(guardado: String): String {
        val (iv, cifrado) = guardado.split(":")
        val c = Cipher.getInstance(TRANSFORMACAO).apply {
            init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return String(c.doFinal(Base64.decode(cifrado, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "controle_financeiro_sessao"
        const val TRANSFORMACAO = "AES/GCM/NoPadding"
        const val CHAVE_SESSAO = "sessao"
        const val CHAVE_VERIFICADOR = "verificador"
    }
}
