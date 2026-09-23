package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate

/** Situação do encarte na fila do Admin (mesmos códigos do enum `status_encarte`). */
enum class StatusEncarte(val codigo: String, val rotulo: String) {
    PENDENTE("pendente", "Aguardando aprovação"),
    APROVADO("aprovado", "Aprovado — já está na busca"),
    REJEITADO("rejeitado", "Não aprovado");

    companion object {
        fun doCodigo(codigo: String): StatusEncarte = entries.firstOrNull { it.codigo == codigo } ?: PENDENTE
    }
}

/** Um encarte que o usuário já enviou (lista "Meus encartes"). */
data class EncarteEnviado(
    val id: String,
    val pdvNome: String,
    val status: StatusEncarte,
    val validade: LocalDate?,
    val enviadoEm: Instant,
    val motivoRejeicao: String? = null,
)

/**
 * Envio de encarte pelo usuário comum (briefing, seção 4): fotos + estabelecimento
 * + (se o encarte mostrar) até quando valem as ofertas. Sem OCR no V1: o Admin
 * monta a tabela de preços olhando as fotos.
 */
data class RascunhoEncarte(
    val quantidadeFotos: Int = 0,
    val lojaId: String? = null,
    val pdvNome: String = "",
    val validade: LocalDate? = null,
) {
    fun erros(hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        if (quantidadeFotos == 0) add("Tire ou escolha pelo menos uma foto do encarte.")
        if (quantidadeFotos > MAX_FOTOS) add("Envie no máximo $MAX_FOTOS fotos por encarte.")
        if (lojaId == null && pdvNome.isBlank()) add("Diga de qual estabelecimento é o encarte.")
        if (validade != null && validade.isBefore(hoje)) add("A validade informada já passou.")
    }

    companion object {
        const val MAX_FOTOS = 5
    }
}
