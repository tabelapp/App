package br.com.tabelapp.ui.busca

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import br.com.tabelapp.BuildConfig
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.MapaPrecos
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.core.PinoMapa
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.dados.Geocodificador
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.PretoLupa
import br.com.tabelapp.ui.tema.VermelhoTabelapp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * "Ver no mapa": os pontos de venda do resultado da busca como pinos no Google
 * Maps, cada um com o preço (o menor daquele local; "+2" = mais itens ali).
 * O pino vermelho é o mais barato do resultado. Tocar no pino mostra os itens,
 * "Como chegar" e o WhatsApp do estabelecimento.
 */
@Composable
fun MapaDePrecos(
    cotacoes: List<Cotacao>,
    termo: String?,
    geocodificador: Geocodificador,
    mostrarMinhaPosicao: Boolean,
    aoFechar: () -> Unit,
) {
    val pinos = remember(cotacoes) { MapaPrecos.pinos(cotacoes) }
    val posicoes = remember(cotacoes) { mutableStateMapOf<String, PontoGeo>() }
    var localizando by remember(cotacoes) { mutableStateOf(true) }
    var selecionado by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pinos) {
        pinos.forEach { p ->
            val ponto = p.local ?: p.enderecoParaBusca?.let { geocodificador.localizar(it) }
            if (ponto != null) posicoes[p.chave] = ponto
        }
        localizando = false
    }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ver no mapa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            termo?.let { "\"$it\" · ${pinos.size} local(is)" } ?: "Últimos preços · ${pinos.size} local(is)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = "Fechar o mapa") }
                }
                if (BuildConfig.MAPS_API_KEY.isBlank()) {
                    SemMapa(pinos)
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Mapa(pinos, posicoes, localizando, mostrarMinhaPosicao, selecionado) { selecionado = it }
                        Aviso(
                            when {
                                localizando -> "Localizando os endereços… ${posicoes.size} de ${pinos.size}"
                                posicoes.isEmpty() && pinos.isNotEmpty() -> "Não foi possível localizar estes endereços no mapa."
                                posicoes.size < pinos.size ->
                                    "${pinos.size - posicoes.size} local(is) sem endereço localizado não aparecem no mapa."
                                else -> null
                            },
                            localizando,
                            Modifier.align(Alignment.TopCenter),
                        )
                        pinos.firstOrNull { it.chave == selecionado }?.let { p ->
                            DetalhePino(p, posicoes[p.chave], Modifier.align(Alignment.BottomCenter)) { selecionado = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Mapa(
    pinos: List<PinoMapa>,
    posicoes: Map<String, PontoGeo>,
    localizando: Boolean,
    mostrarMinhaPosicao: Boolean,
    selecionado: String?,
    aoSelecionar: (String?) -> Unit,
) {
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(Geo.PETROPOLIS_CENTRO.latLng(), 12f)
    }
    var carregado by remember { mutableStateOf(false) }

    // Enquadra todos os pinos quando o mapa e os endereços estiverem prontos.
    LaunchedEffect(carregado, localizando) {
        if (!carregado || localizando || posicoes.isEmpty()) return@LaunchedEffect
        val pontos = posicoes.values.map { it.latLng() }
        runCatching {
            if (pontos.size == 1) {
                camera.animate(CameraUpdateFactory.newLatLngZoom(pontos.first(), 15f))
            } else {
                val limites = LatLngBounds.builder().apply { pontos.forEach { include(it) } }.build()
                camera.animate(CameraUpdateFactory.newLatLngBounds(limites, 140))
            }
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = mostrarMinhaPosicao),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = { carregado = true },
        onMapClick = { aoSelecionar(null) },
    ) {
        pinos.forEach { p ->
            val ponto = posicoes[p.chave] ?: return@forEach
            key(p.chave) {
                val estado = remember { MarkerState(position = ponto.latLng()) }
                val ativo = p.chave == selecionado
                MarkerComposable(
                    p.chave, p.rotulo, p.maisBarato, ativo,
                    state = estado,
                    title = p.pdvNome,
                    zIndex = if (ativo) 2f else if (p.maisBarato) 1f else 0f,
                    onClick = {
                        aoSelecionar(p.chave)
                        true
                    },
                ) {
                    EtiquetaPreco(p.rotulo, maisBarato = p.maisBarato, ativo = ativo)
                }
            }
        }
    }
}

/** O pino: balão com o preço e uma pontinha embaixo. */
@Composable
private fun EtiquetaPreco(texto: String, maisBarato: Boolean, ativo: Boolean) {
    val fundo = when {
        ativo -> PretoLupa
        maisBarato -> VermelhoTabelapp
        else -> Color.White
    }
    val letra = if (ativo || maisBarato) Color.White else PretoLupa
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = fundo,
            border = BorderStroke(1.5.dp, if (maisBarato || ativo) fundo else PretoLupa),
        ) {
            Text(
                texto,
                color = letra,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(
            Modifier.size(width = 12.dp, height = 7.dp).background(
                if (maisBarato || ativo) fundo else PretoLupa,
                GenericShape { tamanho, _ ->
                    moveTo(0f, 0f)
                    lineTo(tamanho.width, 0f)
                    lineTo(tamanho.width / 2, tamanho.height)
                    close()
                },
            )
        )
    }
}

@Composable
private fun Aviso(texto: String?, carregando: Boolean, modifier: Modifier) {
    if (texto == null) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp,
        modifier = modifier.padding(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (carregando) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(texto, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetalhePino(p: PinoMapa, ponto: PontoGeo?, modifier: Modifier, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val telefone = p.cotacoes.firstNotNullOfOrNull { it.telefone }
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(p.pdvNome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    p.endereco?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = aoFechar, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            p.cotacoes.take(5).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.produto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(Dinheiro.formatar(c.precoCentavos), fontWeight = FontWeight.Bold,
                        color = if (c.precoCentavos == p.menorPreco && p.maisBarato) VermelhoTabelapp else Color.Unspecified)
                }
            }
            if (p.cotacoes.size > 5) {
                Text("… e mais ${p.cotacoes.size - 5} item(ns)", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = {
                        val destino = ponto?.let { "${it.latitude},${it.longitude}" } ?: p.endereco.orEmpty()
                        abrir(contexto, Intent(Intent.ACTION_VIEW,
                            "https://www.google.com/maps/dir/?api=1&destination=${android.net.Uri.encode(destino)}".toUri()))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Como chegar")
                }
                if (telefone != null) {
                    OutlinedButton(
                        onClick = { WhatsApp.abrirConversa(contexto, telefone, Mensagens.interesseNaOferta(p.cotacoes.first())) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("WhatsApp")
                    }
                }
            }
        }
    }
}

/** Sem a chave do Google Maps no build: lista os locais, cada um abre no app do Google Maps. */
@Composable
private fun SemMapa(pinos: List<PinoMapa>) {
    val contexto = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "O mapa ainda não foi ativado nesta versão do app. Toque num local para abri-lo no Google Maps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(pinos, key = { it.chave }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = p.endereco != null) { abrirNoMapa(contexto, p.cotacoes.first()) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.pdvNome, fontWeight = FontWeight.SemiBold)
                    Text(p.endereco ?: "Sem endereço", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(p.rotulo, fontWeight = FontWeight.Bold, color = if (p.maisBarato) VermelhoTabelapp else Color.Unspecified)
            }
        }
    }
}

private fun PontoGeo.latLng() = LatLng(latitude, longitude)
