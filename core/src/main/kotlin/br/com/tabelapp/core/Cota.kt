package br.com.tabelapp.core

/**
 * Cota de operações do PDV (briefing, seção 5).
 *
 *  - 50 operações grátis por mês.
 *  - Só CRIAR item ou AUMENTAR preço conta. Diminuir preço, excluir e editar
 *    só OBS/validade são sempre grátis.
 *  - R$10 via Pix = +50 operações (valem no mês da compra).
 *  - Modo rede: uma edição replicada para todas as lojas conta 1.
 *    Modo varejo: cada loja editada conta separadamente.
 *
 * O banco (função `pdv_salvar_precos`) é quem manda; esta classe serve para o
 * app mostrar o custo ANTES de enviar (ex.: prévia da importação da planilha).
 */
object RegrasCota {
    const val GRATIS_POR_MES = 50
    const val OPERACOES_POR_PACOTE = 50
    const val PRECO_PACOTE_CENTAVOS = 1000L
}

enum class TipoOperacao(val contaNaCota: Boolean) {
    CRIAR_ITEM(true),
    AUMENTAR_PRECO(true),
    DIMINUIR_PRECO(false),
    EXCLUIR_ITEM(false),
    EDITAR_DADOS(false);

    companion object {
        /**
         * @param precoAtual preço vigente do item (null = item ainda não existe)
         * @param precoNovo  preço que o PDV quer gravar (null = excluir)
         */
        fun classificar(precoAtual: Long?, precoNovo: Long?): TipoOperacao = when {
            precoNovo == null -> EXCLUIR_ITEM
            precoAtual == null -> CRIAR_ITEM
            precoNovo > precoAtual -> AUMENTAR_PRECO
            precoNovo < precoAtual -> DIMINUIR_PRECO
            else -> EDITAR_DADOS
        }
    }
}

data class SaldoCota(val usadas: Int, val compradas: Int = 0) {
    val limite: Int get() = RegrasCota.GRATIS_POR_MES + compradas
    val restantes: Int get() = (limite - usadas).coerceAtLeast(0)

    fun simular(operacoes: Int): SimulacaoCota {
        val faltam = (operacoes - restantes).coerceAtLeast(0)
        val pacotes = (faltam + RegrasCota.OPERACOES_POR_PACOTE - 1) / RegrasCota.OPERACOES_POR_PACOTE
        return SimulacaoCota(
            operacoes = operacoes,
            restantes = restantes,
            pacotesNecessarios = pacotes,
            valorCentavos = pacotes * RegrasCota.PRECO_PACOTE_CENTAVOS,
        )
    }
}

data class SimulacaoCota(
    val operacoes: Int,
    val restantes: Int,
    val pacotesNecessarios: Int,
    val valorCentavos: Long,
) {
    val cabeNaCota: Boolean get() = pacotesNecessarios == 0
}

data class ResumoAlteracoes(
    val criados: Int,
    val aumentados: Int,
    val diminuidos: Int,
    val inalterados: Int,
) {
    val operacoes: Int get() = criados + aumentados
}

object CalculoCota {
    /**
     * Resume uma leva de alterações (edição manual ou planilha) para UMA loja
     * (varejo) ou para a rede toda (rede — cada produto conta uma vez).
     *
     * @param precosAtuais preço vigente por produto normalizado ([Texto.normalizar]).
     *   Em modo rede, use o MAIOR preço entre as lojas (mesma regra do banco).
     * @param novos produto -> novo preço.
     */
    fun resumir(precosAtuais: Map<String, Long>, novos: Map<String, Long>): ResumoAlteracoes {
        val tipos = novos.map { (produto, preco) ->
            TipoOperacao.classificar(precosAtuais[Texto.normalizar(produto)], preco)
        }
        return ResumoAlteracoes(
            criados = tipos.count { it == TipoOperacao.CRIAR_ITEM },
            aumentados = tipos.count { it == TipoOperacao.AUMENTAR_PRECO },
            diminuidos = tipos.count { it == TipoOperacao.DIMINUIR_PRECO },
            inalterados = tipos.count { it == TipoOperacao.EDITAR_DADOS },
        )
    }

    /** Quantas operações uma mesma alteração custa conforme o modo do PDV. */
    fun custoPorModo(alteracaoContaNaCota: Boolean, modoRede: Boolean, lojasAfetadas: Int): Int = when {
        !alteracaoContaNaCota -> 0
        modoRede -> 1
        else -> lojasAfetadas
    }
}
