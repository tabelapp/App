package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoricoPrecoTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun p(data: String, c: Long) = PontoPreco(d(data), c)

    @Test fun `serie vazia`() {
        val s = SerieHistorico.montar(emptyList())
        assertFalse(s.temGrafico)
        assertNull(s.menor)
        assertNull(s.variacaoPercentual)
        assertNull(s.variacaoTexto)
        assertEquals(emptyList(), s.valoresEixo)
        assertEquals("Sem histórico de preço deste produto.", s.descricao)
    }

    @Test fun `um ponto so`() {
        val s = SerieHistorico.montar(listOf(p("2026-11-01", 599)))
        assertFalse(s.temGrafico)
        assertEquals(599, s.menor)
        assertNull(s.variacaoTexto)
        assertEquals("Um único preço registrado até agora: R$ 5,99 em 01/11.", s.descricao)
    }

    @Test fun `queda como no mockup`() {
        val s = SerieHistorico.montar(listOf(
            p("2026-10-05", 649), p("2026-10-12", 629), p("2026-10-19", 649), p("2026-10-26", 629), p("2026-11-01", 599),
        ))
        assertTrue(s.temGrafico)
        assertEquals(599, s.menor)
        assertEquals(649, s.maior)
        assertEquals(SerieHistorico.Tendencia.QUEDA, s.tendencia)
        assertEquals("▼ 7,7% em 4 semanas", s.variacaoTexto)
        assertEquals("Preço de R$ 6,49 em 05/10 para R$ 5,99 em 01/11, queda de 7,7%.", s.descricao)
        assertEquals(listOf(599L, 624L, 649L), s.valoresEixo)
    }

    @Test fun `subida`() {
        val s = SerieHistorico.montar(listOf(p("2026-10-26", 500), p("2026-11-01", 550)))
        assertEquals(SerieHistorico.Tendencia.ALTA, s.tendencia)
        assertEquals("▲ 10,0% em 6 dias", s.variacaoTexto)
        assertEquals("Preço de R$ 5,00 em 26/10 para R$ 5,50 em 01/11, alta de 10,0%.", s.descricao)
    }

    @Test fun `precos iguais variacao zero`() {
        val s = SerieHistorico.montar(listOf(p("2026-10-01", 599), p("2026-10-15", 599)))
        assertEquals(0.0, s.variacaoPercentual)
        assertEquals(SerieHistorico.Tendencia.ESTAVEL, s.tendencia)
        assertEquals("0,0% em 2 semanas", s.variacaoTexto)
        assertEquals(listOf(599L), s.valoresEixo)
        assertTrue(s.descricao.endsWith("sem variação."))
    }

    @Test fun `dois lancamentos no mesmo dia ficam com o ultimo`() {
        val s = SerieHistorico.montar(listOf(p("2026-10-31", 700), p("2026-10-30", 650), p("2026-10-31", 690)))
        assertEquals(listOf(650L, 690L), s.pontos.map { it.centavos })
        assertEquals(listOf(d("2026-10-30"), d("2026-10-31")), s.pontos.map { it.data })
        assertEquals("▲ 6,2% em 1 dia", s.variacaoTexto)
    }

    @Test fun `no maximo 30 pontos, os mais recentes`() {
        val inicio = d("2026-08-01")
        val s = SerieHistorico.montar((0L until 40L).map { PontoPreco(inicio.plusDays(it), 500 + it) })
        assertEquals(30, s.pontos.size)
        assertEquals(inicio.plusDays(10), s.pontos.first().data)
    }
}
