package br.com.tabelapp.dados

import br.com.tabelapp.core.ItemLidoEncarte
import br.com.tabelapp.core.LeituraEncarte

/** Resultado de "Lançar preços": o lote novo e quantos entraram (repetidos são ignorados pelo banco). */
data class LoteLancado(val loteId: String, val lancados: Int, val recebidos: Int)

/** Loja/endereço já usados em encartes (sugestão no formulário). */
data class LojaEncarte(val nome: String, val endereco: String?)

/**
 * Leitor de encartes da aba Admin (1.0.4). Só o Admin usa; o servidor confere de novo.
 * A imagem vai só para a leitura (não é guardada).
 */
interface EncartesAdminRepositorio {
    /** [tipo]: "image/jpeg", "image/png" ou "application/pdf". */
    suspend fun ler(arquivo: ByteArray, tipo: String, loja: String): LeituraEncarte

    suspend fun lancar(loja: String, endereco: String, itens: List<ItemLidoEncarte>): LoteLancado

    /** Apaga os preços do lote (só encarte, só nas últimas 48 h). Devolve quantos saíram. */
    suspend fun desfazer(loteId: String): Int

    suspend fun lojasConhecidas(): List<LojaEncarte>
}
