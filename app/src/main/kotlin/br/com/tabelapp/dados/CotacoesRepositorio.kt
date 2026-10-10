package br.com.tabelapp.dados

import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.PontoGeo

interface CotacoesRepositorio {
    /**
     * @param termo null/vazio = últimos preços lançados (tela inicial nunca fica vazia).
     * @param posicao usada pelo servidor para calcular distância.
     */
    suspend fun buscar(termo: String?, posicao: PontoGeo?): List<Cotacao>

    /** Banners patrocinados para o termo/posição (segmentação por palavra-chave e raio). */
    suspend fun banners(termo: String?, posicao: PontoGeo?): List<Banner>

    /** Conta uma visualização do banner (ao esgotar o pacote, ele sai do ar). */
    suspend fun registrarVisualizacao(bannerId: String)
}
