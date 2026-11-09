package br.com.tabelapp.ui.busca

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import br.com.tabelapp.R
import br.com.tabelapp.core.Compartilhamento
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.ui.comum.WhatsApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compartilha o "card de preço": uma imagem com produto, preço e local (nas
 * cores da marca) + o texto "Olha esse preço!... Quem pesquisa economiza" com
 * o link para baixar o app. Envia direto pelo WhatsApp.
 */
object CompartilharPreco {

    suspend fun compartilhar(contexto: Context, cotacao: Cotacao) {
        val arquivo = withContext(Dispatchers.Default) {
            val bitmap = ArteCompartilhar.desenhar(contexto.resources, cotacao)
            val pasta = File(contexto.cacheDir, "compartilhar").apply { mkdirs() }
            File(pasta, "preco.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
        // Direto pelo WhatsApp do usuário (pedido do fundador).
        // O texto termina com o aviso: preço de terceiros, sujeito a alteração.
        val aviso = contexto.getString(
            if (Compartilhamento.porEstabelecimento(cotacao)) R.string.compartilhar_aviso_estabelecimento
            else R.string.compartilhar_aviso_usuario
        )
        WhatsApp.enviarImagem(contexto, uri, Compartilhamento.texto(cotacao, aviso = aviso))
    }
}
