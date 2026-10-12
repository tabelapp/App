package br.com.tabelapp.core

/** Textos prontos para conversas pelo WhatsApp. */
object Mensagens {
    /** Cliente falando com o comércio a partir de um card de preço. */
    fun interesseNaOferta(c: Cotacao): String =
        "Olá! Vi a oferta de *${c.produto}* " +
            (if (c.aPartirDe) "a partir de " else "por ") + Dinheiro.formatar(c.precoCentavos) +
            PrecoExibido.sufixo(c.unidade) + " no Tabelapp e gostaria de mais informações."

    const val FALE_CONOSCO = "Olá! Vim pelo app Tabelapp e gostaria de falar com vocês."
    const val QUERO_ANUNCIAR = "Olá! Tenho um comércio e quero anunciar no Tabelapp."

    /** PDV combinando o pagamento (até o Pix automático). */
    fun pagamento(pdvNome: String, descricao: String, valorCentavos: Long, codigo: String): String =
        "Olá! Sou do *$pdvNome* e quero pagar: $descricao — ${Dinheiro.formatar(valorCentavos)}. " +
            "Código do pedido: ${codigo.take(8).uppercase()}"
}
