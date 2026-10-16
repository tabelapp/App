package br.com.tabelapp.dados

import android.net.Uri
import br.com.tabelapp.core.LinhaOcr
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lê o texto de uma foto no próprio celular (ML Kit do Google, gratuito; o modelo
 * é baixado pelo Google Play). Usado no alvará do cadastro do PDV. Devolve cada
 * linha com a posição na foto.
 */
class LeitorTexto(private val imagens: Imagens) {

    private val reconhecedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Todo o texto da foto, uma linha por linha lida. */
    suspend fun texto(uri: Uri): String = linhas(uri, 0).joinToString("\n") { it.texto }

    /** Linhas de texto da foto; [foto] identifica a foto (posições só se comparam dentro dela). */
    suspend fun linhas(uri: Uri, foto: Int): List<LinhaOcr> {
        // Resolução maior que a do envio: letra pequena precisa de pixels.
        val bitmap = withContext(Dispatchers.IO) { imagens.bitmap(uri, LADO_LEITURA) }
            ?: throw ErroAmigavel("Não consegui abrir a foto.")
        val texto = suspendCancellableCoroutine<Text> { cont ->
            reconhecedor.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    cont.resumeWithException(
                        ErroAmigavel("Não consegui ler o texto da foto. Se for a primeira vez, aguarde um minuto (o leitor do Google está sendo baixado) e tente de novo.")
                    )
                }
        }
        return texto.textBlocks.flatMap { it.lines }.mapNotNull { linha ->
            val r = linha.boundingBox ?: return@mapNotNull null
            LinhaOcr(linha.text, r.left, r.top, r.right, r.bottom, foto)
        }
    }

    private companion object {
        const val LADO_LEITURA = 2400
    }
}
