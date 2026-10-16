package br.com.tabelapp.core

/**
 * Regra do campo OBS na tela de busca (briefing, seção 2). É lógica de
 * apresentação: o banco guarda só o texto livre do PDV.
 *
 *  - Nota Fiscal            -> "NF"
 *  - Encarte de usuário     -> "produto de encarte"
 *  - PDV (manual ou Excel)  -> o texto do PDV; vazio -> "Preço oficial"
 */
object Obs {
    const val NF = "NF"
    const val ENCARTE = "produto de encarte"
    const val PRECO_OFICIAL = "Preço oficial"

    fun exibir(fonte: Fonte, obsDoPdv: String?): String = when (fonte) {
        Fonte.USUARIO_NF -> NF
        Fonte.USUARIO_ENCARTE -> ENCARTE
        Fonte.PDV_MANUAL, Fonte.PDV_EXCEL ->
            obsDoPdv?.trim()?.takeIf { it.isNotEmpty() } ?: PRECO_OFICIAL
    }

    fun exibir(cotacao: Cotacao): String = exibir(cotacao.fonte, cotacao.obs)
}
