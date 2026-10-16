package br.com.tabelapp.core

import java.time.Instant

/** Uma lista de compras salva do usuário. */
data class ListaResumo(val id: String, val nome: String, val itens: Int, val atualizadaEm: Instant)

/** Um item salvo da lista (o produto como o usuário escolheu e a quantidade). */
data class ItemSalvo(val id: String, val produto: String, val quantidade: Double) {
    fun paraItemLista() = ItemLista(produto, quantidade)
}

/** Sugestão da busca inteligente ao adicionar um item. */
data class SugestaoProduto(val produto: String, val menorPrecoCentavos: Long, val lugares: Int)
