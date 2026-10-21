package br.com.tabelapp.ui.busca

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.tabelapp.R
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Destaque
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.Obs
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.core.Validade
import br.com.tabelapp.ui.tema.ContornoMarca
import br.com.tabelapp.ui.tema.CoresTabelapp

/** Altura fixa do card de preço (layout compacto da busca, pedido do fundador). */
val ALTURA_CARTAO = 124.dp

/** Fundo alternado dos cards (um tom um pouco mais claro que o outro). */
private fun fundoAlternado(claro: Boolean): Color = if (claro) CoresTabelapp.CartaoAlternado else CoresTabelapp.Cartao

/**
 * "Card de preço": como um produto e preço aparecem na busca (briefing, seção 2).
 * Layout compacto com altura mínima [ALTURA_CARTAO], 4 linhas distribuídas:
 *  1. produto (até 2 linhas; nome completo no ⓘ) e preço em branco, alinhado ao topo;
 *  2. ponto de venda (link, se cadastrado);
 *  3. endereço (abre o mapa);
 *  4. "mais barato", OBS/NF, data e distância — WhatsApp e compartilhar no canto.
 * [alternado] clareia um pouco o fundo de um card sim, outro não. O mais barato da busca
 * tem o mesmo fundo dos outros e só ganha o contorno amarelo da marca ([ContornoMarca]).
 */
@Composable
fun CartaoCotacao(
    cotacao: Cotacao,
    /** Selo "Menor preço lançado", "Parte de combo" ou "Confira na loja" (null = nenhum). */
    destaque: Destaque?,
    posicao: PontoGeo?,
    aoAbrirPdv: () -> Unit,
    aoCompartilhar: () -> Unit,
    aoAbrirMapa: () -> Unit,
    aoChamarNoWhatsApp: () -> Unit,
    /** Toque no ⓘ: "Sobre este preço" (origem do preço, combos, denúncia). */
    aoSobrePreco: () -> Unit,
    alternado: Boolean = false,
) {
    val cores = MaterialTheme.colorScheme
    val maisBarato = destaque == Destaque.MENOR_PRECO
    val pequeno = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = fundoAlternado(alternado),
            contentColor = CoresTabelapp.TextoPrincipal,
        ),
        border = if (maisBarato) ContornoMarca else null,
        // Altura mínima: nome em 1 linha = exatamente ALTURA_CARTAO; nome em 2 linhas cresce só o necessário.
        modifier = Modifier.fillMaxWidth().heightIn(min = ALTURA_CARTAO),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = ALTURA_CARTAO)
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 1. Produto e preço — o principal.
            // Preço alinhado ao topo; os 2 dp no nome mantêm a 1ª linha onde ficava quando era centralizada.
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 10.dp)) {
                Text(
                    cotacao.produto,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 2.dp, end = 8.dp),
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
                    color = CoresTabelapp.Preco,
                    maxLines = 1,
                )
            }

            // 2. Ponto de venda (link quando cadastrado) e, no fim da linha, o destaque do card.
            val nome = listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — ")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                Text(
                    nome,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (cotacao.pdvCadastrado) cores.primary else Color.Unspecified,
                    textDecoration = if (cotacao.pdvCadastrado) TextDecoration.Underline else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                        .then(if (cotacao.pdvCadastrado) Modifier.clickable(onClick = aoAbrirPdv) else Modifier),
                )
                if (destaque != null) Spacer(Modifier.width(6.dp))
                val chip = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
                when (destaque) {
                    // Selo do mais barato: só o texto em amarelo, pequeno, sem fundo nem borda.
                    Destaque.MENOR_PRECO ->
                        Selo(stringResource(R.string.selo_menor_preco), Color.Transparent, CoresTabelapp.AmareloMarca, chip,
                            negrito = true, largura = 170.dp)
                    Destaque.PARTE_DE_COMBO ->
                        Selo(stringResource(R.string.chip_parte_de_combo), cores.surfaceVariant, cores.onSurfaceVariant, chip, largura = 170.dp)
                    Destaque.CONFIRA_NA_LOJA ->
                        Selo(stringResource(R.string.chip_confira_na_loja), cores.surfaceVariant, cores.onSurfaceVariant, chip, largura = 170.dp)
                    null -> Unit
                }
            }

            // 3. Endereço: toque abre o mapa do celular para chegar até a oferta.
            val endereco = cotacao.endereco?.takeIf { it.isNotBlank() }
            Text(
                endereco?.let { "📍 $it" } ?: "Endereço não informado",
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                color = cores.onSurfaceVariant,
                textDecoration = if (endereco != null) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp)
                    .then(if (endereco != null) Modifier.clickable(onClick = aoAbrirMapa) else Modifier),
            )

            // 4. Selos, data e distância; WhatsApp e compartilhar no canto inferior direito (44 dp de toque).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
                Selo(Obs.exibir(cotacao), cores.tertiaryContainer, cores.onTertiaryContainer, pequeno)
                // ⓘ ao lado do chip de origem: "Sobre este preço".
                IconButton(onClick = aoSobrePreco, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.sobre_preco_icone),
                        tint = cores.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
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
                            tint = CoresTabelapp.WhatsApp, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = aoCompartilhar, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Enviar este preço pelo WhatsApp", Modifier.size(20.dp),
                        tint = CoresTabelapp.IconeNeutro)
                }
            }
        }
    }
}

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
