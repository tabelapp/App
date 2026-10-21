package br.com.tabelapp.ui.busca

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.tabelapp.R
import br.com.tabelapp.core.Banner
import br.com.tabelapp.ui.comum.ImagemRemota
import br.com.tabelapp.ui.tema.ContornoMarca
import br.com.tabelapp.ui.tema.CoresTabelapp
import kotlinx.coroutines.delay

/**
 * Espaço do banner patrocinado, do mesmo tamanho de um card de preço ([ALTURA_CARTAO]).
 * A busca tem dois: no topo e no lugar do 5º card. Com vários banners, alterna a cada
 * 6 segundos. Sem nenhum, mostra uma mensagem do próprio Tabelapp (sem convite de
 * venda: o app da Play não tem superfície de venda) — o espaço nunca fica vazio.
 */
@Composable
fun BannerPatrocinado(
    banners: List<Banner>,
    aoExibir: (Banner) -> Unit,
    /** Toque em um banner (o espaço do Tabelapp não reage ao toque). */
    aoTocar: (Banner) -> Unit,
    /** Bandeirinha no canto: "Denunciar este banner" (política de conteúdo da Google Play). */
    aoDenunciar: (Banner) -> Unit,
    modifier: Modifier = Modifier,
    /** Margem lateral (0 quando já está dentro da lista de cards, que tem a própria margem). */
    margemLateral: Dp = 12.dp,
) {
    var indice by remember(banners) { mutableIntStateOf(0) }
    LaunchedEffect(banners) {
        while (banners.size > 1) {
            delay(6_000)
            indice = (indice + 1) % banners.size
        }
    }
    val atual = banners.getOrNull(indice)
    LaunchedEffect(atual?.id) { atual?.let(aoExibir) }

    Box(
        modifier
            .padding(horizontal = margemLateral, vertical = 4.dp)
            .fillMaxWidth()
            .height(ALTURA_CARTAO)
            .clip(RoundedCornerShape(12.dp))
            // Contorno amarelo da marca (banner ou espaço do Tabelapp), sem preenchimento amarelo.
            .border(ContornoMarca, RoundedCornerShape(12.dp))
            .clickable(enabled = atual != null) { atual?.let(aoTocar) },
    ) {
        Crossfade(targetState = atual, label = "banner") { b ->
            val url = b?.imagemUrl
            when {
                b == null -> EspacoTabelapp()
                url != null -> ImagemRemota(
                    url = url, descricao = b.titulo, modifier = Modifier.fillMaxSize(),
                    semImagem = { BannerTexto(b) },
                )
                else -> BannerTexto(b)
            }
        }
        if (atual != null) {
            Surface(
                color = CoresTabelapp.FaixaSobreImagem,
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "Patrocinado",
                    color = CoresTabelapp.TextoSobreImagem,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = { aoDenunciar(atual) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Surface(color = CoresTabelapp.FaixaSobreImagem, shape = CircleShape) {
                    Icon(
                        Icons.Outlined.Flag, contentDescription = stringResource(R.string.banner_denunciar),
                        tint = CoresTabelapp.TextoSobreImagem, modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerTexto(b: Banner) {
    Column(
        Modifier.fillMaxSize().background(CoresTabelapp.Cartao).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
    ) {
        Text(b.pdvNome, color = CoresTabelapp.Destaque, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(b.titulo, color = CoresTabelapp.TextoPrincipal, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        b.descricao?.let {
            Text(it, color = CoresTabelapp.TextoSecundario, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Espaço sem banner: mensagem do Tabelapp, sem convite de compra nem toque. */
@Composable
private fun EspacoTabelapp() {
    Column(
        Modifier.fillMaxSize().background(CoresTabelapp.Cartao).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
    ) {
        Text("Tabelapp", color = CoresTabelapp.TextoPrincipal, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Quem pesquisa economiza. Compare antes de comprar.",
            color = CoresTabelapp.TextoSecundario, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
