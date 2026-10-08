package br.com.tabelapp.ui.busca

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.tabelapp.core.Banner
import br.com.tabelapp.ui.comum.ImagemRemota
import br.com.tabelapp.ui.tema.AmareloTabelapp
import br.com.tabelapp.ui.tema.PretoLupa
import br.com.tabelapp.ui.tema.VermelhoTabelapp
import kotlinx.coroutines.delay

/** Altura do banner = cabeçalho (64 dp) + campo de busca com margens (72 dp). Pedido do fundador. */
val ALTURA_BANNER: Dp = 136.dp

/**
 * Espaço do banner patrocinado, entre o cabeçalho e o campo de busca.
 * Com vários banners, alterna a cada 6 segundos. Sem nenhum, mostra o
 * "Anuncie aqui" do próprio Tabelapp — o espaço nunca fica vazio.
 */
@Composable
fun BannerPatrocinado(banners: List<Banner>, aoExibir: (Banner) -> Unit, modifier: Modifier = Modifier) {
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
            .fillMaxWidth()
            .height(ALTURA_BANNER)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Crossfade(targetState = atual, label = "banner") { b ->
            val url = b?.imagemUrl
            when {
                b == null -> AnuncieAqui()
                url != null -> ImagemRemota(
                    url = url, descricao = b.titulo, modifier = Modifier.fillMaxSize(),
                    semImagem = { BannerTexto(b) },
                )
                else -> BannerTexto(b)
            }
        }
        if (atual != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "Patrocinado",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun BannerTexto(b: Banner) {
    Column(
        Modifier.fillMaxSize().background(VermelhoTabelapp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(b.pdvNome, color = AmareloTabelapp, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(b.titulo, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        b.descricao?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Espaço livre: convite para o comércio anunciar. */
@Composable
private fun AnuncieAqui() {
    Column(
        Modifier.fillMaxSize().background(AmareloTabelapp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text("Anuncie aqui", color = VermelhoTabelapp, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Seu comércio em destaque para quem está pesquisando preços em Petrópolis.",
            color = PretoLupa, style = MaterialTheme.typography.bodyMedium,
        )
        Text("Cadastre seu estabelecimento no Tabelapp.", color = PretoLupa, style = MaterialTheme.typography.labelSmall)
    }
}
