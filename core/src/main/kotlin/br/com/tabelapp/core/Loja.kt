package br.com.tabelapp.core

import java.time.LocalDate

/**
 * Cabeçalho da "Página da loja" (função `pagina_loja` do banco): só dados públicos do
 * estabelecimento aprovado. Nada do dono (nem razão social nem CNPJ).
 */
data class PaginaLoja(
    val lojaId: String,
    val pdvId: String,
    val pdvNome: String,
    val lojaNome: String? = null,
    val endereco: String? = null,
    /** WhatsApp da loja (ou o telefone, se não houver WhatsApp). */
    val telefone: String? = null,
    val site: String? = null,
    val local: PontoGeo? = null,
) {
    /** "Supermercado Serra Imperial — Loja Centro" (ou só o nome do comércio). */
    val titulo: String get() = listOfNotNull(pdvNome, lojaNome?.takeIf { it.isNotBlank() }).joinToString(" — ")
}

/** Um item da tabela oficial da loja (função `precos_da_loja`): ativo e dentro da validade. */
data class ItemLoja(
    val id: String,
    val produto: String,
    val precoCentavos: Long,
    val validade: LocalDate,
    val obs: String? = null,
    val unidade: Unidade = Unidade.UNIDADE,
    val aPartirDe: Boolean = false,
) {
    val preco: String get() = PrecoExibido.texto(precoCentavos, unidade, aPartirDe)
    val validadeTexto: String get() = Validade.exibir(Fonte.PDV_MANUAL, validade)
}

object CompartilharLoja {
    /** Texto do "Compartilhar loja": nome, endereço, WhatsApp e onde baixar o app. */
    fun texto(loja: PaginaLoja, link: String = Compartilhamento.LINK_APP): String = buildString {
        append("Conheça ${loja.titulo} no Tabelapp! 🛒\n")
        loja.endereco?.takeIf { it.isNotBlank() }?.let { append("📍 $it\n") }
        loja.telefone?.takeIf { it.isNotBlank() }?.let { append("💬 WhatsApp: $it\n") }
        loja.site?.takeIf { it.isNotBlank() }?.let { append("🌐 $it\n") }
        append("\nVeja a tabela de preços da loja e compare antes de comprar. Quem pesquisa economiza:\n")
        append(link)
    }
}
