package br.com.tabelapp.core

import java.time.LocalDate

/**
 * Todo preço tem validade (até quando aparece na busca):
 *  - PDV: de hoje até no máximo 30 dias (padrão: 30).
 *  - Nota Fiscal: o preço é o praticado na data da nota (vale até a meia-noite
 *    daquele dia), mas continua visível na busca por 7 dias a partir dela.
 *  - Encarte de usuário: a data impressa no encarte.
 */
object Validade {
    const val MAXIMO_DIAS = 30L
    const val NF_DIAS = 7L

    fun padrao(hoje: LocalDate): LocalDate = hoje.plusDays(MAXIMO_DIAS)

    /** Até quando o preço de uma NF aparece na busca. */
    fun daNotaFiscal(dataNf: LocalDate): LocalDate = dataNf.plusDays(NF_DIAS)

    const val TEXTO_SEM_VALIDADE = "Validade não informada"

    private val formatoData = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun formatar(data: LocalDate): String = data.format(formatoData)

    /**
     * O que aparece no campo "validade" da busca. Lógica de apresentação, como o OBS:
     *  - Nota Fiscal -> "Preço praticado dia dd/MM/aaaa" (a data da nota)
     *  - demais      -> "Válido até dd/MM/aaaa"
     */
    fun exibir(fonte: Fonte, validade: LocalDate?, dataNf: LocalDate? = null): String {
        if (fonte == Fonte.USUARIO_NF) {
            val dia = dataNf ?: validade?.minusDays(NF_DIAS)
            return if (dia != null) "Preço praticado dia ${formatar(dia)}" else "Preço praticado"
        }
        return if (validade == null) TEXTO_SEM_VALIDADE else "Válido até ${formatar(validade)}"
    }

    fun exibir(cotacao: Cotacao): String = exibir(cotacao.fonte, cotacao.validade, cotacao.dataNf)

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
