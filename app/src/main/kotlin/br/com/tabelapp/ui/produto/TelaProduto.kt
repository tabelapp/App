package br.com.tabelapp.ui.produto

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Destaque
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.core.Obs
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.core.SerieHistorico
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.ui.busca.BannerPatrocinado
import br.com.tabelapp.ui.busca.CompartilharPreco
import br.com.tabelapp.ui.busca.FolhaDenunciarBanner
import br.com.tabelapp.ui.busca.FolhaSobrePreco
import br.com.tabelapp.ui.busca.SeloNoApp
import br.com.tabelapp.ui.busca.abrir
import br.com.tabelapp.ui.busca.abrirNoMapa
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.loja.TelaLoja
import br.com.tabelapp.ui.tema.ContornoMarca
import br.com.tabelapp.ui.tema.CoresTabelapp
import br.com.tabelapp.ui.tema.coresBarraTopo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoProduto(
    val banners: List<Banner> = emptyList(),
    val carregandoHistorico: Boolean = true,
    val serie: SerieHistorico? = null,
    val erroHistorico: Boolean = false,
)

/** Página do produto: banners do PDV e o histórico do gráfico, carregados à parte (nunca travam a tela). */
class ProdutoViewModel(private val repositorio: CotacoesRepositorio, private val cotacao: Cotacao) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoProduto())
    val estado: StateFlow<EstadoProduto> = _estado.asStateFlow()
    private val bannersVistos = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val serie = runCatching { SerieHistorico.montar(repositorio.historicoPreco(cotacao.id)) }.getOrNull()
            _estado.update { it.copy(carregandoHistorico = false, serie = serie, erroHistorico = serie == null) }
        }
        cotacao.pdvId?.let { pdvId ->
            viewModelScope.launch {
                val banners = runCatching { repositorio.bannersDaLoja(pdvId) }.getOrDefault(emptyList())
                _estado.update { it.copy(banners = banners) }
            }
        }
    }

    fun bannerExibido(banner: Banner) {
        if (!bannersVistos.add(banner.id)) return
        viewModelScope.launch { runCatching { repositorio.registrarVisualizacao(banner.id) } }
    }

    suspend fun denunciarBanner(banner: Banner, motivo: String?): Boolean = repositorio.denunciarBanner(banner.id, motivo)
}

/**
 * Página do produto (tela cheia, aberta ao tocar no card da busca), na ordem do mockup:
 * banner do PDV, nome e loja ("Ver loja ›"), bloco de preço, Local / Válido até / Origem / OBS,
 * contato e "Sobre este preço", gráfico de variação e o botão Compartilhar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaProduto(
    repositorio: CotacoesRepositorio,
    cotacao: Cotacao,
    destaque: Destaque?,
    /** "Esse preço não confere" (true = registrou). */
    aoDenunciar: suspend (motivo: String?) -> Boolean,
    /** Só para o Admin: "Remover este preço". */
    aoRemover: (suspend () -> Unit)?,
    aoFechar: () -> Unit,
) {
    val vm: ProdutoViewModel = viewModel(key = "produto-${cotacao.id}") { ProdutoViewModel(repositorio, cotacao) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var verLoja by remember { mutableStateOf(false) }
    var sobrePreco by remember { mutableStateOf(false) }
    var denunciando by remember { mutableStateOf<Banner?>(null) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding(),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.produto_titulo)) },
                        navigationIcon = {
                            IconButton(onClick = aoFechar) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.loja_voltar))
                            }
                        },
                        colors = coresBarraTopo(),
                    )
                },
                bottomBar = {
                    OutlinedButton(
                        onClick = { escopo.launch { CompartilharPreco.compartilhar(contexto, cotacao) } },
                        border = ContornoMarca,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(56.dp),
                    ) {
                        Text(stringResource(R.string.produto_compartilhar), color = CoresTabelapp.AmareloMarca,
                            fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
            ) { margens ->
                Column(
                    Modifier.padding(margens).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 2. Banner pago do PDV (sem banner: nenhum espaço reservado).
                    if (estado.banners.isNotEmpty()) {
                        BannerPatrocinado(
                            estado.banners,
                            aoExibir = vm::bannerExibido,
                            aoTocar = { b -> b.link?.let { abrir(contexto, Intent(Intent.ACTION_VIEW, it.toUri())) } },
                            aoDenunciar = { denunciando = it },
                            margemLateral = 0.dp,
                        )
                    }
                    // 3. Nome e loja.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(cotacao.produto, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(cotacao.pdvNome, cotacao.lojaNome).joinToString(" — "),
                                color = CoresTabelapp.NomeLoja, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (cotacao.lojaId != null) {
                                TextButton(onClick = { verLoja = true }) {
                                    Text(stringResource(R.string.produto_ver_loja), color = CoresTabelapp.NomeLoja)
                                }
                            }
                        }
                        destaque?.let { d ->
                            Text(
                                stringResource(when (d) {
                                    Destaque.MENOR_PRECO -> R.string.selo_menor_preco
                                    Destaque.PARTE_DE_COMBO -> R.string.chip_parte_de_combo
                                    Destaque.CONFIRA_NA_LOJA -> R.string.chip_confira_na_loja
                                }),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                color = if (d == Destaque.MENOR_PRECO) CoresTabelapp.AmareloMarca else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 4. Bloco de preço.
                    BlocoPreco(cotacao)
                    // 5. Local, validade, origem e OBS.
                    Column {
                        cotacao.endereco?.let { endereco ->
                            Linha(stringResource(R.string.produto_local)) {
                                Text(endereco, textDecoration = TextDecoration.Underline, textAlign = TextAlign.End,
                                    modifier = Modifier.clickable { abrirNoMapa(contexto, cotacao) })
                            }
                        }
                        if (cotacao.fonte == Fonte.USUARIO_NF) {
                            val dia = cotacao.dataNf ?: cotacao.validade?.minusDays(Validade.NF_DIAS)
                            dia?.let { Linha(stringResource(R.string.produto_praticado_em)) { Text(Validade.formatar(it)) } }
                        } else {
                            cotacao.validade?.let { Linha(stringResource(R.string.produto_valido_ate)) { Text(Validade.formatar(it)) } }
                        }
                        Linha(stringResource(R.string.produto_origem)) { Text(Obs.origem(cotacao.fonte)) }
                        Obs.doPdv(cotacao)?.let { obs -> Linha(stringResource(R.string.produto_obs)) { Text(obs, textAlign = TextAlign.End) } }
                    }
                    // Ações que ficavam no card: WhatsApp da loja e "Sobre este preço".
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cotacao.telefone?.takeIf { it.isNotBlank() }?.let { tel ->
                            OutlinedButton(onClick = { WhatsApp.abrirConversa(contexto, tel, Mensagens.interesseNaOferta(cotacao)) }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = CoresTabelapp.WhatsApp)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.produto_whatsapp))
                            }
                        }
                        TextButton(onClick = { sobrePreco = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sobre_preco_icone))
                        }
                    }
                    // 6. Gráfico de variação do preço.
                    CartaoVariacaoPreco(estado.serie, estado.carregandoHistorico, estado.erroHistorico)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (verLoja) cotacao.lojaId?.let { TelaLoja(repositorio, it, aoFechar = { verLoja = false }) }
    if (sobrePreco) {
        FolhaSobrePreco(
            cotacao = cotacao,
            aoDenunciar = aoDenunciar,
            aoFechar = { sobrePreco = false },
            aoRemover = aoRemover?.let { remover -> suspend { remover(); aoFechar() } },
        )
    }
    denunciando?.let { b ->
        FolhaDenunciarBanner(b, aoDenunciar = { motivo -> vm.denunciarBanner(b, motivo) }, aoFechar = { denunciando = null })
    }
}

/** Preço principal grande; "por kg · no app" embaixo; "Preço normal" riscado à direita quando existir. */
@Composable
private fun BlocoPreco(cotacao: Cotacao) {
    val descricao = PrecoExibido.descricao(cotacao)
    Card(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = descricao },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (cotacao.aPartirDe) PrecoExibido.A_PARTIR_DE.lowercase() + " " else "") + Dinheiro.formatar(cotacao.precoCentavos),
                    fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black, color = CoresTabelapp.Preco,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(porUnidade(cotacao.unidade), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cotacao.temPrecoApp) {
                        Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SeloNoApp()
                    }
                }
            }
            cotacao.precoNormalRiscado?.let { normal ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.produto_preco_normal), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Dinheiro.formatar(normal), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "por kg", "por unidade", "por hora"... */
private fun porUnidade(u: Unidade): String = "por " + (u.sufixo ?: "unidade")

@Composable
private fun Linha(rotulo: String, valor: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rotulo, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { valor() }
        }
        HorizontalDivider()
    }
}
