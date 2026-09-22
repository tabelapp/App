package br.com.tabelapp.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Centro histórico de Petrópolis — posição padrão enquanto o GPS não responde. */
    val PETROPOLIS_CENTRO = PontoGeo(-22.5046, -43.1823)

    /** Distância em linha reta (haversine), em km. Mesma fórmula de `distancia_km()` no banco. */
    fun distanciaKm(a: PontoGeo, b: PontoGeo): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return 6371.0 * 2 * asin(sqrt(h))
    }

    fun distanciaKm(de: PontoGeo?, ate: PontoGeo?): Double? =
        if (de == null || ate == null) null else distanciaKm(de, ate)

    /** 0.35 -> "350 m", 2.345 -> "2,3 km" */
    fun formatarDistancia(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt() / 10 * 10} m"
        else String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f km", km)
}
