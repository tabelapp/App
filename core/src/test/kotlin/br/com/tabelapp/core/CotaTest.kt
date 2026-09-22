package br.com.tabelapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CotaTest {
    @Test fun `so criar e aumentar contam`() {
        assertEquals(TipoOperacao.CRIAR_ITEM, TipoOperacao.classificar(null, 100))
        assertEquals(TipoOperacao.AUMENTAR_PRECO, TipoOperacao.classificar(100, 101))
        assertEquals(TipoOperacao.DIMINUIR_PRECO, TipoOperacao.classificar(100, 99))
        assertEquals(TipoOperacao.EDITAR_DADOS, TipoOperacao.classificar(100, 100))
        assertEquals(TipoOperacao.EXCLUIR_ITEM, TipoOperacao.classificar(100, null))
        assertEquals(
            setOf(TipoOperacao.CRIAR_ITEM, TipoOperacao.AUMENTAR_PRECO),
            TipoOperacao.entries.filter { it.contaNaCota }.toSet(),
        )
    }

    @Test fun `saldo e pacotes`() {
        val saldo = SaldoCota(usadas = 38)
        assertEquals(12, saldo.restantes)
        assertTrue(saldo.simular(12).cabeNaCota)

        val s = saldo.simular(60) // faltam 48 -> 1 pacote
        assertFalse(s.cabeNaCota)
        assertEquals(1, s.pacotesNecessarios)
        assertEquals(1000, s.valorCentavos)

        assertEquals(2, saldo.simular(63).pacotesNecessarios) // faltam 51 -> 2 pacotes
        assertEquals(62, SaldoCota(usadas = 38, compradas = 50).restantes)
        assertEquals(0, SaldoCota(usadas = 80).restantes)
    }

    @Test fun `resumo de importacao compara com precos atuais`() {
        val atuais = mapOf("arroz 5kg" to 2500L, "feijao 1kg" to 800L, "cafe" to 1800L)
        val r = CalculoCota.resumir(
            atuais,
            mapOf("Arroz 5kg" to 2600L, "Feijão 1kg" to 700L, "Café" to 1800L, "Óleo" to 900L),
        )
        assertEquals(ResumoAlteracoes(criados = 1, aumentados = 1, diminuidos = 1, inalterados = 1), r)
        assertEquals(2, r.operacoes)
    }

    @Test fun `modo rede conta uma vez, varejo conta por loja`() {
        assertEquals(1, CalculoCota.custoPorModo(true, modoRede = true, lojasAfetadas = 3))
        assertEquals(3, CalculoCota.custoPorModo(true, modoRede = false, lojasAfetadas = 3))
        assertEquals(0, CalculoCota.custoPorModo(false, modoRede = false, lojasAfetadas = 3))
    }
}
