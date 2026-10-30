package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate

/**
 * "Minhas notas": as notas fiscais que a própria pessoa enviou (função `minhas_notas` do banco).
 * Só o que já é público no preço: estabelecimento, produto e preço. Nada do comprador.
 */
data class LinhaMinhaNota(
    val notaId: String,
    val dataNf: LocalDate?,
    val enviadaEm: Instant,
    val pdvNome: String?,
    val endereco: String?,
    val produto: String,
    val precoCentavos: Long,
    val unidade: Unidade = Unidade.UNIDADE,
    val parteDeCombo: Boolean = false,
    val validade: LocalDate?,
    val naBusca: Boolean,
)

data class ItemMinhaNota(val produto: String, val precoCentavos: Long, val unidade: Unidade, val parteDeCombo: Boolean) {
    val preco: String get() = PrecoExibido.texto(precoCentavos, unidade)
}

data class MinhaNota(
    val id: String,
    val dataNf: LocalDate?,
    val enviadaEm: Instant,
    val pdvNome: String,
    val endereco: String?,
    val validade: LocalDate?,
    val naBusca: Boolean,
    val itens: List<ItemMinhaNota>,
) {
    /** "Compra em 28/10/2026" (data da nota). */
    val dataTexto: String get() = dataNf?.let { "Compra em ${Validade.formatar(it)}" } ?: "Data da compra não informada"

    /** Situação dos preços desta nota na busca. */
    val situacao: String
        get() = when {
            naBusca && validade != null -> "Na busca até ${Validade.formatar(validade)}"
            naBusca -> "Na busca"
            else -> "Fora da busca (preço de nota aparece por ${Validade.NF_DIAS} dias)"
        }

    val quantidadeTexto: String get() = if (itens.size == 1) "1 item" else "${itens.size} itens"
}

object MinhasNotas {
    /** Agrupa as linhas por nota (envio), da mais recente para a mais antiga; itens por nome. */
    fun agrupar(linhas: List<LinhaMinhaNota>): List<MinhaNota> =
        linhas.groupBy { it.notaId }.map { (id, ls) ->
            val p = ls.first()
            MinhaNota(
                id = id,
                dataNf = p.dataNf,
                enviadaEm = ls.minOf { it.enviadaEm },
                pdvNome = p.pdvNome?.takeIf { it.isNotBlank() } ?: "Estabelecimento sem nome",
                endereco = p.endereco?.takeIf { it.isNotBlank() },
                validade = ls.mapNotNull { it.validade }.maxOrNull(),
                naBusca = ls.any { it.naBusca },
                itens = ls.sortedBy { Texto.normalizar(it.produto) }
                    .map { ItemMinhaNota(it.produto, it.precoCentavos, it.unidade, it.parteDeCombo) },
            )
        }.sortedByDescending { it.enviadaEm }
}
