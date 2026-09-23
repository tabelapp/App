package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlvaraTest {
    private val cnpj = "66666666000191"

    @Test fun `acha o CNPJ com ou sem pontuacao`() {
        assertTrue(Alvara.contemCnpj("ALVARÁ DE LICENÇA\nCNPJ: 66.666.666/0001-91\nRua do Imperador", cnpj))
        assertTrue(Alvara.contemCnpj("C.N.P.J. 66666666000191", "66.666.666/0001-91"))
        assertTrue(Alvara.contemCnpj("CNPJ 66.666.666 / 0001 - 91", cnpj))
    }

    @Test fun `tolera letras que o OCR confunde com numeros`() {
        assertTrue(Alvara.contemCnpj("CNPJ: 66.666.666/OOO1-9l", cnpj))
    }

    @Test fun `outro CNPJ ou texto sem CNPJ nao confere`() {
        assertFalse(Alvara.contemCnpj("CNPJ: 11.222.333/0001-81", cnpj))
        assertFalse(Alvara.contemCnpj("ALVARÁ DE FUNCIONAMENTO", cnpj))
        assertFalse(Alvara.contemCnpj("66.666.666 Rua 0001-91", cnpj), "número partido por palavra não vale")
        assertFalse(Alvara.contemCnpj("qualquer", "123"))
    }
}
