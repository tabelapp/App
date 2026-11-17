package br.com.tabelapp.core

/**
 * De onde veio o preço, para o ícone pequeno do card (1.0.5). É a mesma informação da linha
 * "Origem" da página do produto (`Fonte`); nada novo no banco.
 */
enum class OrigemPreco(val texto: String) {
    NOTA_FISCAL("Preço de nota fiscal"),
    ENCARTE("Preço de encarte"),
    TABELA_DA_LOJA("Preço da tabela da loja");

    companion object {
        fun de(fonte: Fonte?): OrigemPreco? = when (fonte) {
            Fonte.USUARIO_NF -> NOTA_FISCAL
            Fonte.USUARIO_ENCARTE -> ENCARTE
            Fonte.PDV_MANUAL, Fonte.PDV_EXCEL -> TABELA_DA_LOJA
            null -> null
        }

        /** Pelo código do banco (`fonte_cotacao`). Valor desconhecido ou vazio: sem ícone (nunca inventar). */
        fun doCodigo(codigo: String?): OrigemPreco? =
            de(Fonte.entries.firstOrNull { it.codigo == codigo?.trim() })
    }
}
