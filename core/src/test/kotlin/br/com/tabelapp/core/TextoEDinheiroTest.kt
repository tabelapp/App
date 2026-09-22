package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextoEDinheiroTest {
    @Test fun normaliza() {
        assertEquals("feijao preto", Texto.normalizar("  Feijão   PRETO "))
        assertEquals("acucar", Texto.normalizar("Açúcar"))
    }

    @Test fun `busca exige todas as palavras sem ligar para acento`() {
        assertTrue(Texto.casaBusca("Feijão Preto 1kg", "feijao PRETO"))
        assertTrue(Texto.casaBusca("Arroz Branco Tipo 1 5kg", "arroz 5kg"))
        assertFalse(Texto.casaBusca("Feijão Preto 1kg", "feijão 5kg"))
        assertTrue(Texto.casaBusca("Qualquer", "   "))
    }

    @Test fun formata() {
        assertEquals("R$ 0,05", Dinheiro.formatar(5))
        assertEquals("R$ 12,90", Dinheiro.formatar(1290))
        assertEquals("R$ 1.234,56", Dinheiro.formatar(123456))
        assertEquals("R$ 1.000.000,00", Dinheiro.formatar(100000000))
    }

    @Test fun `le precos em varios formatos`() {
        assertEquals(1290, Dinheiro.parse("12,90"))
        assertEquals(1290, Dinheiro.parse("R$ 12,9"))
        assertEquals(1290, Dinheiro.parse("12.90"))
        assertEquals(123456, Dinheiro.parse("1.234,56"))
        assertEquals(123456, Dinheiro.parse("1,234.56"))
        assertEquals(123400, Dinheiro.parse("1.234"))
        assertEquals(1200, Dinheiro.parse("12"))
        assertNull(Dinheiro.parse(""))
        assertNull(Dinheiro.parse("abc"))
        assertNull(Dinheiro.parse("-5"))
        assertNull(Dinheiro.parse("1,234"))
    }
}
