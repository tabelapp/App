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

class CadastroPdvTest {
    private val base = CadastroPdv(
        cnpj = "66.666.666/0001-91", nomeFantasia = "Padaria", razaoSocial = null,
        endereco = "Rua A, 1", bairro = "", cidade = "Petrópolis", uf = "RJ", cep = "", telefone = "",
    )

    @Test fun `qualquer situacao na Receita pode cadastrar, desde que tenha alvara`() {
        assertTrue(base.erros(temAlvara = true).isEmpty())
        assertTrue(base.erros(temAlvara = false).size == 1)
        assertTrue(base.copy(cnpj = "123", nomeFantasia = " ").erros(temAlvara = true).size == 2)
    }
}

class RascunhoPrecoTest {
    private val hoje = java.time.LocalDate.parse("2026-09-24")

    @Test fun `item valido e erros de preenchimento`() {
        assertTrue(RascunhoPreco("Arroz 5kg", "24,90").erros(hoje).isEmpty())
        assertTrue(RascunhoPreco("Arroz 5kg", "24,90", hoje.plusDays(30), "Oferta").erros(hoje).isEmpty())
        assertTrue(RascunhoPreco("", "0").erros(hoje).size == 2)
        assertTrue(RascunhoPreco("Arroz", "1", hoje.plusDays(31)).erros(hoje).size == 1)
        assertTrue(RascunhoPreco("Arroz", "1", hoje.minusDays(1)).erros(hoje).size == 1)
        assertTrue(RascunhoPreco("Arroz", "1", obs = "x".repeat(141)).erros(hoje).size == 1)
    }

    @Test fun `editar comeca com os dados do item`() {
        val r = RascunhoPreco.de(PrecoPdv("1", "l", "Arroz", 123456, hoje, null))
        assertTrue(r.preco == "1.234,56" && r.precoCentavos == 123456L)
    }
}
