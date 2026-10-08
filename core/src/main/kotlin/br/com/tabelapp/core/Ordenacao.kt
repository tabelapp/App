package br.com.tabelapp.core

/** Ordenações da tela de busca (briefing, seção 2; "Validade" retirada a pedido do fundador). */
enum class Ordenacao(val rotulo: String) {
    MENOR_PRECO("Menor preço"),
    MAIOR_PRECO("Maior preço"),
    MAIS_PERTO("Mais perto"),
    PDV_AZ("PDV (A-Z)");
}

object Busca {

    fun ordenar(cotacoes: List<Cotacao>, ordenacao: Ordenacao, posicao: PontoGeo?): List<Cotacao> {
        val porPreco = compareBy<Cotacao> { it.precoCentavos }
        val comparador: Comparator<Cotacao> = when (ordenacao) {
            Ordenacao.MENOR_PRECO -> porPreco
            Ordenacao.MAIOR_PRECO -> compareByDescending<Cotacao> { it.precoCentavos }
            // Sem localização conhecida do PDV vai para o fim.
            Ordenacao.MAIS_PERTO -> compareBy<Cotacao, Double?>(nullsLast()) {
                Geo.distanciaKm(posicao, it.local)
            }.then(porPreco)
            Ordenacao.PDV_AZ -> compareBy<Cotacao> { Texto.normalizar(it.pdvNome) }.then(porPreco)
        }
        return cotacoes.sortedWith(comparador)
    }

    /**
     * Ids das cotações com o menor preço do resultado (empates são todos destacados).
     * Só faz sentido destacar quando o usuário buscou algo — na tela inicial
     * (últimos preços, produtos variados) não há "mais barato".
     */
    fun idsMaisBaratos(cotacoes: List<Cotacao>): Set<String> {
        val menor = cotacoes.minOfOrNull { it.precoCentavos } ?: return emptySet()
        return cotacoes.filter { it.precoCentavos == menor }.mapTo(mutableSetOf()) { it.id }
    }
}
