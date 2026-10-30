package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MinhasNotasTest {
    private val hoje = LocalDate.parse("2026-10-30")
    private fun l(nota: String, produto: String, preco: Long, enviada: Long, naBusca: Boolean = true, pdv: String? = "Padaria") =
        LinhaMinhaNota(nota, hoje, Instant.ofEpochSecond(enviada), pdv, "Rua A, 1", produto, preco,
            validade = hoje.plusDays(7), naBusca = naBusca)

    @Test fun `agrupa por nota, mais recente primeiro, itens em ordem`() {
        val notas = MinhasNotas.agrupar(
            listOf(l("n1", "Pão", 1599, 100), l("n2", "Café", 1899, 200), l("n1", "Manteiga", 1099, 100))
        )
        assertEquals(listOf("n2", "n1"), notas.map { it.id })
        assertEquals(listOf("Manteiga", "Pão"), notas[1].itens.map { it.produto })
        assertEquals("2 itens", notas[1].quantidadeTexto)
        assertEquals("1 item", notas[0].quantidadeTexto)
        assertEquals("R$ 15,99", notas[1].itens[1].preco)
    }

    @Test fun `textos de data e situacao`() {
        val n = MinhasNotas.agrupar(listOf(l("n1", "Pão", 1599, 100))).single()
        assertEquals("Compra em 30/10/2026", n.dataTexto)
        assertEquals("Na busca até 06/11/2026", n.situacao)
        val fora = MinhasNotas.agrupar(listOf(l("n1", "Pão", 1599, 100, naBusca = false, pdv = null))).single()
        assertEquals("Fora da busca (preço de nota aparece por 7 dias)", fora.situacao)
        assertEquals("Estabelecimento sem nome", fora.pdvNome)
    }
}
