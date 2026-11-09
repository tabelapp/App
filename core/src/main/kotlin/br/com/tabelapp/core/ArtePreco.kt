package br.com.tabelapp.core

/**
 * O que vai na arte de compartilhar um preço (imagem 1080x1080 enviada pelo WhatsApp).
 * O app só desenha; aqui fica a regra do selo "no app" e dos textos.
 */
data class ConteudoArte(
    val produto: String,
    /** "R$ 5,98" (o preço principal, o menor). */
    val preco: String,
    /** "a partir de" antes do preço (serviços). */
    val aPartirDe: Boolean,
    /** "/ kg", "/ hora"... ou vazio. */
    val sufixoUnidade: String,
    /** Selo "no app": nunca em Nota Fiscal. */
    val seloApp: Boolean,
    /** Preço normal riscado ("R$ 6,99"), só com o selo e se for maior. */
    val precoNormal: String?,
    val local: String,
    val endereco: String?,
    /** "Oferta válida até 10/11/2026 · produto de encarte". */
    val detalhes: String,
)

object ArtePreco {
    const val SELO = PrecoExibido.LEGENDA_APP
    const val ROTULO_NORMAL = "normal"

    fun conteudo(c: Cotacao): ConteudoArte = ConteudoArte(
        produto = c.produto,
        preco = Dinheiro.formatar(c.precoCentavos),
        aPartirDe = c.aPartirDe,
        sufixoUnidade = PrecoExibido.sufixo(c.unidade).trim(),
        seloApp = c.temPrecoApp,
        precoNormal = c.precoNormalRiscado?.let(Dinheiro::formatar),
        local = listOfNotNull(c.pdvNome, c.lojaNome).filter { it.isNotBlank() }.joinToString(" — "),
        endereco = c.endereco?.takeIf { it.isNotBlank() },
        detalhes = "${Validade.exibir(c)} · ${Obs.exibir(c)}",
    )
}
