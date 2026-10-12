package br.com.tabelapp.ui.busca

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.Obs
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.core.Validade
import br.com.tabelapp.ui.tema.VermelhoTabelapp

/** Altura fixa do card de preço (layout compacto da busca, pedido do fundador). */
val ALTURA_CARTAO = 124.dp

/** Fundo alternado dos cards (um mais claro, outro mais escuro), no tema claro e no escuro. */
@Composable
private fun fundoAlternado(escuro: Boolean): Color {
    val cores = MaterialTheme.colorScheme
    return if (cores.surface.luminance() > 0.5f) {
        if (escuro) Color(0xFFF1ECDC) else Color.White
    } else {
        if (escuro) cores.surfaceContainerHigh else cores.surfaceContainerLow
    }
}

/**
 * "Card de preço": como um produto e preço aparecem na busca (briefing, seção 2).
 * Layout compacto de altura fixa ([ALTURA_CARTAO]), 4 linhas distribuídas:
 *  1. produto (1 linha) e preço em vermelho;
 *  2. ponto de venda (link, se cadastrado);
 *  3. endereço (abre o mapa);
 *  4. "mais barato", OBS/NF, data e distância — WhatsApp e compartilhar no canto.
 * [alternado] escurece o fundo de um card sim, outro não.
 */
@Composable
fun CartaoCotacao(
    cotacao: Cotacao,
    maisBarato: Boolean,
    posicao: PontoGeo?,
    aoAbrirPdv: () -> Unit,
    aoCompartilhar: () -> Unit,
    aoAbrirMapa: () -> Unit,
    aoChamarNoWhatsApp: () -> Unit,
    alternado: Boolean = false,
) {
    val cores = MaterialTheme.colorScheme
    val pequeno = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (maisBarato) cores.primaryContainer else fundoAlternado(alternado),
        ),
        border = if (maisBarato) BorderStroke(2.dp, cores.primary) else null,
        modifier = Modifier.fillMaxWidth().height(ALTURA_CARTAO),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 1. Produto e preço — o principal.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                Text(
                    cotacao.produto,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                // "A partir de" e a unidade em letra menor, na mesma linha: o card não cresce.
                Text(
                    buildAnnotatedString {
                        val pequeno = SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        if (cotacao.aPartirDe) withStyle(pequeno) { append(PrecoExibido.A_PARTIR_DE.lowercase() + " ") }
                        append(Dinheiro.formatar(cotacao.precoCentavos))
                        withStyle(pequeno) { append(PrecoExibido.sufixo(cotacao.unidade)) }
                    },
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = VermelhoTabelapp,
                    maxLines = 1,
                )
            }

            // 2. Ponto de venda (link quando cadastrado).
            val nome = listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")
            Text(
                nome,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (cotacao.pdvCadastrado) cores.primary else Color.Unspecified,
                textDecoration = if (cotacao.pdvCadastrado) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp)
                    .then(if (cotacao.pdvCadastrado) Modifier.clickable(onClick = aoAbrirPdv) else Modifier),
            )

            // 3. Endereço: toque abre o mapa do celular para chegar até a oferta.
            val endereco = cotacao.endereco?.takeIf { it.isNotBlank() }
            Text(
                endereco?.let { "📍 $it" } ?: "Endereço não informado",
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                color = if (endereco != null) cores.primary else cores.onSurfaceVariant,
                textDecoration = if (endereco != null) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp)
                    .then(if (endereco != null) Modifier.clickable(onClick = aoAbrirMapa) else Modifier),
            )

            // 4. Selos, data e distância; WhatsApp e compartilhar no canto inferior direito (44 dp de toque).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
                if (maisBarato) {
                    Selo("MAIS BARATO", cores.secondary, cores.onSecondary, pequeno, negrito = true)
                    Spacer(Modifier.width(4.dp))
                }
                Selo(Obs.exibir(cotacao), cores.tertiaryContainer, cores.onTertiaryContainer, pequeno)
                Spacer(Modifier.width(6.dp))
                val distancia = Geo.distanciaKm(posicao, cotacao.local)?.let(Geo::formatarDistancia)
                Text(
                    listOfNotNull(Validade.exibir(cotacao), distancia).joinToString(" · "),
                    style = pequeno,
                    color = cores.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Contato do comércio: abre o WhatsApp dele com uma mensagem sobre a oferta.
                if (!cotacao.telefone.isNullOrBlank()) {
                    IconButton(onClick = aoChamarNoWhatsApp, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chamar no WhatsApp",
                            tint = Color(0xFF1FA855), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = aoCompartilhar, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Enviar este preço pelo WhatsApp", Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Selo(texto: String, fundo: Color, letra: Color, estilo: TextStyle, negrito: Boolean = false) {
    Surface(color = fundo, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 140.dp)) {
        Text(
            texto,
            style = estilo,
            fontWeight = if (negrito) FontWeight.Bold else FontWeight.Medium,
            color = letra,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
