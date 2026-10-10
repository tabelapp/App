package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncarteTest {
    private val hoje = LocalDate.parse("2026-09-23")
    private val item = listOf(ItemEncarte("Tomate", 499))

    @Test fun `encarte valido com loja cadastrada ou nome livre`() {
        assertTrue(RascunhoEncarte(1, lojaId = "x", validade = hoje, itens = item).erros(hoje).isEmpty())
        assertTrue(RascunhoEncarte(2, pdvNome = "Feira", validade = hoje.plusDays(30), itens = item).erros(hoje).isEmpty())
    }

    @Test fun `erros de preenchimento`() {
        // sem foto, sem estabelecimento, sem validade, sem produto
        assertEquals(4, RascunhoEncarte().erros(hoje).size)
        assertEquals(1, RascunhoEncarte(6, lojaId = "x", validade = hoje, itens = item).erros(hoje).size)
        assertEquals(1, RascunhoEncarte(1, lojaId = "x", validade = hoje.minusDays(1), itens = item).erros(hoje).size)
        assertEquals(1, RascunhoEncarte(1, lojaId = "x", validade = hoje.plusDays(31), itens = item).erros(hoje).size)
        assertEquals(1, RascunhoEncarte(1, lojaId = "x", validade = hoje).erros(hoje).size)
    }

    @Test fun `status vem do codigo do banco`() {
        assertEquals(StatusEncarte.APROVADO, StatusEncarte.doCodigo("aprovado"))
        assertEquals(StatusEncarte.PENDENTE, StatusEncarte.doCodigo("???"))
    }

    private fun l(texto: String, e: Int, t: Int, d: Int, b: Int, foto: Int = 0) = LinhaOcr(texto, e, t, d, b, foto)

    /** Encarte em duas colunas, como o OCR devolve: cada linha com a posição na foto. */
    private val encarte = listOf(
        l("OFERTAS VÁLIDAS DE 20/09 A 26/09", 50, 10, 900, 50),
        // coluna 1: nome em duas linhas, preço embaixo
        l("ARROZ TIO JOÃO", 50, 100, 400, 130),
        l("TIPO 1 5KG", 50, 135, 250, 165),
        l("R$ 19,90", 50, 180, 300, 260),
        // coluna 2: centavos em fonte menor, separados da parte inteira
        l("FEIJÃO PRETO", 550, 100, 850, 130),
        l("KICALDO 1kg", 550, 135, 800, 165),
        l("7,", 550, 180, 650, 260),
        l("99", 655, 185, 720, 215),
        // nome e preço na mesma linha
        l("Leite Integral Itambé 1L 5,49", 550, 400, 950, 440),
        // preço por quilo
        l("Tomate", 50, 400, 250, 430),
        l("4,99 kg", 50, 440, 250, 520),
        l("Imagens meramente ilustrativas", 50, 900, 900, 930),
    )

    @Test fun `le produtos e precos pela posicao das linhas`() {
        val lido = LeitorEncarte.ler(encarte, hoje)
        assertEquals(
            listOf(
                ItemEncarte("ARROZ TIO JOÃO TIPO 1 5KG", 1990),
                ItemEncarte("FEIJÃO PRETO KICALDO 1kg", 799),
                ItemEncarte("Leite Integral Itambé 1L", 549),
                ItemEncarte("Tomate (kg)", 499),
            ),
            lido.itens,
        )
        assertEquals(LocalDate.parse("2026-09-26"), lido.validade)
    }

    @Test fun `preco antigo (de) e ignorado, vale o por`() {
        val lido = LeitorEncarte.ler(
            listOf(
                l("Picanha kg", 50, 100, 300, 130),
                l("De R$ 69,90", 50, 140, 200, 170),
                l("Por R$ 49,90", 50, 180, 300, 250),
            ),
            hoje,
        )
        assertEquals(listOf(ItemEncarte("Picanha kg", 4990)), lido.itens)
        assertNull(lido.validade)
    }

    @Test fun `mesmo produto em duas fotos entra uma vez`() {
        val lido = LeitorEncarte.ler(
            listOf(
                l("Café Pilão 500g 18,90", 0, 0, 500, 40, foto = 0),
                l("CAFE PILAO 500G 18,90", 0, 0, 500, 40, foto = 1),
            ),
            hoje,
        )
        assertEquals(1, lido.itens.size)
    }

    @Test fun `preco sem nome por perto e aviso sem preco ficam de fora`() {
        val lido = LeitorEncarte.ler(
            listOf(
                l("Aproveite!", 0, 0, 300, 40),
                l("9,99", 2000, 2000, 2100, 2040),
                l("Tomate", 0, 0, 200, 30, foto = 1),
            ),
            hoje,
        )
        assertTrue(lido.itens.isEmpty())
    }

    @Test fun `validade impressa no encarte`() {
        fun v(texto: String, dia: LocalDate = hoje) = LeitorEncarte.validade(texto, dia)
        assertEquals(LocalDate.parse("2026-09-30"), v("Válido até 30/09"))
        assertEquals(LocalDate.parse("2026-09-26"), v("Ofertas válidas de 20 a 26 de setembro"))
        assertEquals(LocalDate.parse("2026-10-05"), v("Promoção válida até o dia 05/10/2026"))
        assertEquals(LocalDate.parse("2026-09-27"), v("Ofertas de 22/09 - 27/09 ou enquanto durarem"))
        assertNull(v("Válido até 10/09"), "já passou")
        assertNull(v("Válido até 30/12"), "mais de 30 dias")
        assertNull(v("Tomate 4,99"))
        assertEquals(LocalDate.parse("2027-01-03"), v("válido até 03/01", LocalDate.parse("2026-12-28")))
    }
}
