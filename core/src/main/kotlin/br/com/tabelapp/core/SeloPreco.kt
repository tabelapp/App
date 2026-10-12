package br.com.tabelapp.core

/**
 * Parâmetros do selo "Menor preço lançado" e do "Confira na loja". Vêm da tabela
 * `configuracao_app` do banco (ajustáveis sem publicar app); estes são os padrões.
 */
data class ConfigConfianca(
    /** Preço abaixo desta fração da mediana dos DEMAIS resultados é atípico. */
    val fracaoAtipico: Double = 0.4,
    /** O detector de atípico só vale com pelo menos este número de resultados. */
    val minResultados: Int = 3,
    /** O selo prefere PDV cadastrado quando o preço dele está até esta fração acima do menor. */
    val toleranciaPdv: Double = 0.05,
    /** Denúncias "Esse preço não confere" para mostrar "Confira na loja" e tirar o selo. */
    val limiteDenuncias: Int = 3,
)

/** O destaque de cada card (no máximo um, para o card não crescer). */
enum class Destaque { MENOR_PRECO, PARTE_DE_COMBO, CONFIRA_NA_LOJA }

/**
 * Selo prudente (caso real: "Pão, carne e queijo" a R$ 4,00 numa NF era o valor
 * rateado de um combo de R$ 31):
 *  - item de combo, preço atípico ou muito denunciado nunca recebe o selo;
 *  - entre os demais, o menor preço — preferindo PDV cadastrado se o valor for
 *    igual ou até [ConfigConfianca.toleranciaPdv] acima;
 *  - atípico/denunciado aparece normalmente, com o chip "Confira na loja".
 */
object SeloPreco {

    private fun mediana(valores: List<Long>): Double {
        val v = valores.sorted()
        if (v.isEmpty()) return 0.0
        val meio = v.size / 2
        return if (v.size % 2 == 1) v[meio].toDouble() else (v[meio - 1] + v[meio]) / 2.0
    }

    /** Ids dos preços muito abaixo da mediana dos demais resultados da mesma busca. */
    fun atipicos(cotacoes: List<Cotacao>, config: ConfigConfianca = ConfigConfianca()): Set<String> {
        if (cotacoes.size < config.minResultados) return emptySet()
        return cotacoes.filterIndexed { i, c ->
            val demais = cotacoes.filterIndexed { j, _ -> j != i }.map { it.precoCentavos }
            c.precoCentavos < config.fracaoAtipico * mediana(demais)
        }.mapTo(mutableSetOf()) { it.id }
    }

    fun muitoDenunciada(c: Cotacao, config: ConfigConfianca = ConfigConfianca()): Boolean =
        c.denuncias >= config.limiteDenuncias

    /** Ids que recebem o selo "Menor preço lançado" (empates recebem todos). */
    fun idsComSelo(cotacoes: List<Cotacao>, config: ConfigConfianca = ConfigConfianca()): Set<String> {
        val atipicos = atipicos(cotacoes, config)
        val elegiveis = cotacoes.filter { !it.parteDeCombo && !muitoDenunciada(it, config) && it.id !in atipicos }
        val menor = elegiveis.minOfOrNull { it.precoCentavos } ?: return emptySet()
        val pdvPerto = elegiveis.filter { it.fonte.ehDoPdv && it.precoCentavos <= menor * (1 + config.toleranciaPdv) + 1e-9 }
        val escolhidos = if (pdvPerto.isNotEmpty()) {
            val menorPdv = pdvPerto.minOf { it.precoCentavos }
            pdvPerto.filter { it.precoCentavos == menorPdv }
        } else {
            elegiveis.filter { it.precoCentavos == menor }
        }
        return escolhidos.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Destaque de cada card. Sem busca (tela inicial com produtos variados) não há
     * "menor preço"; os avisos de combo e "Confira na loja" valem sempre.
     */
    fun destaques(cotacoes: List<Cotacao>, comBusca: Boolean, config: ConfigConfianca = ConfigConfianca()): Map<String, Destaque> {
        val selo = if (comBusca) idsComSelo(cotacoes, config) else emptySet()
        val atipicos = if (comBusca) atipicos(cotacoes, config) else emptySet()
        return cotacoes.mapNotNull { c ->
            when {
                c.id in selo -> Destaque.MENOR_PRECO
                c.parteDeCombo -> Destaque.PARTE_DE_COMBO
                c.id in atipicos || muitoDenunciada(c, config) -> Destaque.CONFIRA_NA_LOJA
                else -> null
            }?.let { c.id to it }
        }.toMap()
    }
}
