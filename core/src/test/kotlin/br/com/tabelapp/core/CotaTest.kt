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
        val saldo = SaldoCota(gratisUsadas = 38)
        assertEquals(12, saldo.restantes)
        assertTrue(saldo.simular(12).cabeNaCota)

        val s = saldo.simular(60) // faltam 48 -> 1 pacote
        assertFalse(s.cabeNaCota)
        assertEquals(1, s.pacotesNecessarios)
        assertEquals(1000, s.valorCentavos)

        assertEquals(2, saldo.simular(63).pacotesNecessarios) // faltam 51 -> 2 pacotes
        // Pacote ativo soma ao que sobrou das grátis; grátis esgotadas não ficam negativas.
        assertEquals(62, SaldoCota(gratisUsadas = 38, saldoPacotes = 50).restantes)
        assertEquals(12, SaldoCota(gratisUsadas = 50, saldoPacotes = 12).restantes)
        assertEquals(0, SaldoCota(gratisUsadas = 50).restantes)
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

    @Test fun `cada alteracao que conta custa 1 da cota da loja ou da rede`() {
        assertEquals(1, CalculoCota.custoPorCota(true))
        assertEquals(0, CalculoCota.custoPorCota(false))
    }
}
