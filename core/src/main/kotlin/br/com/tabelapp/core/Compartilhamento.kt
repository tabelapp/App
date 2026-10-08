package br.com.tabelapp.core

/**
 * Texto que acompanha a imagem do card de preço compartilhado (redes sociais,
 * WhatsApp...). Pedido do fundador: "olha esse preço" + imagem + chamada para
 * baixar o app.
 */
object Compartilhamento {
    /** Onde baixar o app. ⚠️ Forma de distribuição ainda a definir: hoje aponta para a Play Store. */
    const val LINK_APP = "https://play.google.com/store/apps/details?id=br.com.tabelapp"

    fun texto(cotacao: Cotacao, link: String = LINK_APP): String = buildString {
        append("Olha esse preço! 👀\n")
        append("${cotacao.produto}: ${Dinheiro.formatar(cotacao.precoCentavos)}")
        append(" em ${listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")}")
        cotacao.endereco?.let { append(" ($it)") }
        append("\n\nQuem pesquisa economiza. Pesquise mais preços com o Tabelapp:\n")
        append(link)
    }
}
