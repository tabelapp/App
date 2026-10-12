package br.com.tabelapp.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogoTest {

    // --- Unidade e "a partir de" ----------------------------------------------------

    @Test fun `preco com unidade e a partir de`() {
        assertEquals("A partir de R$ 80,00 / hora", PrecoExibido.texto(8000, Unidade.HORA, aPartirDe = true))
        assertEquals("R$ 12,90 / kg", PrecoExibido.texto(1290, Unidade.KG))
        assertEquals("R$ 45,00 / m²", PrecoExibido.texto(4500, Unidade.M2))
        // "unidade" não tem sufixo.
        assertEquals("R$ 5,00", PrecoExibido.texto(500, Unidade.UNIDADE))
        assertEquals("A partir de R$ 150,00", PrecoExibido.texto(15000, Unidade.UNIDADE, aPartirDe = true))
        assertEquals("", PrecoExibido.sufixo(Unidade.UNIDADE))
        assertEquals(" / diária", PrecoExibido.sufixo(Unidade.DIARIA))
    }

    @Test fun `codigos de unidade iguais aos do banco`() {
        assertEquals(
            listOf("unidade", "kg", "litro", "metro", "m2", "hora", "visita", "diaria", "servico"),
            Unidade.entries.map { it.codigo },
        )
        assertEquals(Unidade.HORA, Unidade.deCodigo("hora"))
        assertEquals(Unidade.UNIDADE, Unidade.deCodigo(null))
        assertEquals(Unidade.UNIDADE, Unidade.deCodigo("tonelada"))
    }

    @Test fun `ordenacao continua pelo valor numerico`() {
        val agora = Instant.EPOCH
        fun c(id: String, preco: Long, u: Unidade, ap: Boolean = false) =
            Cotacao(id, "Item", preco, null, null, Fonte.PDV_MANUAL, "l", "p", "PDV", criadoEm = agora, unidade = u, aPartirDe = ap)
        val r = Busca.ordenar(listOf(c("a", 8000, Unidade.HORA, true), c("b", 1290, Unidade.KG), c("c", 500, Unidade.UNIDADE)),
            Ordenacao.MENOR_PRECO, null)
        assertEquals(listOf("c", "b", "a"), r.map { it.id })
    }

    @Test fun `mensagem do whatsapp usa a unidade`() {
        val c = Cotacao("1", "Aula de violão", 8000, null, null, Fonte.PDV_MANUAL, "l", "p", "Escola",
            criadoEm = Instant.EPOCH, unidade = Unidade.HORA, aPartirDe = true)
        assertTrue(Mensagens.interesseNaOferta(c).contains("*Aula de violão* a partir de R$ 80,00 / hora"))
    }

    // --- Status do catálogo e período de fundador -----------------------------------

    private fun status(fundadorAte: LocalDate?, ativos: Int = 10, limite: Int = 30) = StatusCatalogo(
        plano = "gratis", planoNome = "Grátis", limitePlano = 30, planoEfetivo = "gratis", planoEfetivoNome = "Grátis",
        limite = limite, itensAtivos = ativos, disponiveis = (limite - ativos).coerceAtLeast(0), fundadorAte = fundadorAte,
    )

    @Test fun `avisos do periodo de fundador`() {
        val hoje = LocalDate.of(2026, 10, 12)
        assertEquals(AvisoFundador.FAIXA, status(hoje.plusDays(30)).avisoFundador(hoje))
        assertEquals(AvisoFundador.FALTAM_POUCOS_DIAS, status(hoje.plusDays(7)).avisoFundador(hoje))
        assertEquals(AvisoFundador.FALTAM_POUCOS_DIAS, status(hoje.plusDays(1)).avisoFundador(hoje))
        assertEquals(AvisoFundador.ULTIMO_DIA, status(hoje).avisoFundador(hoje))
        assertEquals(AvisoFundador.NENHUM, status(hoje.minusDays(1)).avisoFundador(hoje))
        assertEquals(AvisoFundador.NENHUM, status(null).avisoFundador(hoje))
    }

    @Test fun `no limite`() {
        assertTrue(status(null, ativos = 30).noLimite)
        assertTrue(status(null, ativos = 35).noLimite)
        assertFalse(status(null, ativos = 29).noLimite)
    }

    @Test fun `resultado da importacao e lista dos que ficaram de fora`() {
        val r = ResultadoImportacao(2, 1, 0, 0, 2, listOf(ItemFora(5, "Café \"especial\""), ItemFora(6, "Chá")), 30, "Grátis")
        assertEquals(3, r.importados)
        assertEquals("Linha;Produto\n5;\"Café \"\"especial\"\"\"\n6;\"Chá\"\n", r.csvDosQueFicaramDeFora())
    }

    // --- Textos (strings.xml do app) ----------------------------------------------------

    private val strings: Map<String, String> by lazy {
        val arquivo = listOf("../app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(arquivo.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    @Test fun `mensagem do limite`() {
        val texto = String.format(strings.getValue("catalogo_limite_texto"), 30, "Grátis")
        assertEquals(
            "Você chegou ao limite de 30 itens do plano Grátis. Seus itens continuam publicados e você pode " +
                "atualizar os preços quando quiser. Para adicionar mais, veja os planos.",
            texto,
        )
        assertEquals("Ver planos", strings["catalogo_ver_planos"])
        assertEquals("Agora não", strings["catalogo_agora_nao"])
        assertEquals("Anotado. Avisaremos assim que os planos estiverem disponíveis.", strings["planos_anotado"])
    }

    @Test fun `resumo da importacao`() {
        assertEquals("12 itens importados.", String.format(strings.getValue("importacao_importados"), 12))
        assertEquals("3 ficaram de fora porque o plano permite até 30 itens.",
            String.format(strings.getValue("importacao_fora"), 3, 30))
        assertEquals("Parceiro fundador: catálogo liberado até 10/01/2027",
            String.format(strings.getValue("fundador_faixa"), "10/01/2027"))
    }
}
