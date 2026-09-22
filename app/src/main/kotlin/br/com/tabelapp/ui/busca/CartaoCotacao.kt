package br.com.tabelapp.ui.busca

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.Obs
import br.com.tabelapp.core.PontoGeo
import java.time.format.DateTimeFormatter

private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Uma linha do resultado: produto, preço, PDV, endereço, validade, OBS e contato
 * (briefing, seção 2). PDV cadastrado aparece em negrito e clicável; não
 * cadastrado, como texto simples. O mais barato da busca ganha destaque.
 */
@Composable
fun CartaoCotacao(
    cotacao: Cotacao,
    maisBarato: Boolean,
    posicao: PontoGeo?,
    aoAbrirPdv: () -> Unit,
) {
    val cores = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (maisBarato) cores.primaryContainer else cores.surfaceContainerLow,
        ),
        border = if (maisBarato) BorderStroke(2.dp, cores.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    cotacao.produto,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Dinheiro.formatar(cotacao.precoCentavos),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cores.primary,
                    )
                    if (maisBarato) {
                        Surface(color = cores.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                "MAIS BARATO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = cores.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            val distancia = Geo.distanciaKm(posicao, cotacao.local)?.let { " · " + Geo.formatarDistancia(it) }.orEmpty()
            if (cotacao.pdvCadastrado) {
                Text(
                    (listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")) + distancia,
                    fontWeight = FontWeight.Bold,
                    color = cores.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = aoAbrirPdv),
                )
            } else {
                Text(cotacao.pdvNome + distancia)
            }

            cotacao.endereco?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cores.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cotacao.validade?.let { "Válido até ${it.format(formatoData)}" } ?: "Validade não informada",
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(color = cores.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        Obs.exibir(cotacao),
                        style = MaterialTheme.typography.labelSmall,
                        color = cores.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            cotacao.telefone?.let {
                Text("Contato: $it", style = MaterialTheme.typography.bodySmall, color = cores.onSurfaceVariant)
            }
        }
    }
}
