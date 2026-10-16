package br.com.tabelapp.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextoLegalTest {
    private fun arquivo(nome: String) = listOf("../app/src/main/res/raw/$nome", "app/src/main/res/raw/$nome")
        .map(::File).first { it.exists() }.readText()

    @Test fun `le titulos itens e negrito`() {
        val b = TextoLegal.blocos("# Título\n\nTexto com **negrito** aqui.\n\n## Seção\n- item um\n")
        assertEquals(TextoLegal.Bloco.Titulo("Título"), b[0])
        assertEquals(TextoLegal.Bloco.Subtitulo("Seção"), b[2])
        assertEquals(TextoLegal.Bloco.Item("item um"), b[3])
        assertEquals(listOf("Texto com " to false, "negrito" to true, " aqui." to false),
            TextoLegal.trechos((b[1] as TextoLegal.Bloco.Paragrafo).texto))
    }

    @Test fun `textos legais existem e citam a versao vigente`() {
        val data = LocalDate.parse(Termos.VERSAO)
        val meses = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
            "setembro", "outubro", "novembro", "dezembro")
        val versao = "Versão de ${data.dayOfMonth} de ${meses[data.monthValue - 1]} de ${data.year}."
        listOf("politica_privacidade.md", "termos_uso.md").forEach { nome ->
            val texto = arquivo(nome)
            assertTrue(texto.contains(versao), "$nome deve trazer \"$versao\"")
            assertTrue(TextoLegal.blocos(texto).first() is TextoLegal.Bloco.Titulo)
        }
        // Pontos que a Play Store e a LGPD pedem.
        val politica = arquivo("politica_privacidade.md")
        listOf("excluir", "CPF", "LGPD", "ANPD", "Minha conta").forEach {
            assertTrue(politica.contains(it), "política deve falar de \"$it\"")
        }
        assertTrue(arquivo("termos_uso.md").contains("agregador"))
    }
}
