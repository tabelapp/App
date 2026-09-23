package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotaFiscalTest {
    private fun fixture(nome: String) =
        javaClass.getResource("/nfce/$nome")!!.readText()

    @Test fun `le emitente e itens da pagina de consulta`() {
        val nota = assertNotNull(LeitorNfce.ler(fixture("consulta_rj.html")))
        assertEquals("MERCADINHO ALTO DA SERRA LTDA", nota.emitenteNome)
        assertEquals("11111111000191", nota.emitenteCnpj)
        assertEquals("RUA TERESA, 1500, ALTO DA SERRA, PETROPOLIS, RJ", nota.emitenteEndereco)
        assertEquals(
            listOf(
                ItemNota("ARROZ BRANCO TIPO 1 5KG", 2349, 2.0, "UN"),
                ItemNota("BANANA PRATA KG", 699, 1.235, "KG"),
                // Sem valor unitário na página: total / quantidade (47,88 / 12).
                ItemNota("CERVEJA PILSEN LATA 350ML", 399, 12.0, "UN"),
            ),
            nota.itens,
        )
    }

    @Test fun `pagina sem itens (carregando, captcha, erro) retorna null`() {
        assertNull(LeitorNfce.ler("<html><body><div>Aguarde...</div></body></html>"))
        assertNull(LeitorNfce.ler("<html><body><table id='tabResult'></table></body></html>"))
    }

    @Test fun `rascunho valida e junta linhas repetidas`() {
        val base43 = "3326091111111100019165001000001234100001234"
        val chave = base43 + ChaveAcessoNfe.calcularDv(base43)
        val ok = RascunhoNf(
            chaveAcesso = chave,
            pdvNome = "Mercadinho",
            itens = listOf(
                ItemNota("Leite 1L", 569),
                ItemNota("LEITE 1L ", 569),
                ItemNota("Pão", 1690),
            ),
        )
        assertTrue(ok.erros().isEmpty())
        assertEquals(listOf(ItemNota("Leite 1L", 569, 2.0), ItemNota("Pão", 1690)), ok.itensParaEnvio())

        val ruim = RascunhoNf(chaveAcesso = "123", itens = listOf(ItemNota("", 0)))
        assertEquals(4, ruim.erros().size)
        // Loja cadastrada dispensa nome digitado; chave é opcional.
        assertTrue(RascunhoNf(lojaId = "x", itens = listOf(ItemNota("Pão", 100))).erros().isEmpty())
    }
}

class DemoNfTest {
    @Test fun `CNPJ da chave encontra as lojas de demonstracao`() {
        assertEquals(2, DadosDemo.lojasDoCnpj("11.111.111/0001-91").size)
        assertEquals("Mercado Quitandinha", DadosDemo.lojasDoCnpj("22222222000191").single().pdvNome)
        assertTrue(DadosDemo.lojasDoCnpj("99999999000191").isEmpty())
    }

    @Test fun `preco enviado na demonstracao aparece na busca enquanto valido`() {
        val hoje = java.time.LocalDate.parse("2026-09-22")
        val agora = java.time.Instant.parse("2026-09-22T12:00:00Z")
        val extra = DadosDemo.cotacoes(agora, hoje).first().copy(
            id = "nf-1", produto = "Manteiga 200g", fonte = Fonte.USUARIO_NF, validade = hoje.plusDays(1), criadoEm = agora,
        )
        assertEquals("nf-1", DadosDemo.buscar(null, agora, hoje, listOf(extra)).first().id)
        assertEquals(1, DadosDemo.buscar("manteiga", agora, hoje, listOf(extra)).size)
        assertTrue(DadosDemo.buscar("manteiga", agora, hoje.plusDays(2), listOf(extra)).isEmpty())
    }
}
