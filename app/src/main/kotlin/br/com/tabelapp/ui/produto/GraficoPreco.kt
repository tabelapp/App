package br.com.tabelapp.ui.produto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.tabelapp.R
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.SerieHistorico
import br.com.tabelapp.ui.tema.CoresTabelapp
import java.time.temporal.ChronoUnit

/** Verde da queda e vermelho da alta (contraste ≥ 4,5:1 sobre o card escuro). */
private val Queda = Color(0xFF5BD68A)
private val Alta = Color(0xFFFF8A80)

/**
 * Cartão "Variação do preço" (fim da página do produto): gráfico de linha do produto em todas as
 * lojas (dia com várias lojas = preço médio). Carrega à parte e nunca trava a página: carregando, erro discreto ou o gráfico.
 */
@Composable
fun CartaoVariacaoPreco(serie: SerieHistorico?, carregando: Boolean, erro: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.grafico_titulo), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.grafico_todas_as_lojas), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                carregando -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                erro || serie == null -> Text(stringResource(R.string.grafico_erro), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                !serie.temGrafico -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.grafico_um_preco), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Canvas(Modifier.size(12.dp)) { drawCircle(CoresTabelapp.AmareloMarca) }
                    }
                    Text(stringResource(R.string.grafico_aparece), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Grafico(serie, Modifier.fillMaxWidth().height(170.dp).clearAndSetSemantics { contentDescription = serie.descricao })
                    Rodape(serie)
                }
            }
        }
    }
}

@Composable
private fun Rodape(serie: SerieHistorico) {
    val fraco = MaterialTheme.colorScheme.onSurfaceVariant
    val forte = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = fraco)) { append("Menor: ") }
            withStyle(SpanStyle(color = forte, fontWeight = FontWeight.Bold)) { append(Dinheiro.formatar(serie.menor ?: 0L)) }
        }, style = MaterialTheme.typography.bodySmall)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = fraco)) { append("Maior: ") }
            withStyle(SpanStyle(color = forte, fontWeight = FontWeight.Bold)) { append(Dinheiro.formatar(serie.maior ?: 0L)) }
        }, style = MaterialTheme.typography.bodySmall)
        serie.variacaoTexto?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                color = when (serie.tendencia) {
                    SerieHistorico.Tendencia.QUEDA -> Queda
                    SerieHistorico.Tendencia.ALTA -> Alta
                    else -> fraco
                },
            )
        }
    }
}

/** Linha amarela com pontos, área suave, 2–3 valores no eixo do preço e datas (dd/MM) embaixo. */
@Composable
private fun Grafico(serie: SerieHistorico, modifier: Modifier) {
    val medidor = rememberTextMeasurer()
    val amarelo = CoresTabelapp.AmareloMarca
    val grade = MaterialTheme.colorScheme.outlineVariant
    val rotulo = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rotuloUltimo = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = amarelo)
    val pontos = serie.pontos
    val min = serie.menor ?: return
    val max = serie.maior ?: return
    val primeiroDia = pontos.first().data
    val totalDias = ChronoUnit.DAYS.between(primeiroDia, pontos.last().data).coerceAtLeast(1)

    Canvas(modifier) {
        val esquerda = 40.dp.toPx()
        val direita = size.width - 12.dp.toPx()
        val topo = 12.dp.toPx()
        val base = size.height - 22.dp.toPx()
        // Folga para a linha não encostar nas bordas quando min == max.
        val faixa = (max - min).takeIf { it > 0 } ?: 1L
        fun x(i: Int): Float = esquerda + (direita - esquerda) *
            (ChronoUnit.DAYS.between(primeiroDia, pontos[i].data).toFloat() / totalDias)
        fun y(c: Long): Float = if (max == min) (topo + base) / 2 else base - (base - topo) * ((c - min).toFloat() / faixa)

        // Eixo do preço: linhas de grade e valores.
        serie.valoresEixo.forEach { v ->
            val yy = y(v)
            drawLine(grade, Offset(esquerda, yy), Offset(direita, yy), strokeWidth = 1f)
            val t = medidor.measure(Dinheiro.formatar(v).removePrefix("R$ ").trim(), rotulo)
            drawText(t, topLeft = Offset(esquerda - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }

        // Área e linha.
        val linha = Path()
        pontos.forEachIndexed { i, p -> if (i == 0) linha.moveTo(x(i), y(p.centavos)) else linha.lineTo(x(i), y(p.centavos)) }
        val area = Path().apply {
            addPath(linha)
            lineTo(x(pontos.lastIndex), base)
            lineTo(x(0), base)
            close()
        }
        drawPath(area, amarelo.copy(alpha = 0.14f))
        drawPath(linha, amarelo, style = Stroke(width = 2.5.dp.toPx()))

        // Pontos (o último maior, com o valor).
        pontos.forEachIndexed { i, p ->
            val c = Offset(x(i), y(p.centavos))
            if (i == pontos.lastIndex) {
                drawCircle(amarelo, radius = 6.dp.toPx(), center = c)
                val t = medidor.measure(Dinheiro.formatar(p.centavos), rotuloUltimo)
                val acima = c.y - t.size.height - 8.dp.toPx() > 0
                drawText(t, topLeft = Offset((c.x - t.size.width).coerceAtLeast(esquerda),
                    if (acima) c.y - t.size.height - 8.dp.toPx() else c.y + 8.dp.toPx()))
            } else {
                drawCircle(Color(0xFF16171C), radius = 3.5.dp.toPx(), center = c)
                drawCircle(amarelo, radius = 3.5.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
            }
        }

        // Datas: até 5 rótulos espalhados (sempre o primeiro e o último).
        val indices = if (pontos.size <= 5) pontos.indices.toList()
            else (0 until 5).map { (it * pontos.lastIndex) / 4 }.distinct()
        indices.forEach { i ->
            val t = medidor.measure(SerieHistorico.diaMes(pontos[i].data), rotulo)
            val cx = (x(i) - t.size.width / 2).coerceAtMost(direita - t.size.width).coerceAtLeast(0f)
            drawText(t, topLeft = Offset(cx, base + 6.dp.toPx()))
        }
    }
}
