package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentosTest {
    private val base43 = "3326091111111100019165001000001234100001234"
    private val chave = base43 + ChaveAcessoNfe.calcularDv(base43)

    @Test fun `chave valida com espacos`() {
        val c = assertNotNull(ChaveAcessoNfe.deTexto(chave.chunked(4).joinToString(" ")))
        assertEquals(chave, c.digitos)
        assertTrue(c.ehDoRioDeJaneiro)
        assertTrue(c.ehNfce)
        assertEquals("11111111000191", c.cnpjEmitente)
        assertEquals(chave.chunked(4).joinToString(" "), c.formatada())
    }

    @Test fun `chave com DV errado ou tamanho errado e rejeitada`() {
        val dvErrado = base43 + ((chave.last() - '0' + 1) % 10)
        assertNull(ChaveAcessoNfe.deTexto(dvErrado))
        assertNull(ChaveAcessoNfe.deTexto("123"))
        assertNull(ChaveAcessoNfe.deTexto(chave.dropLast(1) + "X"))
    }

    @Test fun `extrai chave do QR code da Sefaz RJ`() {
        val url = "https://consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode?p=$chave|2|1|1|ABCDEF0123"
        assertEquals(chave, ChaveAcessoNfe.doQrCode(url)?.digitos)
        assertEquals(chave, ChaveAcessoNfe.doQrCode(url.replace("|", "%7C"))?.digitos)
        assertNull(ChaveAcessoNfe.doQrCode("https://exemplo.invalid/?p=123|2"))
    }

    @Test fun cnpj() {
        assertTrue(Cnpj.valido("11.222.333/0001-81"))
        assertEquals("11222333000181", Cnpj.normalizar("11.222.333/0001-81"))
        assertFalse(Cnpj.valido("11.222.333/0001-82"))
        assertFalse(Cnpj.valido("00000000000000"))
        assertEquals("11.222.333/0001-81", Cnpj.formatar("11222333000181"))
    }

    @Test fun `erros do servidor viram mensagem amigavel`() {
        assertTrue(ErrosServidor.traduzir("""{"code":"P0001","message":"cota_excedida"}""").contains("R$ 10"))
        assertEquals(ErrosServidor.GENERICA, ErrosServidor.traduzir("timeout"))
    }
}
