package br.com.tabelapp.core

/**
 * Texto que acompanha a imagem do card de preço compartilhado (redes sociais,
 * WhatsApp...). Pedido do fundador: "olha esse preço" + imagem + chamada para
 * baixar o app.
 */
object Compartilhamento {
    /** Onde baixar o app. ⚠️ Forma de distribuição ainda a definir: hoje aponta para a Play Store. */
    const val LINK_APP = "https://play.google.com/store/apps/details?id=br.com.tabelapp"

    /**
     * @param aviso última frase ("Preço informado por usuário/estabelecimento e sujeito a alteração...").
     *   O app passa o texto do strings.xml conforme [porEstabelecimento].
     */
    fun texto(cotacao: Cotacao, link: String = LINK_APP, aviso: String? = null): String = buildString {
        append("Olha esse preço! 👀\n")
        append("${cotacao.produto}: ${PrecoExibido.textoCompartilhar(cotacao)}")
        append(" em ${listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")}")
        cotacao.endereco?.let { append(" ($it)") }
        append("\n\nQuem pesquisa economiza. Pesquise mais preços com o Tabelapp:\n")
        append(link)
        aviso?.let { append("\n\n").append(it) }
    }

    /** O preço foi informado pelo próprio estabelecimento (PDV) — senão, por um usuário (NF/encarte). */
    fun porEstabelecimento(cotacao: Cotacao): Boolean = cotacao.fonte.ehDoPdv
}
