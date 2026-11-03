package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrecoAppTest {
    private fun cotacao(
        preco: Long = 598, normal: Long? = 699, app: Boolean = true, fonte: Fonte = Fonte.USUARIO_ENCARTE,
        unidade: Unidade = Unidade.UNIDADE,
    ) = Cotacao(
        id = "1", produto = "Leite Integral 1L", precoCentavos = preco, validade = LocalDate.parse("2026-11-10"),
        obs = null, fonte = fonte, lojaId = null, pdvId = null, pdvNome = "Assaí", endereco = "Rua A, 1",
        criadoEm = Instant.EPOCH, unidade = unidade, precoNormalCentavos = normal, precoApp = app,
    )

    @Test fun `cartao mostra preco do app, legenda e preco normal`() {
        val c = cotacao()
        assertTrue(c.temPrecoApp)
        assertEquals("R$ 5,98", PrecoExibido.texto(c))
        assertEquals("no app", PrecoExibido.LEGENDA_APP)
        assertEquals("R$ 6,99", PrecoExibido.textoNormal(c.precoNormalCentavos!!, c.unidade))
    }

    @Test fun `descricao para leitor de tela`() {
        assertEquals("R$ 5,98 no app. Preço normal R$ 6,99.", PrecoExibido.descricao(cotacao()))
        assertEquals("R$ 6,99", PrecoExibido.descricao(cotacao(preco = 699, normal = null, app = false)))
    }

    @Test fun `por quilo o normal tambem leva a unidade`() {
        val c = cotacao(preco = 5390, normal = 5790, unidade = Unidade.KG)
        assertEquals("R$ 53,90 / kg no app. Preço normal R$ 57,90 / kg.", PrecoExibido.descricao(c))
    }

    @Test fun `regra do selo no app (1_0_3)`() {
        // Dois preços: selo e normal riscado.
        assertTrue(cotacao().temPrecoApp)
        assertEquals(699L, cotacao().precoNormalRiscado)
        // Só o preço do app: selo, sem riscado.
        assertTrue(cotacao(normal = null).temPrecoApp)
        assertNull(cotacao(normal = null).precoNormalRiscado)
        assertEquals("R$ 5,98 no app.", PrecoExibido.descricao(cotacao(normal = null)))
        // Normal menor ou igual ao preço: selo, mas não risca.
        assertTrue(cotacao(normal = 598).temPrecoApp)
        assertNull(cotacao(normal = 598).precoNormalRiscado)
        assertNull(cotacao(normal = 500).precoNormalRiscado)
        // Sem preço no app: igual a antes.
        assertFalse(cotacao(app = false).temPrecoApp)
        assertNull(cotacao(app = false).precoNormalRiscado)
        // Nota Fiscal nunca tem "no app".
        assertFalse(cotacao(fonte = Fonte.USUARIO_NF).temPrecoApp)
        assertNull(cotacao(fonte = Fonte.USUARIO_NF).precoNormalRiscado)
        assertTrue(cotacao(fonte = Fonte.PDV_MANUAL).temPrecoApp, "preço oficial pode ter")
    }

    @Test fun `leitor de tela do card`() {
        assertEquals("Leite Integral 1L, Assaí, R$ 5,98 no app. Preço normal R$ 6,99.", PrecoExibido.descricaoCartao(cotacao()))
        assertEquals("Leite Integral 1L, Assaí, R$ 5,98", PrecoExibido.descricaoCartao(cotacao(app = false)))
    }

    @Test fun `compartilhar inclui os dois precos`() {
        val t = Compartilhamento.texto(cotacao(), link = "x")
        assertTrue("Leite Integral 1L: R$ 5,98 no app / R$ 6,99 normal em Assaí (Rua A, 1)" in t, t)
        val soApp = Compartilhamento.texto(cotacao(normal = null), link = "x")
        assertTrue("Leite Integral 1L: R$ 5,98 no app em Assaí" in soApp, soApp)
        val semApp = Compartilhamento.texto(cotacao(app = false), link = "x")
        assertTrue("Leite Integral 1L: R$ 5,98 em Assaí" in semApp, semApp)
    }

    @Test fun `item da pagina da loja`() {
        val i = ItemLoja("1", "Café 500g", 2389, LocalDate.parse("2026-11-10"), precoNormalCentavos = 2549, precoApp = true)
        assertTrue(i.temPrecoApp)
        assertEquals("R$ 25,49", i.precoNormal)
        assertEquals("R$ 23,89 no app. Preço normal R$ 25,49.", i.descricaoPreco)
        assertEquals(null, i.copy(precoApp = false).precoNormal)
        assertTrue(i.copy(precoNormalCentavos = null).temPrecoApp, "só o preço do app")
        assertEquals(null, i.copy(precoNormalCentavos = null).precoNormal)
    }
}
