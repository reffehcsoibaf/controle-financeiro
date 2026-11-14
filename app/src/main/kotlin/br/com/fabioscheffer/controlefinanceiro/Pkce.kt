package br.com.fabioscheffer.controlefinanceiro

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636): o código que volta do login só serve para quem tem o verificador. */
object Pkce {
    data class Par(val verificador: String, val desafio: String)

    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun novo(): Par {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verificador = base64.encodeToString(bytes)
        return Par(verificador, desafioDe(verificador))
    }

    fun desafioDe(verificador: String): String =
        base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verificador.toByteArray(Charsets.US_ASCII))
        )
}
