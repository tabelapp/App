package br.com.tabelapp.core

/** Política de Privacidade e Termos de Uso (arquivos .md em app/src/main/res/raw). */
object Termos {
    /** Versão aceita no cadastro; mudar quando os textos mudarem (e a data no topo dos arquivos). */
    const val VERSAO = "2026-10-13"
}

/**
 * Leitura simples do Markdown dos textos legais, para o app mostrar sem biblioteca
 * extra: títulos (#, ##), itens (- ) e parágrafos, com trechos em **negrito**.
 */
object TextoLegal {
    sealed interface Bloco {
        data class Titulo(val texto: String) : Bloco
        data class Subtitulo(val texto: String) : Bloco
        data class Item(val texto: String) : Bloco
        data class Paragrafo(val texto: String) : Bloco
    }

    /** Pedaços de um texto: (trecho, está em negrito). */
    fun trechos(texto: String): List<Pair<String, Boolean>> =
        texto.split("**").mapIndexedNotNull { i, t -> t.takeIf { it.isNotEmpty() }?.let { it to (i % 2 == 1) } }

    fun blocos(markdown: String): List<Bloco> =
        markdown.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { linha ->
            when {
                linha.startsWith("## ") -> Bloco.Subtitulo(linha.removePrefix("## "))
                linha.startsWith("# ") -> Bloco.Titulo(linha.removePrefix("# "))
                linha.startsWith("- ") -> Bloco.Item(linha.removePrefix("- "))
                else -> Bloco.Paragrafo(linha)
            }
        }
}
