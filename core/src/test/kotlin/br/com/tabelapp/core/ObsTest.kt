package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ObsTest {
    @Test fun `nota fiscal mostra NF`() =
        assertEquals("NF", Obs.exibir(Fonte.USUARIO_NF, "qualquer coisa"))

    @Test fun `encarte de usuario mostra produto de encarte`() =
        assertEquals("produto de encarte", Obs.exibir(Fonte.USUARIO_ENCARTE, null))

    @Test fun `PDV mostra o proprio texto`() {
        assertEquals("cerveja gelada", Obs.exibir(Fonte.PDV_MANUAL, "cerveja gelada"))
        assertEquals("entrega grátis", Obs.exibir(Fonte.PDV_EXCEL, "  entrega grátis "))
    }

    @Test fun `PDV sem texto mostra Preco oficial`() {
        assertEquals("Preço oficial", Obs.exibir(Fonte.PDV_MANUAL, null))
        assertEquals("Preço oficial", Obs.exibir(Fonte.PDV_EXCEL, "   "))
    }
}

class ValidadeTest {
    private val hoje = java.time.LocalDate.parse("2026-09-22")

    @Test fun `NF fica 7 dias na busca a partir da data da nota`() =
        assertEquals(java.time.LocalDate.parse("2026-09-29"), Validade.daNotaFiscal(hoje))

    @Test fun `PDV no maximo 30 dias`() {
        assertEquals(null, Validade.validar(hoje.plusDays(30), hoje))
        assertEquals(Validade.ErroValidade.MAIOR_QUE_30_DIAS, Validade.validar(hoje.plusDays(31), hoje))
        assertEquals(Validade.ErroValidade.NO_PASSADO, Validade.validar(hoje.minusDays(1), hoje))
    }

    @Test fun `campo validade na busca`() {
        assertEquals("Preço praticado dia 22/09/2026", Validade.exibir(Fonte.USUARIO_NF, hoje.plusDays(7), hoje))
        // Sem data da NF (dado antigo): deduz pela validade.
        assertEquals("Preço praticado dia 22/09/2026", Validade.exibir(Fonte.USUARIO_NF, hoje.plusDays(7)))
        assertEquals("Oferta válida até 25/09/2026", Validade.exibir(Fonte.USUARIO_ENCARTE, hoje.plusDays(3)))
        assertEquals("Oferta válida até 22/10/2026", Validade.exibir(Fonte.PDV_MANUAL, hoje.plusDays(30)))
        assertEquals("Validade não informada", Validade.exibir(Fonte.PDV_EXCEL, null))
    }

    @Test fun `todo preco da demonstracao tem validade`() =
        kotlin.test.assertTrue(DadosDemo.cotacoes(hoje = hoje).all { it.validade != null })

    @Test fun `origem e OBS na pagina do produto`() {
        kotlin.test.assertEquals("Nota Fiscal", Obs.origem(Fonte.USUARIO_NF))
        kotlin.test.assertEquals("Encarte", Obs.origem(Fonte.USUARIO_ENCARTE))
        kotlin.test.assertEquals("Preço oficial", Obs.origem(Fonte.PDV_EXCEL))
        val c = Cotacao(id = "1", produto = "X", precoCentavos = 100, validade = null, obs = " Leve 3 ", fonte = Fonte.PDV_MANUAL,
            lojaId = "l", pdvId = "p", pdvNome = "Loja", criadoEm = java.time.Instant.EPOCH)
        kotlin.test.assertEquals("Leve 3", Obs.doPdv(c))
        kotlin.test.assertEquals(null, Obs.doPdv(c.copy(fonte = Fonte.USUARIO_ENCARTE)))
        kotlin.test.assertEquals(null, Obs.doPdv(c.copy(obs = "  ")))
    }
}
