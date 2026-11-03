package br.com.tabelapp.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Um preço do histórico (função `historico_preco_produto`): o preço principal do dia. */
data class PontoPreco(val data: LocalDate, val centavos: Long, val precoApp: Boolean = false)

/**
 * Série do gráfico "Variação do preço" (mesmo produto em todas as lojas; com várias lojas no dia, a média). Funções puras: o app só desenha.
 * Ordem por data, um ponto por dia (o último lançado no dia), no máximo [MAXIMO_PONTOS].
 */
class SerieHistorico private constructor(val pontos: List<PontoPreco>) {

    /** Gráfico só com 2 ou mais preços; antes disso, o cartão diz "Um único preço registrado até agora". */
    val temGrafico: Boolean get() = pontos.size >= 2

    val menor: Long? get() = pontos.minOfOrNull { it.centavos }
    val maior: Long? get() = pontos.maxOfOrNull { it.centavos }

    /** Variação do primeiro para o último ponto, em %; null com menos de 2 pontos. */
    val variacaoPercentual: Double?
        get() {
            if (pontos.size < 2) return null
            val primeiro = pontos.first().centavos
            if (primeiro <= 0) return null
            return (pontos.last().centavos - primeiro) * 100.0 / primeiro
        }

    /** Dias entre o primeiro e o último ponto. */
    val dias: Long get() = if (pontos.size < 2) 0 else ChronoUnit.DAYS.between(pontos.first().data, pontos.last().data)

    /** "em 4 semanas", "em 6 dias", "em 1 dia". */
    val periodoTexto: String
        get() = when {
            dias < 14 -> if (dias == 1L) "em 1 dia" else "em $dias dias"
            else -> (dias / 7.0).roundToLong().let { if (it == 1L) "em 1 semana" else "em $it semanas" }
        }

    /** "▼ 7,7% em 4 semanas" (queda), "▲ 3,2% em 6 dias" (alta), "0,0% em 2 semanas" (igual). */
    val variacaoTexto: String?
        get() {
            val v = variacaoPercentual ?: return null
            val seta = when {
                tendencia == Tendencia.QUEDA -> "▼ "
                tendencia == Tendencia.ALTA -> "▲ "
                else -> ""
            }
            return "$seta${percentual(abs(v))} $periodoTexto"
        }

    enum class Tendencia { QUEDA, ALTA, ESTAVEL }

    /** Verde se caiu, vermelho se subiu. Arredonda como o texto (0,04% conta como estável). */
    val tendencia: Tendencia?
        get() {
            val v = variacaoPercentual ?: return null
            val arredondado = Math.round(v * 10) / 10.0
            return when {
                arredondado < 0 -> Tendencia.QUEDA
                arredondado > 0 -> Tendencia.ALTA
                else -> Tendencia.ESTAVEL
            }
        }

    /** 2 ou 3 valores para o eixo do preço (menor, meio e maior; só um se forem todos iguais). */
    val valoresEixo: List<Long>
        get() {
            val min = menor ?: return emptyList()
            val max = maior ?: return emptyList()
            if (min == max) return listOf(min)
            val meio = (min + max) / 2
            return if (meio == min || meio == max) listOf(min, max) else listOf(min, meio, max)
        }

    /** Leitor de tela: "Preço de R$ 6,49 em 05/10 para R$ 5,99 em 01/11, queda de 7,7%." */
    val descricao: String
        get() {
            if (pontos.isEmpty()) return "Sem histórico de preço deste produto."
            if (!temGrafico) return "Um único preço registrado até agora: ${Dinheiro.formatar(pontos.first().centavos)} " +
                "em ${diaMes(pontos.first().data)}."
            val a = pontos.first()
            val b = pontos.last()
            val v = variacaoPercentual ?: 0.0
            val mudanca = when (tendencia) {
                Tendencia.QUEDA -> "queda de ${percentual(abs(v))}"
                Tendencia.ALTA -> "alta de ${percentual(abs(v))}"
                else -> "sem variação"
            }
            return "Preço de ${Dinheiro.formatar(a.centavos)} em ${diaMes(a.data)} para ${Dinheiro.formatar(b.centavos)} " +
                "em ${diaMes(b.data)}, $mudanca."
        }

    companion object {
        const val MAXIMO_PONTOS = 30
        private val formatoDiaMes = DateTimeFormatter.ofPattern("dd/MM")

        /** "05/10" (eixo de datas). */
        fun diaMes(data: LocalDate): String = data.format(formatoDiaMes)

        /** "7,7%" (uma casa, vírgula). */
        fun percentual(v: Double): String = String.format(Locale.forLanguageTag("pt-BR"), "%.1f%%", v)

        /**
         * Monta a série a partir dos lançamentos em ordem de registro: um ponto por dia (o último
         * lançado no dia), em ordem de data, com os [MAXIMO_PONTOS] mais recentes.
         */
        fun montar(lancamentos: List<PontoPreco>): SerieHistorico {
            val porDia = LinkedHashMap<LocalDate, PontoPreco>()
            lancamentos.forEach { porDia[it.data] = it }
            return SerieHistorico(porDia.values.sortedBy { it.data }.takeLast(MAXIMO_PONTOS))
        }
    }
}
