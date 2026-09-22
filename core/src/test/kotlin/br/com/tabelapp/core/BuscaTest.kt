package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BuscaTest {
    private val agora = Instant.parse("2026-09-22T12:00:00Z")
    private val hoje = LocalDate.parse("2026-09-22")

    @Test fun `sem termo traz os ultimos lancados primeiro`() {
        val r = DadosDemo.buscar(null, agora, hoje)
        assertEquals(r.maxOf { it.criadoEm }, r.first().criadoEm)
    }

    @Test fun `busca feijao acha 4 e o mais barato e o da NF`() {
        val r = DadosDemo.buscar("feijao", agora, hoje)
        assertEquals(4, r.size)
        assertEquals(setOf(r.first().id), Busca.idsMaisBaratos(r))
        assertEquals("Mercadinho Alto da Serra", r.first().pdvNome)
        assertEquals(false, r.first().pdvCadastrado)
    }

    @Test fun `empate no menor preco destaca todos`() {
        val r = DadosDemo.buscar("cerveja", agora, hoje)
        assertEquals(2, Busca.idsMaisBaratos(r).size) // duas lojas da rede a R$3,99
    }

    @Test fun ordenacoes() {
        val r = DadosDemo.buscar("cafe", agora, hoje)
        assertEquals(1899, Busca.ordenar(r, Ordenacao.MENOR_PRECO, null).first().precoCentavos)
        assertEquals(2150, Busca.ordenar(r, Ordenacao.MAIOR_PRECO, null).first().precoCentavos)
        assertEquals("Empório Itaipava", Busca.ordenar(r, Ordenacao.PDV_AZ, null).first().pdvNome)
        assertEquals(hoje.plusDays(15), Busca.ordenar(r, Ordenacao.VALIDADE, null).first().validade)

        // Em Itaipava, o mais perto é o Empório.
        val itaipava = PontoGeo(-22.39, -43.13)
        assertEquals("Empório Itaipava", Busca.ordenar(r, Ordenacao.MAIS_PERTO, itaipava).first().pdvNome)
    }

    @Test fun `mais perto deixa PDV sem localizacao por ultimo`() {
        val r = Busca.ordenar(DadosDemo.buscar("tomate", agora, hoje), Ordenacao.MAIS_PERTO, Geo.PETROPOLIS_CENTRO)
        assertEquals("Hortifruti Bingen", r.first().pdvNome)
        assertEquals("Sacolão Corrêas", r.last().pdvNome)
    }

    @Test fun distancia() {
        val km = Geo.distanciaKm(Geo.PETROPOLIS_CENTRO, PontoGeo(-22.3865, -43.1335))
        assert(km in 12.0..15.0) { "distância $km" }
        assertEquals("350 m", Geo.formatarDistancia(0.354))
        assertEquals("2,3 km", Geo.formatarDistancia(2.345))
    }
}
