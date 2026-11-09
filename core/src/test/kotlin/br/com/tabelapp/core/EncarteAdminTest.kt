package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncarteAdminTest {
    private val hoje = LocalDate.of(2026, 11, 9)
    private fun item(preco: Long, normal: Long? = null, app: Boolean = false, produto: String = "Arroz Tio João 5kg") =
        ItemLidoEncarte(produto, preco, normal, app, validade = hoje.plusDays(5))

    @Test fun `dois precos - o menor e o do app e o maior e o normal`() {
        val i = RegrasEncarte.normalizar(item(699, 598, app = false))
        assertEquals(598, i.precoCentavos)
        assertEquals(699, i.precoNormalCentavos)
        assertTrue(i.precoApp)
        // Já na ordem certa, mas sem a marca de app: dois preços = app.
        assertTrue(RegrasEncarte.normalizar(item(598, 699)).precoApp)
    }

    @Test fun `so preco do app - sem normal`() {
        val i = RegrasEncarte.normalizar(item(598, null, app = true))
        assertTrue(i.precoApp)
        assertNull(i.precoNormalCentavos)
        assertTrue(RegrasEncarte.erros(i, hoje).isEmpty())
    }

    @Test fun `so preco comum`() {
        val i = RegrasEncarte.normalizar(item(598))
        assertFalse(i.precoApp)
        assertNull(i.precoNormalCentavos)
    }

    @Test fun `normal igual ao preco some`() {
        assertNull(RegrasEncarte.normalizar(item(598, 598, app = true)).precoNormalCentavos)
    }

    @Test fun `produto por quilo`() {
        val l = LeitorJsonEncarte.ler(
            """{"itens":[{"produto":"Picanha kg","preco_centavos":6990,"unidade":"kg","validade":"2026-11-15"}]}"""
        )
        assertEquals(Unidade.KG, l.itens.single().unidade)
    }

    @Test fun `leve 3 por - preco de cada e conta na obs`() {
        val (cada, obs) = RegrasEncarte.levePor(3, 1000)
        assertEquals(333, cada)
        assertEquals("Leve 3 por R$ 10,00 (R$ 3,33 cada)", obs)
    }

    @Test fun `obs com no maximo 140 caracteres`() {
        val i = RegrasEncarte.normalizar(item(500).copy(obs = "x".repeat(200)))
        assertEquals(140, i.obs!!.length)
        assertTrue(RegrasEncarte.erros(item(500).copy(obs = "y".repeat(141)), hoje).isNotEmpty())
    }

    @Test fun `validade - vencida ha mais de 30 dias e muito a frente`() {
        assertTrue(RegrasEncarte.erros(item(500).copy(validade = hoje.minusDays(31)), hoje).isNotEmpty())
        assertTrue(RegrasEncarte.erros(item(500).copy(validade = hoje.minusDays(30)), hoje).isEmpty())
        assertTrue(RegrasEncarte.erros(item(500).copy(validade = hoje.plusDays(61)), hoje).isNotEmpty())
        assertTrue(RegrasEncarte.erros(item(500).copy(validade = null), hoje).isNotEmpty())
    }

    @Test fun `uniao de repetidos entre imagens - mesmo produto e mesmo preco`() {
        val a = listOf(item(598, produto = "Café Pilão 500g"), item(1299, produto = "Óleo Soya 900ml"))
        val b = listOf(item(598, produto = "CAFÉ  PILAO 500G"), item(649, produto = "Café Pilão 500g"))
        val u = RegrasEncarte.unir(listOf(a, b))
        assertEquals(3, u.size, "mesmo produto com outro preço continua")
        assertEquals("Café Pilão 500g", u.first().produto)
    }

    @Test fun `alcoolico vem desmarcado`() {
        val l = LeitorJsonEncarte.ler(
            """{"itens":[{"produto":"Cerveja Heineken 350ml","preco_centavos":499,"alcoolico":true},
                         {"produto":"Cerveja Heineken Zero 350ml","preco_centavos":499,"alcoolico":false}]}"""
        )
        assertFalse(l.itens[0].marcado)
        assertTrue(l.itens[1].marcado)
    }

    @Test fun `json da funcao - valido, item ruim vai para ignorados`() {
        val l = LeitorJsonEncarte.ler(
            """{"loja":{"nome":"Assaí"},
                "itens":[
                  {"produto":"Leite Italac 1L","preco_centavos":479,"preco_normal_centavos":529,"preco_app":true,
                   "unidade":"unidade","validade":"15/11/2026","obs":"Limite 6 un. por CPF","alcoolico":false},
                  {"produto":"Sem preço","preco_centavos":0},
                  {"produto":"Unidade estranha","preco_centavos":100,"unidade":"tonelada"},
                  {"preco_centavos":100}
                ],
                "ignorados":[{"texto":"Biscoito ...","motivo":"preço ilegível"}]}"""
        )
        assertEquals("Assaí", l.lojaNome)
        val leite = l.itens.single()
        assertEquals(479, leite.precoCentavos)
        assertEquals(529, leite.precoNormalCentavos)
        assertEquals(LocalDate.of(2026, 11, 15), leite.validade)
        assertEquals(4, l.ignorados.size)
        assertEquals("preço ilegível", l.ignorados.first().motivo)
    }

    @Test fun `json invalido`() {
        assertFailsWith<LeitorJsonEncarte.JsonInvalido> { LeitorJsonEncarte.ler("não é json") }
        assertFailsWith<LeitorJsonEncarte.JsonInvalido> { LeitorJsonEncarte.ler("""{"loja":{}}""") }
    }

    @Test fun `resumo antes de confirmar`() {
        val itens = listOf(
            item(598, 699, app = true), item(1299, produto = "Óleo"),
            item(499, produto = "Cerveja").copy(alcoolico = true, marcado = false),
        )
        assertEquals("2 itens, 1 com preço do app, validade 14/11/2026, loja Assaí Centro",
            RegrasEncarte.resumo(itens, " Assaí Centro "))
    }
}
