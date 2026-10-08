package br.com.tabelapp.ui.busca

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import br.com.tabelapp.core.Validade

/**
 * "Card de preço": como um produto e preço aparecem na busca (briefing, seção 2).
 * Hierarquia pedida pelo fundador: produto e preço em fonte grande; ponto de
 * venda (link), endereço e OBS menores; o resto (validade, contato,
 * distância) menor ainda. O mais barato da busca ganha destaque.
 */
@Composable
fun CartaoCotacao(
    cotacao: Cotacao,
    maisBarato: Boolean,
    posicao: PontoGeo?,
    aoAbrirPdv: () -> Unit,
    aoCompartilhar: () -> Unit,
) {
    val cores = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (maisBarato) cores.primaryContainer else cores.surfaceContainerLow,
        ),
        border = if (maisBarato) BorderStroke(2.dp, cores.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 1. Produto e preço — o principal.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    cotacao.produto,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Dinheiro.formatar(cotacao.precoCentavos),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = cores.primary,
                    )
                    if (maisBarato) {
                        Surface(color = cores.secondary, shape = MaterialTheme.shapes.small) {
                            Text(
                                "MAIS BARATO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = cores.onSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // 2. Ponto de venda (link), endereço e OBS.
            val nome = listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")
            if (cotacao.pdvCadastrado) {
                Text(
                    nome,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = cores.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = aoAbrirPdv),
                )
            } else {
                Text(nome, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                cotacao.endereco ?: "Endereço não informado",
                style = MaterialTheme.typography.bodySmall,
                color = cores.onSurfaceVariant,
            )
            Surface(color = cores.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    Obs.exibir(cotacao),
                    style = MaterialTheme.typography.bodySmall,
                    color = cores.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 3. Demais informações (menor ainda) + compartilhar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val distancia = Geo.distanciaKm(posicao, cotacao.local)?.let(Geo::formatarDistancia)
                Text(
                    listOfNotNull(Validade.exibir(cotacao), distancia, cotacao.telefone?.let { "Tel. $it" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = cores.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = aoCompartilhar) {
                    Icon(Icons.Default.Share, contentDescription = "Enviar este preço pelo WhatsApp", Modifier.size(20.dp))
                }
            }
        }
    }
}
