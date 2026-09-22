package br.com.tabelapp.core

/** Valores em centavos (Long), como no banco. Nada de Double para dinheiro. */
object Dinheiro {

    /** 123456 -> "R$ 1.234,56" */
    fun formatar(centavos: Long): String {
        val negativo = centavos < 0
        val abs = kotlin.math.abs(centavos)
        val reais = (abs / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val cents = (abs % 100).toString().padStart(2, '0')
        return (if (negativo) "-R$ " else "R$ ") + reais + "," + cents
    }

    /**
     * Lê o que o usuário digitou ou o que veio da planilha.
     * Aceita "12,90", "R$ 1.234,56", "12.90", "1,234.56", "12". Retorna null se inválido.
     */
    fun parse(texto: String): Long? {
        var s = texto.trim().replace("R$", "", ignoreCase = true).replace(" ", "").replace(" ", "")
        if (s.isEmpty() || s.startsWith("-")) return null
        val ultimaVirgula = s.lastIndexOf(',')
        val ultimoPonto = s.lastIndexOf('.')
        s = when {
            ultimaVirgula >= 0 && ultimoPonto >= 0 ->
                if (ultimaVirgula > ultimoPonto) s.replace(".", "").replace(',', '.') // 1.234,56
                else s.replace(",", "")                                              // 1,234.56
            ultimaVirgula >= 0 -> s.replace(',', '.')                                // 12,90
            // "1.234" (milhar) vs "12.90" (decimal): 3 dígitos após o ponto = milhar
            ultimoPonto >= 0 && s.length - ultimoPonto - 1 == 3 && s.count { it == '.' } >= 1 -> s.replace(".", "")
            else -> s
        }
        if (!s.matches(Regex("""\d+(\.\d{1,2})?"""))) return null
        val partes = s.split('.')
        val reais = partes[0].toLongOrNull() ?: return null
        val cents = partes.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
        return reais * 100 + cents
    }
}
