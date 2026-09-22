package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ObsTest {
    @Test fun `nota fiscal mostra NF`() =
        assertEquals("NF", Obs.exibir(Fonte.USUARIO_NF, "qualquer coisa"))

    @Test fun `encarte de usuario mostra produto de encarte`() =
        assertEquals("produto de encarte", Obs.exibir(Fonte.USUARIO_ENCARTE, null))

    @Test fun `PDV mostra o proprio texto`() {
        assertEquals("cerveja gelada", Obs.exibir(Fonte.PDV_MANUAL, "cerveja gelada"))
        assertEquals("entrega grátis", Obs.exibir(Fonte.PDV_EXCEL, "  entrega grátis "))
    }

    @Test fun `PDV sem texto mostra Preco oficial`() {
        assertEquals("Preço oficial", Obs.exibir(Fonte.PDV_MANUAL, null))
        assertEquals("Preço oficial", Obs.exibir(Fonte.PDV_EXCEL, "   "))
    }
}
