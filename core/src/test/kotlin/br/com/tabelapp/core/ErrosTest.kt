package br.com.tabelapp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrosTest {

    // --- Página de bloqueio da Sefaz -------------------------------------------------

    private val paginaBloqueio = """
        <html><body><h2>SECRETARIA DE ESTADO DE FAZENDA DO RIO DE JANEIRO</h2>
        <p>Por motivos de segurança, a SEFAZ-RJ bloqueia acessos provenientes desses endereços IP.</p>
        </body></html>
    """.trimIndent()

    @Test fun `reconhece a pagina de bloqueio de IP`() {
        assertTrue(PaginaSefaz.ehBloqueio(paginaBloqueio))
        // Variações de maiúsculas, acentos e quebras de linha.
        assertTrue(PaginaSefaz.ehBloqueio("<div>A Sefaz BLOQUEIA ACESSOS\nPROVENIENTES destes IPs</div>"))
        assertTrue(PaginaSefaz.ehBloqueio("Secretaria de Estado de Fazenda — o sistema bloqueia o seu acesso"))
    }

    @Test fun `pagina normal da nota nao e bloqueio`() {
        val nota = """<html><body><div id="u20">SECRETARIA DE ESTADO DE FAZENDA</div>
            <table id="tabResult"><tr><td>ARROZ 5KG</td><td>Vl. Total 24,90</td></tr></table></body></html>"""
        assertFalse(PaginaSefaz.ehBloqueio(nota))
        assertFalse(PaginaSefaz.ehErroDoNavegador(nota))
    }

    @Test fun `reconhece a pagina de erro do navegador`() {
        assertTrue(PaginaSefaz.ehErroDoNavegador("<h2>Página da Web não disponível</h2><p>net::ERR_CONNECTION_RESET</p>"))
        assertTrue(PaginaSefaz.ehErroDoNavegador("net::ERR_TIMED_OUT"))
    }

    // --- Mapeamento de falhas técnicas para tipos de erro ----------------------------

    @Test fun `falhas da sefaz`() {
        assertEquals(TipoErro.SEFAZ_INDISPONIVEL, ClassificadorErros.deFalhaDePagina(temInternet = true))
        assertEquals(TipoErro.SEM_INTERNET, ClassificadorErros.deFalhaDePagina(temInternet = false))
        listOf(403, 429, 500, 502, 503).forEach {
            assertEquals(TipoErro.SEFAZ_INDISPONIVEL, ClassificadorErros.deStatusHttpSefaz(it), "status $it")
        }
        assertNull(ClassificadorErros.deStatusHttpSefaz(200))
        assertNull(ClassificadorErros.deStatusHttpSefaz(404))
    }

    @Test fun `falhas do servidor do tabelapp`() {
        val c = ClassificadorErros
        assertEquals(TipoErro.SEM_INTERNET, c.deServidor("java.net.UnknownHostException", "x", null, temInternet = false))
        assertEquals(TipoErro.SERVIDOR_INSTAVEL, c.deServidor("java.net.SocketTimeoutException", "timeout", null, true))
        assertEquals(TipoErro.SERVIDOR_INSTAVEL,
            c.deServidor("io.github.jan.supabase.exceptions.HttpRequestException", null, null, true))
        assertEquals(TipoErro.SERVIDOR_INSTAVEL, c.deServidor("RestException", "Bad gateway", 502, true))
        assertEquals(TipoErro.SESSAO_EXPIRADA, c.deServidor("UnauthorizedRestException", "JWT expired", 401, true))
        assertEquals(TipoErro.SESSAO_EXPIRADA, c.deServidor("RestException", "nao_autenticado", 400, true))
        // Regra de negócio conhecida: fica a mensagem própria (ex.: "nota já enviada").
        assertNull(c.deServidor("RestException", "nf_ja_enviada", 400, true))
        assertEquals(TipoErro.INESPERADO, c.deServidor("IllegalStateException", "boom", null, true))
    }

    // --- Intervalo mínimo entre tentativas -------------------------------------------

    @Test fun `intervalo minimo de 10 segundos entre tentativas`() {
        val i = IntervaloTentativas(minimoMs = 10_000)
        assertTrue(i.podeTentar(0), "a primeira tentativa é livre")
        i.registrar(1_000)
        assertFalse(i.podeTentar(5_000))
        assertEquals(6_000, i.esperaRestanteMs(5_000))
        assertFalse(i.podeTentar(10_999))
        assertTrue(i.podeTentar(11_000))
        assertEquals(0, i.esperaRestanteMs(20_000))
    }

    // --- Log sem CPF -------------------------------------------------------------------

    @Test fun `log nunca guarda cpf`() {
        val limpo = LogSeguro.limpar("CPF 123.456.789-09 / 12345678909 chave 33251011111111000191650010000099991000000001")
        assertFalse(limpo.contains("123.456.789-09"))
        assertFalse(limpo.contains(" 12345678909 "))
        assertTrue(limpo.contains("33251011111111000191650010000099991000000001"), "a chave da nota (44 números) fica")
    }

    // --- Textos (strings.xml do app) ----------------------------------------------------

    private val strings: Map<String, String> by lazy {
        val arquivo = listOf("../app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(arquivo.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun `todo tipo de erro tem titulo e texto amigaveis`() {
        TipoErro.entries.forEach { tipo ->
            val titulo = strings["erro_${tipo.chave}_titulo"]
            val texto = strings["erro_${tipo.chave}_texto"]
            assertTrue(!titulo.isNullOrBlank(), "falta erro_${tipo.chave}_titulo")
            assertTrue(!texto.isNullOrBlank(), "falta erro_${tipo.chave}_texto")
            listOf(titulo, texto).forEach { t ->
                assertFalse(t.contains("!"), "sem exclamação: $t")
                assertFalse(Regex("ERR_|HTTP|Exception|\\b[45]\\d\\d\\b").containsMatchIn(t), "sem código técnico: $t")
            }
        }
    }

    @Test fun `textos pedidos para a sefaz`() {
        assertEquals("A Sefaz não respondeu agora", strings["erro_sefaz_indisponivel_titulo"])
        assertTrue(strings["erro_sefaz_indisponivel_texto"]!!.startsWith("Isso acontece de vez em quando e não foi culpa sua."))
        assertEquals("Preencher manualmente", strings["erro_botao_preencher_manualmente"])
    }
}
