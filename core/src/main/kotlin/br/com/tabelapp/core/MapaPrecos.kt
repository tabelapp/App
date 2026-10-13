package br.com.tabelapp.core

/**
 * "Ver no mapa" da busca: um pino por ponto de venda, com o menor preço do
 * resultado naquele local (e quantos outros itens há ali).
 */
data class PinoMapa(
    val chave: String,
    val pdvNome: String,
    val endereco: String?,
    /** Coordenada do cadastro da loja; null = precisa localizar pelo endereço. */
    val local: PontoGeo?,
    /** Itens do resultado neste local, do mais barato ao mais caro. */
    val cotacoes: List<Cotacao>,
    /** Tem o menor preço de todo o resultado. */
    val maisBarato: Boolean,
) {
    val menorPreco: Long get() = cotacoes.first().precoCentavos

    /** Texto do pino: "R$ 4,99" ou "R$ 4,99 +2". */
    val rotulo: String
        get() = Dinheiro.formatar(menorPreco) + if (cotacoes.size > 1) " +${cotacoes.size - 1}" else ""

    /** Endereço para localizar no mapa (o Geocoder precisa da cidade/UF). */
    val enderecoParaBusca: String?
        get() = endereco?.let { MapaPrecos.enderecoParaGeocoder(it) }
}

object MapaPrecos {

    /** Endereço completo para o Geocoder (sem cidade, assume Petrópolis - RJ); null = vazio. */
    fun enderecoParaGeocoder(endereco: String): String? = endereco.takeIf { it.isNotBlank() }?.let { e ->
        val n = Texto.normalizar(e)
        if (n.contains("petropolis") || Regex("\\brj\\b").containsMatchIn(n)) "$e, Brasil" else "$e, Petrópolis - RJ, Brasil"
    }

    /** Coordenada plausível para uma loja (dentro do Brasil; descarta 0,0 e latitude/longitude trocadas). */
    fun coordenadaValida(p: PontoGeo): Boolean = p.latitude in -34.0..6.0 && p.longitude in -74.0..-28.0

    /** @param idsSelo quem tem o selo "Menor preço lançado" na busca (null = o menor preço do resultado). */
    fun pinos(cotacoes: List<Cotacao>, idsSelo: Set<String>? = null): List<PinoMapa> {
        val menor = cotacoes.minOfOrNull { it.precoCentavos }
        return cotacoes.groupBy { it.chaveLocal }.map { (chave, itens) ->
            val ordenados = itens.sortedBy { it.precoCentavos }
            val ref = ordenados.first()
            PinoMapa(
                chave = chave,
                pdvNome = ref.pdvNome,
                endereco = itens.firstNotNullOfOrNull { it.endereco?.takeIf(String::isNotBlank) },
                local = itens.firstNotNullOfOrNull { it.local },
                cotacoes = ordenados,
                maisBarato = if (idsSelo != null) itens.any { it.id in idsSelo } else ref.precoCentavos == menor,
            )
        }.sortedBy { it.menorPreco }
    }
}
