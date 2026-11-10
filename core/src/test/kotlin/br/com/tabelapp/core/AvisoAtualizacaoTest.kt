package br.com.tabelapp.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvisoAtualizacaoTest {
    private val hoje = LocalDate.of(2026, 11, 10)

    @Test fun `so mostra se houver atualizacao`() {
        assertTrue(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.DISPONIVEL, null, hoje, ocupado = false))
        assertFalse(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.NENHUMA, null, hoje, ocupado = false))
        assertFalse(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.BAIXANDO, null, hoje, ocupado = false))
    }

    @Test fun `dispensado hoje nao repete no mesmo dia, mas volta amanha`() {
        assertFalse(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.DISPONIVEL, hoje, hoje, ocupado = false))
        assertTrue(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.DISPONIVEL, hoje, hoje.plusDays(1), ocupado = false))
        assertTrue(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.DISPONIVEL, hoje.minusDays(1), hoje, ocupado = false))
    }

    @Test fun `nunca interrompe a leitura de nota nem o envio`() {
        assertFalse(AvisoAtualizacao.mostrarDisponivel(EstadoAtualizacao.DISPONIVEL, null, hoje, ocupado = true))
        assertFalse(AvisoAtualizacao.mostrarReiniciar(EstadoAtualizacao.BAIXADA, ocupado = true))
    }

    @Test fun `reiniciar so depois de baixada`() {
        assertTrue(AvisoAtualizacao.mostrarReiniciar(EstadoAtualizacao.BAIXADA, ocupado = false))
        assertFalse(AvisoAtualizacao.mostrarReiniciar(EstadoAtualizacao.BAIXANDO, ocupado = false))
        assertFalse(AvisoAtualizacao.mostrarReiniciar(EstadoAtualizacao.DISPONIVEL, ocupado = false))
    }
}
