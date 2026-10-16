package br.com.tabelapp.dados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Prepara uma foto (ex.: alvará) para envio: reduz para no máximo 1600 px no lado maior
 * e grava em JPEG 80% (legível para conferência e leve para o 4G, ~200–500 KB).
 */
class Imagens(private val contexto: Context) {

    fun jpegReduzido(uri: Uri, ladoMaximo: Int = 1600, qualidade: Int = 80): ByteArray? = runCatching {
        val final = bitmap(uri, ladoMaximo) ?: return null
        ByteArrayOutputStream().use { saida ->
            final.compress(Bitmap.CompressFormat.JPEG, qualidade, saida)
            saida.toByteArray()
        }
    }.getOrNull()

    /** A foto com no máximo [ladoMaximo] px no lado maior (para a leitura do texto, em resolução maior). */
    fun bitmap(uri: Uri, ladoMaximo: Int): Bitmap? = runCatching {
        val bitmap = decodificar(uri, ladoMaximo) ?: return null
        val escala = ladoMaximo.toFloat() / max(bitmap.width, bitmap.height)
        if (escala < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * escala).toInt(), (bitmap.height * escala).toInt(), true)
        } else {
            bitmap
        }
    }.getOrNull()

    private fun decodificar(uri: Uri, ladoMaximo: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder já respeita a rotação da foto (EXIF).
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contexto.contentResolver, uri)) { decoder, info, _ ->
                val maior = max(info.size.width, info.size.height)
                if (maior > ladoMaximo) {
                    val f = ladoMaximo.toFloat() / maior
                    decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var amostra = 1
            while (max(limites.outWidth, limites.outHeight) / (amostra * 2) >= ladoMaximo) amostra *= 2
            val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
            contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opcoes) }
        }
}
