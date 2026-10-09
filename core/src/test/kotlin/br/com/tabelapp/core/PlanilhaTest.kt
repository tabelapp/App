package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanilhaTest {
    private val hoje = LocalDate.parse("2026-09-22")

    @Test fun `linhas validas e invalidas`() {
        val r = Planilha.validar(
            listOf(
                listOf("Arroz 5kg", "24,90", "", "Oferta"),
                listOf("Feijão 1kg", "R$ 7,89", "10/10/2026", null),
                listOf("", "", "", ""),                       // em branco: ignorada
                listOf("Café", "abc"),                        // preço inválido
                listOf("Açúcar", "4,89", "2026-11-30"),       // > 30 dias
                listOf("arroz  5KG", "25,00"),                // repetido
                listOf("Leite", "5,69", "21/09/2026"),        // passado
            ),
            hoje,
        )
        assertEquals(listOf("Arroz 5kg", "Feijão 1kg"), r.linhas.map { it.produto })
        assertEquals(hoje.plusDays(30), r.linhas[0].validade)
        assertEquals(789, r.linhas[1].precoCentavos)
        assertEquals(listOf(5, 6, 7, 8), r.erros.map { it.numero })
        assertTrue(r.erros.first { it.numero == 7 }.mensagem.contains("linha 2"))
    }
}

class LeitorPlanilhaTest {
    private val hoje = java.time.LocalDate.parse("2026-10-10")

    @Test fun `csv do Excel brasileiro com cabecalho, ponto e virgula e aspas`() {
        val csv = "Produto;Preço;Validade;OBS\n\"Arroz, tipo 1 5kg\";24,90;;\nCerveja lata;3,49;20/10/2026;Gelada\n"
        val lida = LeitorPlanilha.ler(csv.toByteArray(), "precos.csv")
        assertEquals(2, lida.primeiraLinha)
        val r = Planilha.validar(lida.celulas, hoje, lida.primeiraLinha)
        assertTrue(r.ok, r.erros.toString())
        assertEquals(listOf("Arroz, tipo 1 5kg", "Cerveja lata"), r.linhas.map { it.produto })
        assertEquals(listOf(2490L, 349L), r.linhas.map { it.precoCentavos })
        assertEquals("Gelada", r.linhas[1].obs)
    }

    @Test fun `xlsx com textos compartilhados, numero e data do Excel`() {
        val xlsx = xlsx(
            shared = listOf("Produto", "Preço", "Feijão Preto 1kg", "Óleo de soja"),
            linhas = """
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>7.89</v></c><c r="C2"><v>46315</v></c></row>
                <row r="3"><c r="A3" t="s"><v>3</v></c><c r="B3"><v>8</v></c><c r="D3" t="inlineStr"><is><t>Promoção</t></is></c></row>
            """,
        )
        val lida = LeitorPlanilha.ler(xlsx, "tabela.xlsx")
        val r = Planilha.validar(lida.celulas, hoje, lida.primeiraLinha)
        assertTrue(r.ok, r.erros.toString())
        assertEquals(listOf(789L, 800L), r.linhas.map { it.precoCentavos })
        assertEquals(java.time.LocalDate.parse("2026-10-20"), r.linhas[0].validade)
        assertEquals("Promoção", r.linhas[1].obs)
        assertEquals(3, r.linhas[1].numero)
    }

    private fun xlsx(shared: List<String>, linhas: String): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(saida).use { zip ->
            fun arquivo(nome: String, conteudo: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(nome)); zip.write(conteudo.toByteArray()); zip.closeEntry()
            }
            arquivo("xl/sharedStrings.xml",
                "<sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>")
            arquivo("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$linhas</sheetData></worksheet>")
        }
        return saida.toByteArray()
    }
}
