package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtePrecoTest {
    private fun cot(
        preco: Long = 598, normal: Long? = null, app: Boolean = false, fonte: Fonte = Fonte.USUARIO_ENCARTE,
        produto: String = "Café Pilão 500g", unidade: Unidade = Unidade.UNIDADE,
    ) = Cotacao(
        id = "1", produto = produto, precoCentavos = preco, validade = LocalDate.of(2026, 11, 20), obs = null,
        fonte = fonte, lojaId = null, pdvId = null, pdvNome = "Assaí", endereco = "Rua do Imperador, 100",
        criadoEm = Instant.EPOCH, unidade = unidade, precoNormalCentavos = normal, precoApp = app,
        dataNf = if (fonte == Fonte.USUARIO_NF) LocalDate.of(2026, 11, 5) else null,
    )

    @Test fun `dois precos - selo e normal riscado`() {
        val a = ArtePreco.conteudo(cot(598, 699, app = true))
        assertEquals("R$ 5,98", a.preco)
        assertTrue(a.seloApp)
        assertEquals("R$ 6,99", a.precoNormal)
    }

    @Test fun `so preco do app - selo sem normal`() {
        val a = ArtePreco.conteudo(cot(598, null, app = true))
        assertTrue(a.seloApp)
        assertNull(a.precoNormal)
    }

    @Test fun `nota fiscal - sem selo`() {
        val a = ArtePreco.conteudo(cot(598, null, app = true, fonte = Fonte.USUARIO_NF))
        assertFalse(a.seloApp)
        assertNull(a.precoNormal)
        assertTrue(a.detalhes.startsWith("Preço praticado dia 05/11/2026"))
    }

    @Test fun `preco comum, unidade kg e textos`() {
        val a = ArtePreco.conteudo(cot(1290, unidade = Unidade.KG))
        assertFalse(a.seloApp)
        assertEquals("/ kg", a.sufixoUnidade)
        assertEquals("Assaí", a.local)
        assertEquals("Rua do Imperador, 100", a.endereco)
        assertEquals("Oferta válida até 20/11/2026 · ${Obs.ENCARTE}", a.detalhes)
    }

    @Test fun `normal menor ou igual nao aparece`() {
        assertNull(ArtePreco.conteudo(cot(598, 598, app = true)).precoNormal)
    }
}
