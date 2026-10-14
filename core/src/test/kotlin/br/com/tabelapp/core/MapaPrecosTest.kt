package br.com.tabelapp.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapaPrecosTest {
    private fun c(id: String, preco: Long, pdv: String, endereco: String?, local: PontoGeo? = null, loja: String? = null) =
        Cotacao(id, "Arroz $id", preco, null, null, Fonte.USUARIO_NF, loja, null, pdv, endereco = endereco,
            local = local, criadoEm = Instant.EPOCH)

    @Test fun `um pino por local com o menor preco`() {
        val pinos = MapaPrecos.pinos(listOf(
            c("a", 2490, "Mercado A", "Rua 1, 10"),
            c("b", 2290, "Mercado A", "Rua 1, 10"),
            c("c", 2590, "Mercado B", null, PontoGeo(-22.5, -43.1), loja = "loja-b"),
        ))
        assertEquals(2, pinos.size)
        assertEquals("Mercado A", pinos[0].pdvNome)
        assertEquals(2290, pinos[0].menorPreco)
        assertTrue(pinos[0].maisBarato)
        assertEquals(Dinheiro.formatar(2290) + " +1", pinos[0].rotulo)
        assertEquals(Dinheiro.formatar(2590), pinos[1].rotulo)
        assertEquals(PontoGeo(-22.5, -43.1), pinos[1].local)
    }

    @Test fun `endereco para localizar ganha a cidade quando falta`() {
        assertEquals("Rua 1, 10, Petrópolis - RJ, Brasil", MapaPrecos.pinos(listOf(c("a", 1, "X", "Rua 1, 10")))[0].enderecoParaBusca)
        assertEquals("Rua 2, Centro, Petrópolis - RJ, Brasil",
            MapaPrecos.pinos(listOf(c("a", 1, "X", "Rua 2, Centro, Petrópolis - RJ")))[0].enderecoParaBusca)
        assertNull(MapaPrecos.pinos(listOf(c("a", 1, "X", " ")))[0].enderecoParaBusca)
    }

    @Test fun `endereco de outra cidade nao ganha Petropolis`() {
        assertEquals("Rua da Praia, 5, Icaraí, Niterói - RJ, Brasil", MapaPrecos.enderecoParaGeocoder("Rua da Praia, 5, Icaraí, Niterói - RJ"))
        assertEquals("Av. Paulista, 1000, São Paulo/SP, Brasil", MapaPrecos.enderecoParaGeocoder("Av. Paulista, 1000, São Paulo/SP"))
        // "se" e "to" soltos no nome da rua não são UF.
        assertEquals("Rua Se Toque, 5, Petrópolis - RJ, Brasil", MapaPrecos.enderecoParaGeocoder("Rua Se Toque, 5"))
    }

    @Test fun `loja usa o mesmo endereco completo para o mapa`() {
        val loja = LojaPdv("l1", null, "Rua do Imperador, 500, Centro, Petrópolis - RJ", null)
        assertEquals("Rua do Imperador, 500, Centro, Petrópolis - RJ, Brasil", loja.enderecoParaMapa)
    }

    @Test fun `coordenada da loja precisa estar no Brasil`() {
        assertTrue(MapaPrecos.coordenadaValida(PontoGeo(-22.5058, -43.1790)))
        assertFalse(MapaPrecos.coordenadaValida(PontoGeo(0.0, 0.0)))
        assertFalse(MapaPrecos.coordenadaValida(PontoGeo(-43.17, -22.5)), "latitude e longitude trocadas")
    }
}
