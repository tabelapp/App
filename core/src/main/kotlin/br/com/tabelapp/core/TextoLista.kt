package br.com.tabelapp.core

/**
 * Mensagens dos dois relatórios da lista de compras, prontas para o WhatsApp
 * (negrito com *asteriscos*, como o WhatsApp entende).
 */
object TextoLista {

    /** 2 -> "2"; 1.5 -> "1,5" */
    fun quantidade(q: Double): String =
        if (q % 1.0 == 0.0) q.toLong().toString() else q.toString().replace('.', ',')

    private fun linhaItem(c: ItemCotado, comLocal: Boolean): String = buildString {
        append("• ${quantidade(c.item.quantidade)}x ${c.item.produto}: ${Dinheiro.formatar(c.subtotalCentavos)}")
        if (c.item.quantidade != 1.0) append(" (${Dinheiro.formatar(c.cotacao.precoCentavos)} cada)")
        if (comLocal) append(" — ${c.cotacao.pdvNome}")
    }

    /** Relatório 1: onde a lista inteira sai mais barata (até [maxLocais] opções). */
    fun porPdv(nomeLista: String, opcoes: List<OpcaoPdvUnico>, totalItens: Int, maxLocais: Int = 3): String = buildString {
        append("🛒 *$nomeLista* — onde comprar tudo num lugar só\n")
        if (opcoes.isEmpty()) {
            append("\nNenhum preço encontrado para os itens da lista.\n")
        }
        opcoes.take(maxLocais).forEachIndexed { i, o ->
            append("\n*${i + 1}º ${o.pdvNome}* — ${Dinheiro.formatar(o.totalCentavos)}")
            append(if (o.completo) " (todos os $totalItens itens)" else " (${o.encontrados.size} de $totalItens itens)")
            append('\n')
            o.endereco?.let { append("$it\n") }
            if (i == 0) o.encontrados.forEach { append(linhaItem(it, comLocal = false)).append('\n') }
            if (o.faltando.isNotEmpty()) append("Faltam: ${o.faltando.joinToString(", ") { it.produto }}\n")
        }
        append(rodape())
    }

    /** Relatório 2: o mais barato de cada item, mesmo que em lugares diferentes. */
    fun porItem(nomeLista: String, melhor: MelhorPorItem): String = buildString {
        append("🛒 *$nomeLista* — item a item, onde está mais barato\n\n")
        melhor.itens.forEach { append(linhaItem(it, comLocal = true)).append('\n') }
        append("\n*Total: ${Dinheiro.formatar(melhor.totalCentavos)}*")
        if (melhor.itens.isNotEmpty()) {
            append(" em ${melhor.quantidadePdvs} ${if (melhor.quantidadePdvs == 1) "lugar" else "lugares"}")
        }
        append('\n')
        if (melhor.semPreco.isNotEmpty()) append("Sem preço encontrado: ${melhor.semPreco.joinToString(", ") { it.produto }}\n")
        append(rodape())
    }

    private fun rodape() =
        "\nQuem pesquisa economiza. Pesquise mais preços com o Tabelapp:\n${Compartilhamento.LINK_APP}"
}
