package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstadosNfceTest {
    /** Chave válida (DV certo) da UF [uf]: os outros números são de exemplo. */
    private fun chave(uf: String): ChaveAcessoNfe {
        val base = uf + "2610" + "11222333000181" + "65" + "001" + "000000123" + "1" + "00000000"
        return ChaveAcessoNfe.deTexto(base + ChaveAcessoNfe.calcularDv(base))!!
    }

    @Test fun `RJ continua ligado e igual a antes`() {
        val rj = EstadosNfce.ativoDaChave(chave("33"))
        assertEquals(EstadosNfce.RJ, rj)
        assertEquals("https://www.fazenda.rj.gov.br/nfce/consulta", rj!!.urlConsultaChave)
        assertTrue(rj.ativo)
        assertEquals(listOf("fazenda.rj.gov.br"), EstadosNfce.dominiosAtivos)
    }

    @Test fun `SP e MG desligados ate chegar o HTML real`() {
        assertNull(EstadosNfce.ativoDaChave(chave("35")))
        assertNull(EstadosNfce.ativoDaChave(chave("31")))
        assertFalse(EstadosNfce.SP.ativo)
        assertFalse(EstadosNfce.MG.ativo)
        assertNull(EstadosNfce.ativoDaChave(chave("41")), "estado fora da lista")
        assertEquals("Por enquanto o Tabelapp lê notas do RJ. Outros estados chegam em breve.",
            EstadosNfce.MENSAGEM_OUTROS_ESTADOS)
    }
}
