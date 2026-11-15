package br.com.fabioscheffer.controlefinanceiro

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/** O documento já no formato que o servidor de leitura espera. */
data class DocumentoPronto(val tipo: String, val conteudo: String, val mime: String, val nome: String)

object Documentos {
    const val LIMITE_BYTES = 15 * 1024 * 1024
    private const val LADO_MAXIMO = 2200
    private const val IMAGEM_GRANDE = 1_500_000

    const val FORMATOS = "Use imagem, PDF, TXT ou XML."

    /** "imagem", "pdf", "xml" ou "texto"; null se o formato não serve. */
    fun tipoDe(nome: String, mime: String?): String? {
        val ext = nome.substringAfterLast('.', "").lowercase()
        val m = mime.orEmpty().lowercase()
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> "imagem"
            m == "application/pdf" || ext == "pdf" -> "pdf"
            ext == "xml" || m.endsWith("/xml") || m.endsWith("+xml") -> "xml"
            ext == "txt" || m.startsWith("text/") -> "texto"
            else -> null
        }
    }

    /** O arquivo onde a câmera grava a foto, numa pasta que só guarda a foto atual. */
    fun arquivoDeFoto(contexto: android.content.Context): java.io.File {
        val pasta = java.io.File(contexto.cacheDir, "fotos").apply { mkdirs() }
        pasta.listFiles()?.forEach { it.delete() }
        return java.io.File(pasta, "documento-${System.currentTimeMillis()}.jpg")
    }

    /** O endereço do arquivo da foto para entregar à câmera. */
    fun novaFoto(contexto: android.content.Context): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            contexto, contexto.packageName + ".arquivos", arquivoDeFoto(contexto),
        )

    fun doTexto(texto: String) = DocumentoPronto("texto", texto, "text/plain", "texto copiado")

    /** Lê o arquivo escolhido. Levanta [ErroDocumento] com a mensagem para a pessoa. */
    fun preparar(resolver: ContentResolver, uri: Uri): DocumentoPronto {
        val nome = nomeDe(resolver, uri)
        val mime = resolver.getType(uri)
        val tipo = tipoDe(nome, mime) ?: throw ErroDocumento("Formato não suportado. $FORMATOS")
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readNBytesAte(LIMITE_BYTES + 1) }
        } catch (e: Exception) {
            null
        } ?: throw ErroDocumento("Não foi possível abrir o arquivo.")
        if (bytes.size > LIMITE_BYTES) throw ErroDocumento("O arquivo é grande demais. O limite é 15 MB.")
        if (bytes.isEmpty()) throw ErroDocumento("O arquivo está vazio.")
        return when (tipo) {
            "imagem" -> {
                val (dados, mimeFinal) = reduzirImagem(bytes, mime ?: "image/jpeg")
                DocumentoPronto("imagem", Base64.encodeToString(dados, Base64.NO_WRAP), mimeFinal, nome)
            }
            "pdf" -> DocumentoPronto("pdf", Base64.encodeToString(bytes, Base64.NO_WRAP), "application/pdf", nome)
            "xml" -> DocumentoPronto("xml", String(bytes, Charsets.UTF_8), mime ?: "text/xml", nome)
            else -> DocumentoPronto("texto", String(bytes, Charsets.UTF_8), mime ?: "text/plain", nome)
        }
    }

    /**
     * Foto de celular tem vários megabytes: reduz para um tamanho que a IA lê bem e que sobe rápido
     * na rede móvel. Imagem pequena segue como está.
     */
    private fun reduzirImagem(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        if (bytes.size <= IMAGEM_GRANDE) return bytes to mime
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
        val maior = maxOf(medidas.outWidth, medidas.outHeight)
        if (maior <= 0) return bytes to mime
        var amostra = 1
        while (maior / (amostra * 2) >= LADO_MAXIMO) amostra *= 2
        val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
        val figura = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes) ?: return bytes to mime
        val saida = ByteArrayOutputStream()
        figura.compress(Bitmap.CompressFormat.JPEG, 85, saida)
        figura.recycle()
        return saida.toByteArray() to "image/jpeg"
    }

    private fun nomeDe(resolver: ContentResolver, uri: Uri): String =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment.orEmpty().ifEmpty { "documento" }

    private fun java.io.InputStream.readNBytesAte(limite: Int): ByteArray {
        val saida = ByteArrayOutputStream()
        val bloco = ByteArray(64 * 1024)
        while (saida.size() < limite) {
            val n = read(bloco, 0, minOf(bloco.size, limite - saida.size()))
            if (n < 0) break
            saida.write(bloco, 0, n)
        }
        return saida.toByteArray()
    }
}
