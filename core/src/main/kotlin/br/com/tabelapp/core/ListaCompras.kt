package br.com.tabelapp.core

import kotlin.math.roundToLong

data class ItemLista(val produto: String, val quantidade: Double = 1.0) {
    init {
        require(produto.isNotBlank()) { "Produto vazio" }
        require(quantidade > 0) { "Quantidade deve ser positiva" }
    }
}

data class ItemCotado(val item: ItemLista, val cotacao: Cotacao) {
    val subtotalCentavos: Long get() = (cotacao.precoCentavos * item.quantidade).roundToLong()
}

/** Relatório 1: tudo num lugar só. */
data class OpcaoPdvUnico(
    val chaveLocal: String,
    val pdvNome: String,
    val endereco: String?,
    val pdvCadastrado: Boolean,
    val encontrados: List<ItemCotado>,
    val faltando: List<ItemLista>,
    val distanciaKm: Double?,
) {
    val totalCentavos: Long get() = encontrados.sumOf { it.subtotalCentavos }
    val completo: Boolean get() = faltando.isEmpty()
}

/** Relatório 2: menor preço de cada item, mesmo que em PDVs diferentes. */
data class MelhorPorItem(
    val itens: List<ItemCotado>,
    val semPreco: List<ItemLista>,
) {
    val totalCentavos: Long get() = itens.sumOf { it.subtotalCentavos }
    val quantidadePdvs: Int get() = itens.map { it.cotacao.chaveLocal }.distinct().size
}

/** Relatório 3: pontos no mapa (só PDVs com localização). Sem fórmula de deslocamento — o usuário decide. */
data class PontoMapa(
    val chaveLocal: String,
    val pdvNome: String,
    val local: PontoGeo,
    val distanciaKm: Double?,
    val itens: List<ItemCotado>,
) {
    val totalCentavos: Long get() = itens.sumOf { it.subtotalCentavos }
}

/**
 * "Buscar melhores preços" da lista de compras (briefing, seção 3).
 *
 * @param cotacoesPorItem resultado da busca de cada item da lista.
 * @param posicao posição do usuário (para distância).
 */
class RelatorioListaCompras(
    private val itens: List<ItemLista>,
    cotacoesPorItem: Map<ItemLista, List<Cotacao>>,
    private val posicao: PontoGeo?,
) {
    // Por item, só o preço mais barato de cada local (um local pode ter várias marcas que casam).
    private val maisBaratoPorLocal: Map<ItemLista, Map<String, Cotacao>> = itens.associateWith { item ->
        cotacoesPorItem[item].orEmpty()
            .groupBy { it.chaveLocal }
            .mapValues { (_, lista) -> lista.minBy { it.precoCentavos } }
    }

    fun pdvUnico(): List<OpcaoPdvUnico> {
        val locais = maisBaratoPorLocal.values.flatMap { it.values }.associateBy { it.chaveLocal }
        return locais.map { (chave, exemplo) ->
            val encontrados = itens.mapNotNull { item ->
                maisBaratoPorLocal.getValue(item)[chave]?.let { ItemCotado(item, it) }
            }
            OpcaoPdvUnico(
                chaveLocal = chave,
                pdvNome = exemplo.pdvNome,
                endereco = exemplo.endereco,
                pdvCadastrado = exemplo.pdvCadastrado,
                encontrados = encontrados,
                faltando = itens.filter { item -> maisBaratoPorLocal.getValue(item)[chave] == null },
                distanciaKm = Geo.distanciaKm(posicao, exemplo.local),
            )
        }.sortedWith(
            // Primeiro quem tem tudo; depois quem tem mais itens; empate -> mais barato.
            compareBy<OpcaoPdvUnico> { it.faltando.size }.thenBy { it.totalCentavos }
        )
    }

    fun melhorPorItem(): MelhorPorItem {
        val cotados = mutableListOf<ItemCotado>()
        val semPreco = mutableListOf<ItemLista>()
        for (item in itens) {
            val melhor = maisBaratoPorLocal.getValue(item).values.minByOrNull { it.precoCentavos }
            if (melhor == null) semPreco += item else cotados += ItemCotado(item, melhor)
        }
        return MelhorPorItem(cotados, semPreco)
    }

    /** Pontos do relatório "melhor por item", agrupados por local, para desenhar no mapa. */
    fun mapa(): List<PontoMapa> =
        melhorPorItem().itens
            .filter { it.cotacao.local != null }
            .groupBy { it.cotacao.chaveLocal }
            .map { (chave, cotados) ->
                val c = cotados.first().cotacao
                PontoMapa(chave, c.pdvNome, c.local!!, Geo.distanciaKm(posicao, c.local), cotados)
            }
            .sortedWith(compareBy(nullsLast()) { it.distanciaKm })
}
