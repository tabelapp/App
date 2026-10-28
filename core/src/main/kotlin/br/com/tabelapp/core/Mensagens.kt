package br.com.tabelapp.core

/** Textos prontos para conversas pelo WhatsApp. */
object Mensagens {
    /** Cliente falando com o comércio a partir de um card de preço. */
    fun interesseNaOferta(c: Cotacao): String =
        "Olá! Vi a oferta de *${c.produto}* " +
            (if (c.aPartirDe) "a partir de " else "por ") + Dinheiro.formatar(c.precoCentavos) +
            PrecoExibido.sufixo(c.unidade) + " no Tabelapp e gostaria de mais informações."

    /** Cliente falando com a loja a partir da "Página da loja". */
    fun contatoLoja(nomeLoja: String): String =
        "Olá! Vi a página da *$nomeLoja* no Tabelapp e gostaria de mais informações."

    const val FALE_CONOSCO = "Olá! Vim pelo app Tabelapp e gostaria de falar com vocês."

    /** Dono de estabelecimento suspenso pedindo para conversar sobre a suspensão. */
    fun suspensao(pdvNome: String, cnpj: String): String =
        "Olá! Sou do *$pdvNome* (CNPJ ${Cnpj.formatar(cnpj)}) e quero conversar sobre a suspensão do cadastro no Tabelapp."
}
