package br.com.tabelapp.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
