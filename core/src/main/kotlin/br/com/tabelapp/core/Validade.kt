package br.com.tabelapp.core

import java.time.LocalDate

/**
 * Todo preço tem validade:
 *  - PDV: de hoje até no máximo 30 dias (padrão: 30).
 *  - Nota Fiscal: 1 dia (data do envio + 1).
 *  - Encarte de usuário: a data impressa no encarte.
 */
object Validade {
    const val MAXIMO_DIAS = 30L
    const val NF_DIAS = 1L

    fun padrao(hoje: LocalDate): LocalDate = hoje.plusDays(MAXIMO_DIAS)

    fun daNotaFiscal(dataEnvio: LocalDate): LocalDate = dataEnvio.plusDays(NF_DIAS)

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
