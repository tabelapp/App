package br.com.tabelapp.ui.comum

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Cache em memória das imagens baixadas (artes de banner). */
private val cacheImagens = LruCache<String, ImageBitmap>(16)

/**
 * Mostra uma imagem da internet (sem biblioteca extra). Enquanto baixa, ou se
 * falhar, mostra [semImagem].
 */
@Composable
fun ImagemRemota(
    url: String,
    descricao: String?,
    modifier: Modifier = Modifier,
    escala: ContentScale = ContentScale.Crop,
    semImagem: @Composable () -> Unit = {},
) {
    val imagem by produceState(initialValue = cacheImagens.get(url), url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }.getOrNull()
            }?.also { cacheImagens.put(url, it) }
        }
    }
    val atual = imagem
    if (atual != null) Image(atual, contentDescription = descricao, modifier = modifier, contentScale = escala)
    else semImagem()
}
