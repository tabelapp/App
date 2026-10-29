package br.com.tabelapp.ui.loja

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.R
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.CompartilharLoja
import br.com.tabelapp.core.ItemLoja
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.core.PaginaLoja
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.ui.busca.BannerPatrocinado
import br.com.tabelapp.ui.busca.FolhaDenunciarBanner
import br.com.tabelapp.ui.busca.abrir
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.CoresTabelapp
import br.com.tabelapp.ui.tema.coresBarraTopo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoLoja(
    val carregando: Boolean = true,
    /** null depois de carregar = loja indisponível (desativada, pendente ou suspensa). */
    val loja: PaginaLoja? = null,
    val banners: List<Banner> = emptyList(),
    val itens: List<ItemLoja> = emptyList(),
    val busca: String = "",
    val buscando: Boolean = false,
    val erro: String? = null,
)

/** "Página da loja": dados públicos, banners e a tabela oficial (só itens ativos e válidos). */
class LojaViewModel(private val repositorio: CotacoesRepositorio, private val lojaId: String) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoLoja())
    val estado: StateFlow<EstadoLoja> = _estado.asStateFlow()
    private val bannersVistos = mutableSetOf<String>()
    private var buscaAtual: Job? = null

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val loja = repositorio.paginaLoja(lojaId)
                val itens = if (loja != null) repositorio.precosDaLoja(lojaId, null) else emptyList()
                val banners = if (loja != null) runCatching { repositorio.bannersDaLoja(loja.pdvId) }.getOrDefault(emptyList())
                    else emptyList()
                _estado.update { it.copy(carregando = false, loja = loja, itens = itens, banners = banners) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message) }
            }
        }
    }

    /** Busca dentro da loja (espera a pessoa parar de digitar). */
    fun alterarBusca(texto: String) {
        _estado.update { it.copy(busca = texto.take(60)) }
        buscaAtual?.cancel()
        buscaAtual = viewModelScope.launch {
            delay(350)
            buscar()
        }
    }

    fun buscar() {
        val termo = _estado.value.busca
        _estado.update { it.copy(buscando = true, erro = null) }
        viewModelScope.launch {
            try {
                val itens = repositorio.precosDaLoja(lojaId, termo.ifBlank { null })
                _estado.update { if (it.busca == termo) it.copy(buscando = false, itens = itens) else it }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(buscando = false, erro = e.message) }
            }
        }
    }

    fun bannerExibido(banner: Banner) {
        if (!bannersVistos.add(banner.id)) return
        viewModelScope.launch { repositorio.registrarVisualizacao(banner.id) }
    }

    suspend fun denunciarBanner(banner: Banner, motivo: String?): Boolean = repositorio.denunciarBanner(banner.id, motivo)
}

/** Página pública da loja, em tela cheia (aberta ao tocar no nome do comércio na busca). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaLoja(repositorio: CotacoesRepositorio, lojaId: String, aoFechar: () -> Unit) {
    val vm: LojaViewModel = viewModel(key = "loja-$lojaId") { LojaViewModel(repositorio, lojaId) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val foco = LocalFocusManager.current
    var denunciando by remember { mutableStateOf<Banner?>(null) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(estado.loja?.pdvNome ?: stringResource(R.string.loja_titulo), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = aoFechar) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.loja_voltar))
                            }
                        },
                        actions = {
                            estado.loja?.let { loja ->
                                IconButton(onClick = { compartilhar(contexto, loja) }) {
                                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.loja_compartilhar))
                                }
                            }
                        },
                        colors = coresBarraTopo(),
                    )
                },
            ) { margens ->
                val loja = estado.loja
                when {
                    estado.carregando -> Box(Modifier.padding(margens).fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    loja == null -> Box(Modifier.padding(margens).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(estado.erro ?: stringResource(R.string.loja_indisponivel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        Modifier.padding(margens).fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { Cabecalho(loja, aoCompartilhar = { compartilhar(contexto, loja) }) }
                        if (estado.banners.isNotEmpty()) {
                            item {
                                BannerPatrocinado(
                                    estado.banners,
                                    aoExibir = vm::bannerExibido,
                                    aoTocar = { b -> b.link?.let { abrir(contexto, Intent(Intent.ACTION_VIEW, it.toUri())) } },
                                    aoDenunciar = { denunciando = it },
                                    margemLateral = 0.dp,
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = estado.busca,
                                onValueChange = vm::alterarBusca,
                                placeholder = { Text(stringResource(R.string.loja_buscar)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (estado.busca.isNotEmpty()) {
                                        IconButton(onClick = { vm.alterarBusca(""); foco.clearFocus() }) {
                                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.loja_limpar_busca))
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { vm.buscar(); foco.clearFocus() }),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        item {
                            Text(
                                if (estado.buscando) stringResource(R.string.loja_buscando)
                                else stringResource(R.string.loja_contagem, estado.itens.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (estado.itens.isEmpty() && !estado.buscando) {
                            item {
                                Text(
                                    stringResource(if (estado.busca.isBlank()) R.string.loja_sem_itens else R.string.loja_busca_vazia),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        items(estado.itens, key = { it.id }) { item -> LinhaItem(item) }
                        item {
                            Text(stringResource(R.string.loja_aviso), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }

    denunciando?.let { b ->
        FolhaDenunciarBanner(
            banner = b,
            aoDenunciar = { motivo -> vm.denunciarBanner(b, motivo) },
            aoFechar = { denunciando = null },
        )
    }
}

@Composable
private fun Cabecalho(loja: PaginaLoja, aoCompartilhar: () -> Unit) {
    val contexto = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(loja.pdvNome, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            loja.lojaNome?.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            loja.endereco?.let { endereco ->
                Text(
                    "📍 $endereco",
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        val local = loja.local
                        val geo = if (local != null) "geo:${local.latitude},${local.longitude}?q=" else "geo:0,0?q="
                        abrir(contexto, Intent(Intent.ACTION_VIEW, (geo + android.net.Uri.encode(endereco)).toUri()))
                    },
                )
            }
            loja.telefone?.let { tel ->
                Text(
                    "💬 WhatsApp: $tel",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { WhatsApp.abrirConversa(contexto, tel, Mensagens.contatoLoja(loja.titulo)) },
                )
            }
            loja.site?.let { site ->
                Text(
                    "🌐 $site",
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { abrir(contexto, Intent(Intent.ACTION_VIEW, site.toUri())) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedButton(onClick = aoCompartilhar, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("  " + stringResource(R.string.loja_compartilhar))
                }
            }
        }
    }
}

/** Uma linha da tabela da loja: produto, preço em branco, validade e OBS. */
@Composable
private fun LinhaItem(item: ItemLoja) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CoresTabelapp.Cartao, contentColor = CoresTabelapp.TextoPrincipal),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(item.produto, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(top = 2.dp, end = 8.dp))
                val normal = item.precoNormal
                if (normal != null) {
                    // Preço no app/clube da loja; abaixo, "no app" e o preço normal, menor e sem legenda.
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = item.descricaoPreco },
                    ) {
                        Text(item.preco, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
                        Text(PrecoExibido.LEGENDA_APP, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(normal, fontSize = 13.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(item.preco, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
                }
            }
            Text(item.validadeTexto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.obs?.let {
                Text("OBS: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Compartilhar loja": menu de compartilhar do Android (WhatsApp, e-mail...). */
private fun compartilhar(contexto: android.content.Context, loja: PaginaLoja) {
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, CompartilharLoja.texto(loja))
    }
    abrir(contexto, Intent.createChooser(envio, contexto.getString(R.string.loja_compartilhar)))
}
