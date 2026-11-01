package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginaLojaTest {
    private val loja = PaginaLoja(
        lojaId = "l1", pdvId = "p1", pdvNome = "Mercado Bom", lojaNome = "Loja Centro",
        endereco = "Rua A, 1, Centro, Petrópolis - RJ", telefone = "(24) 99999-0000", site = "https://exemplo.invalid",
    )

    @Test fun `titulo junta comercio e loja`() {
        assertEquals("Mercado Bom — Loja Centro", loja.titulo)
        assertEquals("Mercado Bom", loja.copy(lojaNome = " ").titulo)
    }

    @Test fun `compartilhar loja tem nome, endereco, whatsapp, site e link`() {
        val t = CompartilharLoja.texto(loja, link = "https://exemplo.invalid/app")
        assertTrue(t.startsWith("Conheça Mercado Bom — Loja Centro no Tabelapp!"))
        assertTrue("📍 Rua A, 1, Centro, Petrópolis - RJ" in t)
        assertTrue("💬 WhatsApp: (24) 99999-0000" in t)
        assertTrue("🌐 https://exemplo.invalid" in t)
        assertTrue(t.endsWith("https://exemplo.invalid/app"))
    }

    @Test fun `compartilhar loja sem contato nao deixa linha vazia`() {
        val t = CompartilharLoja.texto(loja.copy(telefone = null, site = null, endereco = null), link = "x")
        assertFalse("WhatsApp" in t)
        assertFalse("📍" in t)
    }

    @Test fun `item da loja formata preco e validade`() {
        val i = ItemLoja("1", "Alcatra", 5390, LocalDate.parse("2026-11-10"), unidade = Unidade.KG)
        assertEquals("R$ 53,90 / kg", i.preco)
        assertEquals("Oferta válida até 10/11/2026", i.validadeTexto)
    }
}
