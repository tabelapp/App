package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrigemPrecoTest {
    @Test fun `cada fonte vira uma das tres origens`() {
        assertEquals(OrigemPreco.NOTA_FISCAL, OrigemPreco.de(Fonte.USUARIO_NF))
        assertEquals(OrigemPreco.ENCARTE, OrigemPreco.de(Fonte.USUARIO_ENCARTE))
        assertEquals(OrigemPreco.TABELA_DA_LOJA, OrigemPreco.de(Fonte.PDV_MANUAL))
        assertEquals(OrigemPreco.TABELA_DA_LOJA, OrigemPreco.de(Fonte.PDV_EXCEL))
    }

    @Test fun `pelo codigo do banco`() {
        assertEquals(OrigemPreco.NOTA_FISCAL, OrigemPreco.doCodigo("usuario_nf"))
        assertEquals(OrigemPreco.ENCARTE, OrigemPreco.doCodigo("usuario_encarte"))
        assertEquals(OrigemPreco.TABELA_DA_LOJA, OrigemPreco.doCodigo("pdv_excel"))
    }

    @Test fun `desconhecido ou vazio - sem icone`() {
        assertNull(OrigemPreco.doCodigo("algo_novo"))
        assertNull(OrigemPreco.doCodigo(""))
        assertNull(OrigemPreco.doCodigo(null))
        assertNull(OrigemPreco.de(null))
    }

    @Test fun `textos ao tocar`() {
        assertEquals("Preço de nota fiscal", OrigemPreco.NOTA_FISCAL.texto)
        assertEquals("Preço de encarte", OrigemPreco.ENCARTE.texto)
        assertEquals("Preço da tabela da loja", OrigemPreco.TABELA_DA_LOJA.texto)
    }
}
