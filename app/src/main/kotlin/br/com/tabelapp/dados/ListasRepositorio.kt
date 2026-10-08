package br.com.tabelapp.dados

import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.SugestaoProduto

/** Listas de compras do usuário. Cada alteração é salva na hora. */
interface ListasRepositorio {
    suspend fun listas(): List<ListaResumo>
    suspend fun criar(nome: String): String
    suspend fun renomear(listaId: String, nome: String)
    suspend fun excluir(listaId: String)

    suspend fun itens(listaId: String): List<ItemSalvo>
    /** Produto já na lista (mesmo nome) soma a quantidade. */
    suspend fun adicionar(listaId: String, produto: String, quantidade: Double = 1.0)
    suspend fun alterarQuantidade(itemId: String, quantidade: Double)
    suspend fun remover(itemId: String)

    /** Busca inteligente: produtos com preço válido que casam com o que foi digitado. */
    suspend fun sugerir(termo: String): List<SugestaoProduto>
}
