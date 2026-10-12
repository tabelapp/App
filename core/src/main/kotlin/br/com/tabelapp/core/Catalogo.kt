package br.com.tabelapp.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Limite por TAMANHO DO CATÁLOGO (substituiu a cota de 50 operações/mês).
 *
 *  - O limite é de itens ATIVOS (não excluídos e dentro da validade). Varejo:
 *    por loja. Rede: o catálogo único da rede.
 *  - Só criar item novo é conferido; atualizar preço, renovar validade, mudar
 *    OBS/unidade e excluir nunca contam nem são bloqueados.
 *  - Faixas na tabela `planos` do banco; o banco (`pdv_salvar_precos`) é quem manda.
 */
enum class TipoOperacao {
    CRIAR_ITEM, AUMENTAR_PRECO, DIMINUIR_PRECO, EXCLUIR_ITEM, EDITAR_DADOS;

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

/** Um plano (faixa de itens ativos). Espelha a tabela `planos`. */
data class Plano(
    val codigo: String,
    val nome: String,
    val limiteItens: Int,
    /** Preço de referência (null = a definir; 0 = grátis). Ainda não há cobrança. */
    val precoReferenciaCentavos: Long?,
    val ordem: Int,
)

/** Avisos do fim do período de parceiro fundador. */
enum class AvisoFundador { NENHUM, FAIXA, FALTAM_POUCOS_DIAS, ULTIMO_DIA }

/** Situação do catálogo da loja (ou da rede). Espelha `status_catalogo()` do banco. */
data class StatusCatalogo(
    val plano: String,
    val planoNome: String,
    /** Limite do plano contratado (vale depois do período de fundador). */
    val limitePlano: Int,
    val planoEfetivo: String,
    val planoEfetivoNome: String,
    /** Limite que vale hoje. */
    val limite: Int,
    val itensAtivos: Int,
    val disponiveis: Int,
    val fundadorAte: LocalDate?,
    val avisoFundadorDias: Int = 7,
) {
    val noLimite: Boolean get() = disponiveis <= 0

    fun fundadorAtivo(hoje: LocalDate): Boolean = fundadorAte != null && !fundadorAte.isBefore(hoje)

    /** Dias que faltam para acabar o período de fundador (0 = hoje é o último dia). */
    fun diasDeFundador(hoje: LocalDate): Long? =
        fundadorAte?.takeIf { !it.isBefore(hoje) }?.let { ChronoUnit.DAYS.between(hoje, it) }

    fun avisoFundador(hoje: LocalDate): AvisoFundador {
        val dias = diasDeFundador(hoje) ?: return AvisoFundador.NENHUM
        return when {
            dias == 0L -> AvisoFundador.ULTIMO_DIA
            dias <= avisoFundadorDias -> AvisoFundador.FALTAM_POUCOS_DIAS
            else -> AvisoFundador.FAIXA
        }
    }
}

/**
 * Unidade do preço (produtos e serviços). [sufixo] aparece depois do preço
 * ("R$ 12,90 / kg"); "unidade" não tem sufixo.
 */
enum class Unidade(val codigo: String, val rotulo: String, val sufixo: String?) {
    UNIDADE("unidade", "Unidade", null),
    KG("kg", "Quilo (kg)", "kg"),
    LITRO("litro", "Litro", "litro"),
    METRO("metro", "Metro", "metro"),
    M2("m2", "Metro quadrado (m²)", "m²"),
    HORA("hora", "Hora", "hora"),
    VISITA("visita", "Visita", "visita"),
    DIARIA("diaria", "Diária", "diária"),
    SERVICO("servico", "Serviço", "serviço");

    companion object {
        fun deCodigo(codigo: String?): Unidade = entries.firstOrNull { it.codigo == codigo } ?: UNIDADE
    }
}

/** Como o preço aparece: "R$ 12,90 / kg", "A partir de R$ 80,00 / hora", "R$ 5,00". */
object PrecoExibido {
    const val A_PARTIR_DE = "A partir de"

    /** Só o sufixo da unidade (" / kg"), ou vazio para "unidade". */
    fun sufixo(unidade: Unidade): String = unidade.sufixo?.let { " / $it" }.orEmpty()

    fun texto(centavos: Long, unidade: Unidade = Unidade.UNIDADE, aPartirDe: Boolean = false): String =
        (if (aPartirDe) "$A_PARTIR_DE " else "") + Dinheiro.formatar(centavos) + sufixo(unidade)

    fun texto(c: Cotacao): String = texto(c.precoCentavos, c.unidade, c.aPartirDe)
}

/** Item da planilha que ficou fora do limite do plano. */
data class ItemFora(val linha: Int, val produto: String)

/** Prévia/resultado da importação da planilha (espelha a resposta de `pdv_salvar_precos`). */
data class ResultadoImportacao(
    val criados: Int,
    val aumentados: Int,
    val diminuidos: Int,
    val inalterados: Int,
    val foraDoLimite: Int,
    val itensFora: List<ItemFora>,
    val limite: Int,
    val planoNome: String,
) {
    /** Linhas que entram (atualizações nunca ficam de fora). */
    val importados: Int get() = criados + aumentados + diminuidos + inalterados

    /** CSV com os itens que ficaram de fora (para o PDV baixar). */
    fun csvDosQueFicaramDeFora(): String =
        "Linha;Produto\n" + itensFora.joinToString("") { "${it.linha};\"${it.produto.replace("\"", "\"\"")}\"\n" }
}
