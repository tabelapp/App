package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListaComprasTest {
    private val agora = Instant.parse("2026-09-22T12:00:00Z")
    private val hoje = LocalDate.parse("2026-09-22")

    private val arroz = ItemLista("arroz 5kg")
    private val feijao = ItemLista("feijão preto", quantidade = 2.0)
    private val oleo = ItemLista("óleo de soja")
    private val itens = listOf(arroz, feijao, oleo)

    private fun relatorio(lista: List<ItemLista> = itens) = RelatorioListaCompras(
        lista,
        lista.associateWith { DadosDemo.buscar(it.produto, agora, hoje) },
        Geo.PETROPOLIS_CENTRO,
    )

    @Test fun `PDV unico com todos os itens vem primeiro`() {
        val opcoes = relatorio().pdvUnico()
        val primeira = opcoes.first()
        assertEquals("Mercado Quitandinha", primeira.pdvNome)
        assertTrue(primeira.completo)
        assertEquals(2290L + 2 * 849 + 749, primeira.totalCentavos)
        assertTrue(opcoes.drop(1).all { !it.completo })
    }

    @Test fun `melhor por item mistura PDVs`() {
        val r = relatorio().melhorPorItem()
        assertEquals(listOf(2290L, 759L, 749L), r.itens.map { it.cotacao.precoCentavos })
        assertEquals(2290L + 2 * 759 + 749, r.totalCentavos)
        assertEquals(2, r.quantidadePdvs)
        assertTrue(r.semPreco.isEmpty())
    }

    @Test fun `item sem preco aparece separado`() {
        val caviar = ItemLista("caviar")
        val r = relatorio(listOf(arroz, caviar)).melhorPorItem()
        assertEquals(listOf(caviar), r.semPreco)
    }

    @Test fun `mapa so tem PDVs com localizacao, do mais perto ao mais longe`() {
        val pontos = relatorio().mapa()
        // O feijão mais barato é de PDV não cadastrado (sem coordenadas): fica fora do mapa.
        assertEquals(listOf("Mercado Quitandinha"), pontos.map { it.pdvNome })
        assertEquals(2, pontos.single().itens.size)
    }
}
