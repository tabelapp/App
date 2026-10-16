package br.com.tabelapp.core

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeloPrecoTest {
    private fun c(
        id: String, preco: Long, fonte: Fonte = Fonte.USUARIO_NF, combo: Boolean = false, denuncias: Int = 0,
    ) = Cotacao(id, "Pão, carne e queijo", preco, null, null, fonte, if (fonte.ehDoPdv) "loja-$id" else null, null,
        "PDV $id", criadoEm = Instant.EPOCH, parteDeCombo = combo, denuncias = denuncias)

    private val config = ConfigConfianca(fracaoAtipico = 0.4, minResultados = 3, toleranciaPdv = 0.05, limiteDenuncias = 3)

    // --- Detector de preço atípico ------------------------------------------------------

    @Test fun `caso real - preco de combo muito abaixo da mediana fica sem selo`() {
        val r = listOf(c("bk", 400), c("a", 1890), c("b", 2190), c("d", 2050))
        assertEquals(setOf("bk"), SeloPreco.atipicos(r, config))
        assertEquals(setOf("a"), SeloPreco.idsComSelo(r, config))
        val d = SeloPreco.destaques(r, comBusca = true, config = config)
        assertEquals(Destaque.CONFIRA_NA_LOJA, d["bk"])
        assertEquals(Destaque.MENOR_PRECO, d["a"])
    }

    @Test fun `com menos de 3 resultados o detector nao vale`() {
        val r = listOf(c("bk", 400), c("a", 2000))
        assertEquals(emptySet(), SeloPreco.atipicos(r, config))
        assertEquals(setOf("bk"), SeloPreco.idsComSelo(r, config))
        // Único resultado (só a NF): o selo aparece normalmente.
        assertEquals(setOf("bk"), SeloPreco.idsComSelo(listOf(c("bk", 400)), config))
    }

    @Test fun `valores na borda - exatamente 40 por cento da mediana nao e atipico`() {
        // Mediana dos demais (1000, 1000) = 1000; 400 = 40% exato -> não é atípico; 399 é.
        assertEquals(emptySet(), SeloPreco.atipicos(listOf(c("x", 400), c("a", 1000), c("b", 1000)), config))
        assertEquals(setOf("x"), SeloPreco.atipicos(listOf(c("x", 399), c("a", 1000), c("b", 1000)), config))
        // Mediana com número par de demais: média dos dois do meio ((1000+2000)/2 = 1500 -> 40% = 600).
        assertTrue("x" in SeloPreco.atipicos(listOf(c("x", 599), c("a", 1000), c("b", 2000)), config))
        assertTrue("x" !in SeloPreco.atipicos(listOf(c("x", 600), c("a", 1000), c("b", 2000)), config))
    }

    @Test fun `fracao vem da configuracao`() {
        val r = listOf(c("x", 700), c("a", 1000), c("b", 1000))
        assertEquals(emptySet(), SeloPreco.atipicos(r, config))
        assertEquals(setOf("x"), SeloPreco.atipicos(r, config.copy(fracaoAtipico = 0.8)))
    }

    // --- Preferência por PDV cadastrado -----------------------------------------------------

    @Test fun `selo prefere pdv cadastrado com preco igual ou ate 5 por cento acima`() {
        assertEquals(setOf("pdv"), SeloPreco.idsComSelo(listOf(c("nf", 1000), c("pdv", 1050, Fonte.PDV_MANUAL), c("z", 1200)), config))
        assertEquals(setOf("pdv"), SeloPreco.idsComSelo(listOf(c("nf", 1000), c("pdv", 1000, Fonte.PDV_EXCEL)), config))
        // 5,1% acima: fica com o menor (NF).
        assertEquals(setOf("nf"), SeloPreco.idsComSelo(listOf(c("nf", 1000), c("pdv", 1051, Fonte.PDV_MANUAL)), config))
        // Empate entre usuários: todos recebem.
        assertEquals(setOf("a", "b"), SeloPreco.idsComSelo(listOf(c("a", 999), c("b", 999), c("d", 1500)), config))
    }

    // --- Combo e denúncias --------------------------------------------------------------------

    @Test fun `item de combo nunca recebe o selo`() {
        val r = listOf(c("combo", 400, combo = true), c("a", 1890))
        assertEquals(setOf("a"), SeloPreco.idsComSelo(r, config))
        assertEquals(Destaque.PARTE_DE_COMBO, SeloPreco.destaques(r, comBusca = true, config = config)["combo"])
        // Mesmo sozinho ou na tela inicial, o chip de combo aparece; o selo, não.
        assertEquals(emptySet(), SeloPreco.idsComSelo(listOf(c("combo", 400, combo = true)), config))
        assertEquals(mapOf("combo" to Destaque.PARTE_DE_COMBO),
            SeloPreco.destaques(listOf(c("combo", 400, combo = true)), comBusca = false, config = config))
    }

    @Test fun `limite de denuncias`() {
        val r = listOf(c("a", 900, denuncias = 2), c("b", 1000))
        assertEquals(setOf("a"), SeloPreco.idsComSelo(r, config), "2 denúncias: ainda recebe")
        val r3 = listOf(c("a", 900, denuncias = 3), c("b", 1000))
        assertEquals(setOf("b"), SeloPreco.idsComSelo(r3, config), "3 denúncias: perde o selo")
        assertEquals(Destaque.CONFIRA_NA_LOJA, SeloPreco.destaques(r3, comBusca = true, config = config)["a"])
        // O limite vem da configuração.
        assertEquals(setOf("b"), SeloPreco.idsComSelo(r, config.copy(limiteDenuncias = 2)))
    }

    @Test fun `sem busca nao ha selo`() {
        assertEquals(emptyMap(), SeloPreco.destaques(listOf(c("a", 100), c("b", 200)), comBusca = false, config = config))
    }

    // --- Itens da NF e compartilhamento ----------------------------------------------------------

    @Test fun `combo vai junto no envio da nota`() {
        val r = RascunhoNf(itens = listOf(ItemNota("Pão carne e queijo", 400, parteDeCombo = true), ItemNota("Refri", 600)))
        assertEquals(listOf(true, false), r.itensParaEnvio().map { it.parteDeCombo })
    }

    private val strings: Map<String, String> by lazy {
        val arquivo = listOf("../app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(arquivo.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun `compartilhamento termina com o aviso`() {
        val nf = c("a", 400)
        val pdv = c("b", 500, Fonte.PDV_MANUAL)
        val avisoUsuario = strings.getValue("compartilhar_aviso_usuario")
        val avisoPdv = strings.getValue("compartilhar_aviso_estabelecimento")
        assertEquals("Preço informado por usuário e sujeito a alteração. Confirme na loja. Visto no Tabelapp.", avisoUsuario)
        assertEquals("Preço informado por estabelecimento e sujeito a alteração. Confirme na loja. Visto no Tabelapp.", avisoPdv)
        assertTrue(!Compartilhamento.porEstabelecimento(nf) && Compartilhamento.porEstabelecimento(pdv))
        assertTrue(Compartilhamento.texto(nf, aviso = avisoUsuario).endsWith(avisoUsuario))
        assertEquals("MENOR PREÇO LANÇADO", strings["selo_menor_preco"])
    }
}
