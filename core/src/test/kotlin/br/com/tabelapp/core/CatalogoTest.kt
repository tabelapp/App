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

    // --- Planos, limite efetivo e avisos ---------------------------------------------

    private val planos = listOf(
        Plano("gratis", "Grátis", 5, 0, 1), Plano("essencial", "Essencial", 30, 999, 2),
        Plano("loja", "Loja", 300, 1999, 3), Plano("mercado", "Mercado", 3000, 2999, 4),
    )
    private val hoje = LocalDate.of(2026, 10, 15)

    private fun efetivo(plano: String = "gratis", planoAte: LocalDate? = null, testeAte: LocalDate? = null, tol: Int = 7) =
        Limites.efetivo(planos, plano, planoAte, testeAte, hoje, tol)

    @Test fun `teste gratis vale o limite do mercado`() {
        val e = efetivo(testeAte = hoje.plusDays(30))
        assertEquals(3000, e.limite); assertEquals(OrigemLimite.TESTE, e.origem); assertFalse(e.emTolerancia)
        // No último dia ainda vale.
        assertEquals(3000, efetivo(testeAte = hoje).limite)
    }

    @Test fun `depois do teste e da tolerancia vale o gratis`() {
        val tolerancia = efetivo(testeAte = hoje.minusDays(1))
        assertEquals(3000, tolerancia.limite); assertTrue(tolerancia.emTolerancia)
        assertEquals(hoje.plusDays(6), tolerancia.toleranciaAte)
        assertEquals(3000, efetivo(testeAte = hoje.minusDays(7)).limite)
        val gratis = efetivo(testeAte = hoje.minusDays(8))
        assertEquals(5, gratis.limite); assertEquals(OrigemLimite.GRATIS, gratis.origem)
        // Tolerância 0: acabou, acabou.
        assertEquals(5, efetivo(testeAte = hoje.minusDays(1), tol = 0).limite)
    }

    @Test fun `planos pagos e o maior limite vence`() {
        assertEquals(30, efetivo("essencial", hoje.plusDays(10)).limite)
        assertEquals(300, efetivo("loja", hoje.plusDays(10)).limite)
        assertEquals(3000, efetivo("mercado", hoje.plusDays(10)).limite)
        // Teste ainda valendo com plano essencial: vale o maior (o do teste).
        assertEquals(OrigemLimite.TESTE, efetivo("essencial", hoje.plusDays(10), hoje.plusDays(3)).origem)
        // Plano mercado e teste: empate, vale o pago.
        assertEquals(OrigemLimite.PAGO, efetivo("mercado", hoje.plusDays(10), hoje.plusDays(3)).origem)
        // Plano vencido há 8 dias: grátis.
        assertEquals(5, efetivo("loja", hoje.minusDays(8)).limite)
    }

    private fun status(e: LimiteEfetivo, ativos: Int) = StatusCatalogo(
        plano = "gratis", planoNome = "Grátis", limitePlano = 5, planoAte = null, testeAte = e.fim,
        planoEfetivo = e.plano, planoEfetivoNome = planos.first { it.codigo == e.plano }.nome, limite = e.limite,
        origem = e.origem, emTolerancia = e.emTolerancia, fim = e.fim, toleranciaAte = e.toleranciaAte,
        itensAtivos = ativos, disponiveis = (e.limite - ativos).coerceAtLeast(0),
    )

    @Test fun `estados do catalogo e avisos`() {
        val emTeste = status(efetivo(testeAte = hoje.plusDays(20)), ativos = 10)
        assertEquals(listOf<AvisoPlano>(AvisoPlano.FaixaTeste(hoje.plusDays(20))), emTeste.avisos(hoje))
        val acabando = status(efetivo(testeAte = hoje.plusDays(7)), ativos = 10)
        assertEquals(AvisoPlano.TesteTermina(7, 5), acabando.avisos(hoje)[1])
        assertEquals(AvisoPlano.TesteTermina(0, 5), status(efetivo(testeAte = hoje), 10).avisos(hoje)[1])
        val tolerancia = status(efetivo(testeAte = hoje.minusDays(2)), ativos = 10)
        assertEquals(listOf<AvisoPlano>(AvisoPlano.Tolerancia(OrigemLimite.TESTE, hoje.plusDays(5), 5)), tolerancia.avisos(hoje))
        val pausados = status(efetivo(testeAte = hoje.minusDays(8)), ativos = 12)
        assertEquals(5, pausados.itensPublicados)
        assertEquals(7, pausados.pausadosLimite)
        assertTrue(pausados.noLimite)
        assertEquals(listOf<AvisoPlano>(AvisoPlano.ItensPausados(7)), pausados.avisos(hoje))
        assertFalse(status(efetivo(testeAte = hoje.minusDays(8)), ativos = 4).noLimite)
    }

    @Test fun `estado do item vem do banco`() {
        assertEquals(EstadoItem.PAUSADO_LIMITE, EstadoItem.doCodigo("pausado_limite"))
        assertEquals(EstadoItem.ATIVO, EstadoItem.doCodigo(null))
        assertTrue(EstadoItem.ATIVO.publicado)
        assertFalse(EstadoItem.PAUSADO.publicado)
    }

    @Test fun `preco do plano a partir de centavos`() {
        assertEquals("R$ 9,99 por mês", PrecoPlano.porMes(999))
        assertEquals("R$ 19,99 por mês", PrecoPlano.porMes(1999))
        assertEquals("R$ 29,99 por mês", PrecoPlano.porMes(2999))
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

    @Test fun `mensagem do limite e neutra`() {
        assertEquals("Limite do plano atingido. Itens excedentes pausados.", strings["catalogo_limite_atingido"])
        assertEquals(
            "Limite do plano atingido. Itens excedentes pausados. O plano Grátis permite 5 itens ativos; seus itens " +
                "continuam guardados e você pode atualizar os preços quando quiser.",
            String.format(strings.getValue("catalogo_limite_texto"), 5, "Grátis"),
        )
    }

    @Test fun `estado do plano para o PDV`() {
        assertEquals("Plano: Loja", String.format(strings.getValue("catalogo_plano"), "Loja"))
        assertEquals("Teste grátis até 14/11/2026", String.format(strings.getValue("teste_faixa"), "14/11/2026"))
        assertEquals("Itens ativos 3 de 30", String.format(strings.getValue("catalogo_resumo"), 3, 30))
        assertEquals("Itens pausados: 2", String.format(strings.getValue("catalogo_itens_pausados"), 2))
        assertEquals(
            "Seu teste grátis termina em 7 dias. Depois disso, o plano grátis mantém 5 itens ativos. " +
                "Os outros ficam guardados, só pausados.",
            String.format(strings.getValue("teste_termina_dias"), 7, 5),
        )
        assertEquals("Até 5 itens ativos", String.format(strings.getValue("plano_limite_itens"), 5))
    }

    /** Decisão do fundador (Google Play): nenhum texto do app vende, cobra ou fala de pagamento. */
    @Test fun `app sem superficie de venda`() {
        val proibido = Regex("""(?i)\bassin|\bcontrat[ae]|\bpague|\bpagar\b|pagamento|\bpix\b|R\$|veja os planos|ver planos|anuncie""")
        val achados = strings.filter { (_, texto) -> proibido.containsMatchIn(texto) }
        assertEquals(emptyMap<String, String>(), achados)
    }

    @Test fun `resumo da importacao`() {
        assertEquals("12 itens importados.", String.format(strings.getValue("importacao_importados"), 12))
        assertEquals("3 ficaram de fora porque o plano permite até 30 itens.",
            String.format(strings.getValue("importacao_fora"), 3, 30))
    }
}
