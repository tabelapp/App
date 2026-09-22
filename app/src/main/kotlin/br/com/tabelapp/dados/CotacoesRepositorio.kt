package br.com.tabelapp.dados

import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.PontoGeo

interface CotacoesRepositorio {
    /**
     * @param termo null/vazio = últimos preços lançados (tela inicial nunca fica vazia).
     * @param posicao usada pelo servidor para calcular distância.
     */
    suspend fun buscar(termo: String?, posicao: PontoGeo?): List<Cotacao>
}
