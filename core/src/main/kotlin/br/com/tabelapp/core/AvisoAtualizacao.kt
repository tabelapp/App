package br.com.tabelapp.core

import java.time.LocalDate

/** Situação da atualização do app pela Play Store (modo flexível). */
enum class EstadoAtualizacao {
    /** Sem versão nova, ou não deu para saber (sem Play Store, sem internet, app de fora da Play). */
    NENHUMA,
    DISPONIVEL,
    /** A pessoa tocou em "Atualizar": baixando em segundo plano. */
    BAIXANDO,
    /** Baixada: falta reiniciar para concluir. */
    BAIXADA,
}

/**
 * Quando mostrar o aviso de atualização:
 *  - "Nova versão disponível": só se houver atualização, no máximo uma vez por dia (dispensado
 *    hoje com "Agora não" = não aparece de novo até amanhã);
 *  - "Reiniciar para concluir": quando a versão nova já foi baixada;
 *  - nunca no meio de uma leitura de nota ou envio de preços ([ocupado]).
 */
object AvisoAtualizacao {
    fun mostrarDisponivel(estado: EstadoAtualizacao, dispensadoEm: LocalDate?, hoje: LocalDate, ocupado: Boolean): Boolean =
        estado == EstadoAtualizacao.DISPONIVEL && dispensadoEm != hoje && !ocupado

    fun mostrarReiniciar(estado: EstadoAtualizacao, ocupado: Boolean): Boolean =
        estado == EstadoAtualizacao.BAIXADA && !ocupado
}
