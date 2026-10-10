package br.com.tabelapp.core

import java.text.Normalizer

object Texto {
    private val marcas = "\\p{M}+".toRegex()
    private val espacos = "\\s+".toRegex()

    /**
     * Mesma normalização da função `normalizar()` do banco:
     * minúsculo, sem acento, espaços colapsados. "Feijão  PRETO" -> "feijao preto".
     */
    fun normalizar(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(marcas, "")
            .lowercase()
            .replace(espacos, " ")
            .trim()

    /** Todas as palavras do termo precisam aparecer no produto (mesma regra da busca no banco). */
    fun casaBusca(produto: String, termo: String): Boolean {
        val palavras = normalizar(termo).split(' ').filter { it.isNotEmpty() }
        if (palavras.isEmpty()) return true
        val alvo = normalizar(produto)
        return palavras.all { it in alvo }
    }
}
