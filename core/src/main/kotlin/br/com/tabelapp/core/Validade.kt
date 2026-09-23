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

    const val TEXTO_NF = "Preço praticado hoje"
    const val TEXTO_SEM_VALIDADE = "Validade não informada"

    private val formatoData = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /**
     * O que aparece no campo "validade" da busca. Lógica de apresentação, como o OBS:
     *  - Nota Fiscal -> "Preço praticado hoje" (é o preço pago no caixa, não uma promessa de validade)
     *  - demais      -> "Válido até dd/MM/aaaa"
     */
    fun exibir(fonte: Fonte, validade: LocalDate?): String = when {
        fonte == Fonte.USUARIO_NF -> TEXTO_NF
        validade == null -> TEXTO_SEM_VALIDADE
        else -> "Válido até ${validade.format(formatoData)}"
    }

    fun exibir(cotacao: Cotacao): String = exibir(cotacao.fonte, cotacao.validade)

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
