package br.com.tabelapp.dados

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import br.com.tabelapp.core.PontoGeo

/**
 * Posição aproximada do usuário, usando a última localização conhecida do aparelho
 * (sem Google Play Services). Suficiente para "mais perto" dentro de Petrópolis.
 * O mapa de verdade (seção 3 do briefing) virá com a API de mapas.
 */
class Localizacao(private val contexto: Context) {

    fun temPermissao(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(contexto, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun ultimaConhecida(): PontoGeo? {
        if (!temPermissao()) return null
        val gerenciador = contexto.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val melhor: Location? = gerenciador.getProviders(true)
            .mapNotNull { provedor -> runCatching { gerenciador.getLastKnownLocation(provedor) }.getOrNull() }
            .maxByOrNull { it.time }
        return melhor?.let { PontoGeo(it.latitude, it.longitude) }
    }
}
