package br.com.tabelapp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotaIncompativelTest {
    private val strings: Map<String, String> by lazy {
        val arquivo = listOf("../app/src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(arquivo.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun `problemas da propria nota viram nota incompativel`() {
        listOf(
            ProblemaNota.QR_NAO_RECONHECIDO, ProblemaNota.CHAVE_INVALIDA,
            ProblemaNota.CHAVE_NAO_CONFERE, ProblemaNota.PRODUTOS_NAO_ENCONTRADOS,
        ).forEach { assertEquals(TipoErro.NOTA_INCOMPATIVEL, it.tipoErro, it.name) }
    }

    @Test fun `internet, sefaz e camera continuam com a mensagem de antes`() {
        assertEquals(TipoErro.SEM_INTERNET, ProblemaNota.SEM_INTERNET.tipoErro)
        assertEquals(TipoErro.SEFAZ_INDISPONIVEL, ProblemaNota.SEFAZ_INDISPONIVEL.tipoErro)
        assertEquals(TipoErro.CAMERA_NEGADA, ProblemaNota.CAMERA_NEGADA.tipoErro)
        // Regras de negócio mantêm a mensagem própria.
        assertNull(ProblemaNota.OUTRO_ESTADO.tipoErro)
        assertNull(ProblemaNota.NOTA_JA_ENVIADA.tipoErro)
        assertNull(ProblemaNota.LIMITE_DIARIO.tipoErro)
    }

    @Test fun `chave invalida nao permite tentar de novo nem lancar manual`() {
        assertFalse(ProblemaNota.CHAVE_INVALIDA.permiteTentarDeNovo)
        assertTrue(ProblemaNota.PRODUTOS_NAO_ENCONTRADOS.permiteTentarDeNovo)
        assertTrue(ProblemaNota.QR_NAO_RECONHECIDO.permiteTentarDeNovo)
    }

    @Test fun `texto exato da mensagem`() {
        assertEquals("Esta nota não está compatível com o sistema da SeFaz.", strings["erro_nota_incompativel_titulo"])
        assertEquals(
            "Isso acontece com algumas notas, mesmo direto no site da SeFaz. Você pode tentar outra nota.",
            strings["erro_nota_incompativel_texto"],
        )
    }
}
