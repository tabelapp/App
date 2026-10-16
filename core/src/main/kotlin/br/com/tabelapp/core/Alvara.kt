package br.com.tabelapp.core

/**
 * Conferência do alvará no cadastro do PDV: o texto lido da foto (OCR no celular)
 * precisa conter o CNPJ informado. É só um indício para o Admin — quem aprova é ele.
 */
object Alvara {
    // Letras que o OCR costuma confundir com números, quando estão no meio de números.
    private val parecidas = mapOf(
        'O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'l' to '1', 'i' to '1', '|' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2',
    )

    /** true se o CNPJ aparece no texto, com ou sem pontuação ("12.345.678/0001-90", "12345678000190"). */
    fun contemCnpj(texto: String, cnpj: String): Boolean {
        val alvo = cnpj.filter { it.isDigit() }
        if (alvo.length != 14) return false
        return alvo in sequenciaDeDigitos(texto)
    }

    /**
     * Junta os números do texto ignorando pontuação e espaços; qualquer outra coisa
     * (palavras) vira separador. Letra parecida com número só conta dentro de um
     * trecho que também tem algum número de verdade ("0OO1" sim, "DOS" não).
     */
    private fun sequenciaDeDigitos(texto: String): String {
        val saida = StringBuilder()
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (!c.isDigit() && c !in parecidas && c !in PONTUACAO) {
                saida.append('x')
                i++
                continue
            }
            var fim = i
            while (fim < texto.length && (texto[fim].isDigit() || texto[fim] in parecidas || texto[fim] in PONTUACAO)) fim++
            val trecho = texto.substring(i, fim)
            if (trecho.any { it.isDigit() }) {
                trecho.forEach { t ->
                    when {
                        t.isDigit() -> saida.append(t)
                        t in parecidas -> saida.append(parecidas.getValue(t))
                    }
                }
            } else if (trecho.any { it in parecidas }) {
                saida.append('x')
            }
            i = fim
        }
        return saida.toString()
    }

    private const val PONTUACAO = " .-/\t"
}
