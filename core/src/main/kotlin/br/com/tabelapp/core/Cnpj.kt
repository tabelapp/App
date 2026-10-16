package br.com.tabelapp.core

object Cnpj {
    /** Só dígitos, se for um CNPJ válido (com dígitos verificadores corretos); senão null. */
    fun normalizar(texto: String): String? {
        val d = texto.filter { it.isDigit() }
        if (d.length != 14 || d.all { it == d[0] }) return null
        val dv1 = dv(d.substring(0, 12), intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
        val dv2 = dv(d.substring(0, 12) + dv1, intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
        return if (d[12] - '0' == dv1 && d[13] - '0' == dv2) d else null
    }

    fun valido(texto: String): Boolean = normalizar(texto) != null

    /** "11222333000181" -> "11.222.333/0001-81" */
    fun formatar(digitos: String): String {
        require(digitos.length == 14)
        return "${digitos.substring(0, 2)}.${digitos.substring(2, 5)}.${digitos.substring(5, 8)}/" +
            "${digitos.substring(8, 12)}-${digitos.substring(12)}"
    }

    private fun dv(base: String, pesos: IntArray): Int {
        val soma = base.mapIndexed { i, c -> (c - '0') * pesos[i] }.sum()
        val resto = soma % 11
        return if (resto < 2) 0 else 11 - resto
    }
}
