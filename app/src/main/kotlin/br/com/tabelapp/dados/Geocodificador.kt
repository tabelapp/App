package br.com.tabelapp.dados

import android.content.Context
import android.location.Geocoder
import androidx.core.content.edit
import br.com.tabelapp.core.PontoGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Transforma o endereço de um ponto de venda em coordenada, para o pino do mapa.
 *
 * Usa o Geocoder do próprio Android (serviço do Google no aparelho, sem chave e
 * sem custo). Os resultados ficam guardados no celular: cada endereço é
 * procurado uma vez só.
 */
class Geocodificador(contexto: Context) {
    private val geocoder = if (Geocoder.isPresent()) Geocoder(contexto, Locale.forLanguageTag("pt-BR")) else null
    private val cache = contexto.getSharedPreferences("geocodigos", Context.MODE_PRIVATE)

    val disponivel: Boolean get() = geocoder != null

    /** null = endereço não encontrado (ou serviço indisponível no aparelho). */
    suspend fun localizar(endereco: String): PontoGeo? {
        val chave = endereco.trim().lowercase()
        cache.getString(chave, null)?.let { salvo ->
            if (salvo == NAO_ACHOU) return null
            val (lat, lng) = salvo.split(';').map { it.toDouble() }
            return PontoGeo(lat, lng)
        }
        val g = geocoder ?: return null
        val achado = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION") // a versão com callback (API 33+) faz o mesmo; esta roda em qualquer Android.
                // Só dentro do estado do RJ: evita cair numa rua de mesmo nome em outra cidade.
                g.getFromLocationName(endereco, 1, -23.4, -44.9, -20.7, -40.9)?.firstOrNull()
            }
        }
        // Falha de rede: não guarda, tenta de novo na próxima vez.
        if (achado.isFailure) return null
        val ponto = achado.getOrNull()?.let { PontoGeo(it.latitude, it.longitude) }
        cache.edit { putString(chave, ponto?.let { "${it.latitude};${it.longitude}" } ?: NAO_ACHOU) }
        return ponto
    }

    private companion object {
        const val NAO_ACHOU = "-"
    }
}
