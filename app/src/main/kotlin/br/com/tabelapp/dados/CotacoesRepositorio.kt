package br.com.tabelapp.dados

import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.ConfigConfianca
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

    /** Parâmetros do selo "Menor preço lançado" (tabela configuracao_app); padrão se não der para ler. */
    suspend fun configConfianca(): ConfigConfianca

    /**
     * "Esse preço não confere". @param motivo combo_ou_promocao | preco_mudou | produto_errado | outro (opcional).
     * @return false se a pessoa já tinha avisado sobre este preço.
     */
    suspend fun denunciar(cotacaoId: String, motivo: String?): Boolean

    /**
     * "Denunciar este banner". @param motivo enganoso | ofensivo | golpe | outro (opcional).
     * @return false se a pessoa já tinha denunciado este banner.
     */
    suspend fun denunciarBanner(promocaoId: String, motivo: String?): Boolean
}
