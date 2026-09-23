package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncarteTest {
    private val hoje = LocalDate.parse("2026-09-23")

    @Test fun `encarte valido com loja cadastrada ou nome livre, validade opcional`() {
        assertTrue(RascunhoEncarte(quantidadeFotos = 1, lojaId = "x").erros(hoje).isEmpty())
        assertTrue(RascunhoEncarte(quantidadeFotos = 2, pdvNome = "Feira", validade = hoje).erros(hoje).isEmpty())
    }

    @Test fun `erros de preenchimento`() {
        assertEquals(2, RascunhoEncarte().erros(hoje).size) // sem foto e sem estabelecimento
        assertEquals(1, RascunhoEncarte(quantidadeFotos = 6, lojaId = "x").erros(hoje).size)
        assertEquals(1, RascunhoEncarte(quantidadeFotos = 1, lojaId = "x", validade = hoje.minusDays(1)).erros(hoje).size)
    }

    @Test fun `status vem do codigo do banco`() {
        assertEquals(StatusEncarte.REJEITADO, StatusEncarte.doCodigo("rejeitado"))
        assertEquals(StatusEncarte.PENDENTE, StatusEncarte.doCodigo("???"))
    }
}
