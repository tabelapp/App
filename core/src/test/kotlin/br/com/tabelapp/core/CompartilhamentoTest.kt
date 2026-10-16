package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class CompartilhamentoTest {
    @Test fun `texto tem o produto, o preco, o local e o link`() {
        val c = Cotacao(
            id = "1", produto = "Arroz 5kg", precoCentavos = 2490, validade = LocalDate.parse("2026-10-10"),
            obs = null, fonte = Fonte.PDV_MANUAL, lojaId = "l", pdvId = "p", pdvNome = "Mercado Bom",
            endereco = "Rua A, 1 - Centro", criadoEm = Instant.EPOCH,
        )
        val t = Compartilhamento.texto(c, link = "https://exemplo.invalid/app")
        assertTrue(t.startsWith("Olha esse preço!"))
        assertTrue("Arroz 5kg: R$ 24,90 em Mercado Bom (Rua A, 1 - Centro)" in t)
        assertTrue("Quem pesquisa economiza. Pesquise mais preços com o Tabelapp" in t)
        assertTrue(t.endsWith("https://exemplo.invalid/app"))
    }
}
