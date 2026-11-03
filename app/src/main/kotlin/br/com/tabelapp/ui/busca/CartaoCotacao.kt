package br.com.tabelapp.ui.busca

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.tabelapp.R
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Destaque
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.ui.tema.ContornoMarca
import br.com.tabelapp.ui.tema.CoresTabelapp

/**
 * Altura dos banners da busca e do bloco de busca (era a altura do card de preço; desde a 1.0.3
 * o card é menor e cresce só com o conteúdo).
 */
val ALTURA_CARTAO = 124.dp

/** Fundo alternado dos cards (um tom um pouco mais claro que o outro). */
private fun fundoAlternado(claro: Boolean): Color = if (claro) CoresTabelapp.CartaoAlternado else CoresTabelapp.Cartao

/**
 * "Card de preço" (1.0.3, card menor): nome do produto em negrito, a loja (e a distância) em azul
 * claro e menor, e o preço em amarelo e negrito com a unidade, o selo "no app" e o preço normal
 * riscado quando houver. O card inteiro abre a página do produto (endereço, validade, origem,
 * WhatsApp, "Sobre este preço", gráfico e compartilhar ficam lá). O mais barato da busca mantém
 * o contorno amarelo da marca ([ContornoMarca]); [alternado] clareia o fundo de um card sim, outro não.
 */
@Composable
fun CartaoCotacao(
    cotacao: Cotacao,
    /** Selo "Menor preço lançado", "Parte de combo" ou "Confira na loja" (null = nenhum). */
    destaque: Destaque?,
    posicao: PontoGeo?,
    /** Toque no card: abre a página do produto. */
    aoAbrir: () -> Unit,
    alternado: Boolean = false,
) {
    val cores = MaterialTheme.colorScheme
    val maisBarato = destaque == Destaque.MENOR_PRECO
    val distancia = Geo.distanciaKm(posicao, cotacao.local)?.let(Geo::formatarDistancia)
    val rotuloAbrir = stringResource(R.string.produto_abrir)
    val descricao = PrecoExibido.descricaoCartao(cotacao)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = fundoAlternado(alternado),
            contentColor = CoresTabelapp.TextoPrincipal,
        ),
        border = if (maisBarato) ContornoMarca else null,
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = rotuloAbrir, onClick = aoAbrir)
            .clearAndSetSemantics {
                contentDescription = descricao
                role = Role.Button
                onClick(label = rotuloAbrir) { aoAbrir(); true }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                cotacao.produto,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(cotacao.pdvNome, distancia).joinToString(" · "),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = CoresTabelapp.NomeLoja,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val chip = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
                when (destaque) {
                    // Selo do mais barato: só o texto em amarelo, pequeno, sem fundo nem borda.
                    Destaque.MENOR_PRECO -> {
                        Spacer(Modifier.width(6.dp))
                        Selo(stringResource(R.string.selo_menor_preco), Color.Transparent, CoresTabelapp.AmareloMarca, chip,
                            negrito = true, largura = 170.dp)
                    }
                    Destaque.PARTE_DE_COMBO -> {
                        Spacer(Modifier.width(6.dp))
                        Selo(stringResource(R.string.chip_parte_de_combo), cores.surfaceVariant, cores.onSurfaceVariant, chip, largura = 170.dp)
                    }
                    Destaque.CONFIRA_NA_LOJA -> {
                        Spacer(Modifier.width(6.dp))
                        Selo(stringResource(R.string.chip_confira_na_loja), cores.surfaceVariant, cores.onSurfaceVariant, chip, largura = 170.dp)
                    }
                    null -> Unit
                }
            }
            PrecoComSelo(cotacao, tamanho = 20.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Preço principal em amarelo e negrito, com "a partir de" e a unidade em letra menor; com preço no
 * app, o selo "no app" ao lado e, se existir e for maior, o preço normal riscado.
 */
@Composable
fun PrecoComSelo(cotacao: Cotacao, tamanho: TextUnit, modifier: Modifier = Modifier) {
    val cores = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            buildAnnotatedString {
                val pequeno = SpanStyle(fontSize = tamanho * 0.6f, fontWeight = FontWeight.Medium)
                if (cotacao.aPartirDe) withStyle(pequeno) { append(PrecoExibido.A_PARTIR_DE.lowercase() + " ") }
                append(Dinheiro.formatar(cotacao.precoCentavos))
                withStyle(pequeno) { append(PrecoExibido.sufixo(cotacao.unidade)) }
            },
            fontSize = tamanho,
            lineHeight = tamanho * 1.2f,
            fontWeight = FontWeight.Bold,
            color = CoresTabelapp.Preco,
            maxLines = 1,
        )
        if (cotacao.temPrecoApp) {
            Spacer(Modifier.width(8.dp))
            SeloNoApp()
        }
        cotacao.precoNormalRiscado?.let { normal ->
            Spacer(Modifier.width(8.dp))
            Text(
                PrecoExibido.textoNormal(normal, cotacao.unidade),
                fontSize = 13.sp,
                textDecoration = TextDecoration.LineThrough,
                color = cores.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Selo "no app": pequeno, contorno amarelo. */
@Composable
fun SeloNoApp() {
    Surface(color = Color.Transparent, shape = MaterialTheme.shapes.small, border = ContornoNeutroAmarelo) {
        Text(
            PrecoExibido.LEGENDA_APP,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = CoresTabelapp.AmareloMarca,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private val ContornoNeutroAmarelo = BorderStroke(1.dp, CoresTabelapp.AmareloMarca)

@Composable
private fun Selo(
    texto: String, fundo: Color, letra: Color, estilo: TextStyle, negrito: Boolean = false, largura: Dp = 140.dp,
) {
    Surface(color = fundo, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = largura)) {
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
