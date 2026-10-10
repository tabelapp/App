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

class TextoListaTest {
    private val agora = Instant.parse("2026-09-22T12:00:00Z")
    private val hoje = LocalDate.parse("2026-09-22")
    private val itens = listOf(ItemLista("arroz 5kg"), ItemLista("feijão preto", quantidade = 2.0), ItemLista("caviar"))
    private val relatorio = RelatorioListaCompras(itens, itens.associateWith { DadosDemo.buscar(it.produto, agora, hoje) }, null)

    @Test fun `quantidade sem casas decimais desnecessarias`() {
        assertEquals("2", TextoLista.quantidade(2.0))
        assertEquals("1,5", TextoLista.quantidade(1.5))
    }

    @Test fun `mensagem do lugar unico mostra o total, os itens e o que falta`() {
        val t = TextoLista.porPdv("Feira", relatorio.pdvUnico(), totalItens = 3)
        assertTrue(t.startsWith("🛒 *Feira*"), t)
        assertTrue("*1º Mercadinho Alto da Serra* — R$ 38,67 (2 de 3 itens)" in t, t)
        assertTrue("(2 de 3 itens)" in t, t)
        assertTrue("Faltam: caviar" in t, t)
        assertTrue(t.endsWith(Compartilhamento.LINK_APP))
    }

    @Test fun `mensagem item a item mostra o local de cada um e o total`() {
        val t = TextoLista.porItem("Feira", relatorio.melhorPorItem())
        assertTrue("• 2x feijão preto: R$ 15,18 (R$ 7,59 cada) — " in t, t)
        assertTrue("*Total: R$ 38,08* em 2 lugares" in t, t)
        assertTrue("Sem preço encontrado: caviar" in t, t)
    }
}
