package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanilhaTest {
    private val hoje = LocalDate.parse("2026-09-22")

    @Test fun `linhas validas e invalidas`() {
        val r = Planilha.validar(
            listOf(
                listOf("Arroz 5kg", "24,90", "", "Oferta"),
                listOf("Feijão 1kg", "R$ 7,89", "10/10/2026", null),
                listOf("", "", "", ""),                       // em branco: ignorada
                listOf("Café", "abc"),                        // preço inválido
                listOf("Açúcar", "4,89", "2026-11-30"),       // > 30 dias
                listOf("arroz  5KG", "25,00"),                // repetido
                listOf("Leite", "5,69", "21/09/2026"),        // passado
            ),
            hoje,
        )
        assertEquals(listOf("Arroz 5kg", "Feijão 1kg"), r.linhas.map { it.produto })
        assertEquals(hoje.plusDays(30), r.linhas[0].validade)
        assertEquals(789, r.linhas[1].precoCentavos)
        assertEquals(listOf(5, 6, 7, 8), r.erros.map { it.numero })
        assertTrue(r.erros.first { it.numero == 7 }.mensagem.contains("linha 2"))
    }
}
