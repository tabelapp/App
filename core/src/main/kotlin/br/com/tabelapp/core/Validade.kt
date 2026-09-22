package br.com.tabelapp.core

import java.time.LocalDate

/** Validade do preço do PDV: de hoje até no máximo 30 dias (briefing, seção 5). */
object Validade {
    const val MAXIMO_DIAS = 30L

    /** Preço de usuário (NF/encarte) sem validade explícita fica visível por este período. */
    const val VIGENCIA_PRECO_USUARIO_DIAS = 30L

    fun padrao(hoje: LocalDate): LocalDate = hoje.plusDays(MAXIMO_DIAS)

    fun validar(validade: LocalDate, hoje: LocalDate): ErroValidade? = when {
        validade.isBefore(hoje) -> ErroValidade.NO_PASSADO
        validade.isAfter(hoje.plusDays(MAXIMO_DIAS)) -> ErroValidade.MAIOR_QUE_30_DIAS
        else -> null
    }

    enum class ErroValidade(val mensagem: String) {
        NO_PASSADO("A validade não pode estar no passado."),
        MAIOR_QUE_30_DIAS("A validade pode ser de no máximo 30 dias."),
    }
}
