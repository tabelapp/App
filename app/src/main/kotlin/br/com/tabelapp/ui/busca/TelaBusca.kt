package br.com.tabelapp.ui.busca

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.R
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.ContatoTabelapp
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Destaque
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.core.Ordenacao
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.comum.AcaoErro
import br.com.tabelapp.ui.comum.DocumentoLegal
import br.com.tabelapp.ui.comum.PainelErro
import br.com.tabelapp.ui.comum.TelaTextoLegal
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.principal.MinhaConta
import br.com.tabelapp.ui.principal.QuemSomos
import br.com.tabelapp.ui.tema.coresBarraTopo
import kotlinx.coroutines.launch

/** O segundo banner pago entra no lugar do 5º card (antes do card de índice 4). */
private const val POSICAO_BANNER_MEIO = 4

/** Altura do cabeçalho da busca, incluindo a barra de status (layout compacto). */
private val ALTURA_CABECALHO: Dp = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaBusca(container: AppContainer, usuario: Usuario, aoEnviarPrecos: () -> Unit = {}) {
    val vm: BuscaViewModel = viewModel(key = "busca-${usuario.id}") {
        BuscaViewModel(container.cotacoes, container.preferencias, container.localizacao, container.listas)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val foco = LocalFocusManager.current
    val escopo = rememberCoroutineScope()
    val contexto = LocalContext.current
    var pdvAberto by remember { mutableStateOf<Cotacao?>(null) }
    var sobrePreco by remember { mutableStateOf<Cotacao?>(null) }
    var verMapa by remember { mutableStateOf(false) }

    // No mapa, mostramos onde a pessoa está: pede a permissão (uma vez) ao abrir. Negar não impede o mapa.
    val pedirLocalizacao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.atualizarPosicao()
        verMapa = true
    }
    fun abrirMapa() {
        if (container.localizacao.temPermissao()) {
            verMapa = true
        } else {
            pedirLocalizacao.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    fun tocarBanner(banner: Banner?) {
        if (banner == null) {
            WhatsApp.abrirConversa(contexto, ContatoTabelapp.WHATSAPP, Mensagens.QUERO_ANUNCIAR)
        } else {
            banner.link?.let { abrir(contexto, Intent(Intent.ACTION_VIEW, it.toUri())) }
        }
    }

    Scaffold(
        // A barra de baixo (abas) já cuida da borda inferior da tela.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Cabeçalho com 72 dp no total, contando a barra de status do celular.
            val barraDeStatus = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            TopAppBar(
                title = {
                    Column {
                        Text("Tabelapp", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Quem pesquisa economiza", style = MaterialTheme.typography.labelSmall)
                    }
                },
                expandedHeight = (ALTURA_CABECALHO - barraDeStatus).coerceAtLeast(40.dp),
                colors = coresBarraTopo(),
                actions = {
                    MenuUsuario(usuario, container.auth, aoSair = { escopo.launch { runCatching { container.auth.sair() } } })
                },
            )
        },
    ) { margens ->
        Column(Modifier.padding(margens).fillMaxSize()) {
            // Topo, bloco de busca e cards têm a mesma altura (a de um card de preço).
            BannerPatrocinado(estado.bannersTopo, aoExibir = vm::bannerExibido, aoTocar = ::tocarBanner)
            BlocoBusca(
                estado = estado,
                vm = vm,
                aoBuscar = { vm.buscar(); foco.clearFocus() },
                aoLimpar = { vm.limpar(); foco.clearFocus() },
                aoVerMapa = ::abrirMapa,
            )

            Box(Modifier.fillMaxSize()) {
                when {
                    estado.carregando && estado.resultados.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    estado.erro != null -> PainelErro(
                        tipo = estado.erroTipo ?: TipoErro.SERVIDOR_INSTAVEL,
                        texto = estado.erro.takeIf { estado.erroTipo == null },
                        principal = AcaoErro(stringResource(R.string.erro_botao_tentar_de_novo), vm::tentarDeNovo),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )

                    // Nada encontrado: convite calmo para outra palavra ou para ser o primeiro a cadastrar.
                    estado.resultados.isEmpty() -> PainelErro(
                        TipoErro.BUSCA_VAZIA,
                        principal = AcaoErro(stringResource(R.string.erro_botao_enviar_precos), aoEnviarPrecos),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(estado.resultados, key = { _, c -> c.id }) { indice, cotacao ->
                            Column {
                                // Segundo banner pago, no lugar do 5º card.
                                if (indice == POSICAO_BANNER_MEIO) {
                                    BannerPatrocinado(
                                        estado.bannersMeio,
                                        aoExibir = vm::bannerExibido,
                                        aoTocar = ::tocarBanner,
                                        margemLateral = 0.dp,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                CartaoCotacao(
                                    alternado = indice % 2 == 1,
                                    cotacao = cotacao,
                                    destaque = estado.destaques[cotacao.id],
                                    posicao = estado.posicao,
                                    aoAbrirPdv = { pdvAberto = cotacao },
                                    aoCompartilhar = { escopo.launch { CompartilharPreco.compartilhar(contexto, cotacao) } },
                                    aoSobrePreco = { sobrePreco = cotacao },
                                    aoAbrirMapa = { abrirNoMapa(contexto, cotacao) },
                                    aoChamarNoWhatsApp = {
                                        cotacao.telefone?.let { WhatsApp.abrirConversa(contexto, it, Mensagens.interesseNaOferta(cotacao)) }
                                    },
                                )
                            }
                        }
                    }
                }
                // Autocompletar por cima dos resultados enquanto digita.
                if (estado.texto.trim().length >= 2 && (estado.sugestoes.isNotEmpty() || estado.buscandoSugestoes)) {
                    ListaSugestoes(
                        estado = estado,
                        aoEscolher = { produto -> vm.escolherSugestao(produto); foco.clearFocus() },
                        aoBuscarDigitado = { vm.buscar(); foco.clearFocus() },
                    )
                }
            }
        }
    }

    if (estado.mostrarDisclaimer) {
        AlertDialog(
            onDismissRequest = {}, // precisa de um "Entendi" explícito
            title = { Text("Antes de começar") },
            text = {
                Text(
                    "Os preços exibidos no Tabelapp são informados por estabelecimentos e por outros " +
                        "usuários. O app apenas reúne essas informações — a responsabilidade pelos preços é " +
                        "de terceiros.\n\nOs preços podem variar sem aviso prévio, conforme cada " +
                        "estabelecimento. Confirme o valor na hora da compra."
                )
            },
            confirmButton = { Button(onClick = vm::aceitarDisclaimer) { Text("Entendi") } },
        )
    }

    pdvAberto?.let { c -> DialogoPdv(c, aoFechar = { pdvAberto = null }) }

    sobrePreco?.let { c ->
        FolhaSobrePreco(
            cotacao = c,
            aoDenunciar = { motivo -> vm.denunciar(c, motivo) },
            aoFechar = { sobrePreco = null },
            aoRemover = if (usuario.tipo == TipoConta.ADMIN) {
                {
                    container.pdvs.removerCotacao(c.id)
                    vm.tirarDosResultados(c.id)
                }
            } else {
                null
            },
        )
    }

    if (verMapa) {
        MapaDePrecos(
            cotacoes = estado.resultados,
            termo = estado.termoBuscado,
            geocodificador = container.geocodificador,
            idsSelo = estado.destaques.filterValues { it == Destaque.MENOR_PRECO }.keys,
            mostrarMinhaPosicao = container.localizacao.temPermissao(),
            aoFechar = { verMapa = false },
        )
    }
}

@Composable
private fun MenuUsuario(usuario: Usuario, auth: AuthRepositorio, aoSair: () -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    var quemSomos by remember { mutableStateOf(false) }
    var minhaConta by remember { mutableStateOf(false) }
    var documento by remember { mutableStateOf<DocumentoLegal?>(null) }
    val contexto = LocalContext.current
    if (quemSomos) QuemSomos(aoFechar = { quemSomos = false })
    if (minhaConta) MinhaConta(usuario, auth, aoFechar = { minhaConta = false })
    documento?.let { TelaTextoLegal(it, aoFechar = { documento = null }) }
    Box {
        IconButton(onClick = { aberto = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
        }
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            Text(
                usuario.nome ?: usuario.email.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_minha_conta)) }, onClick = { aberto = false; minhaConta = true })
            DropdownMenuItem(text = { Text("Quem somos") }, onClick = { aberto = false; quemSomos = true })
            DropdownMenuItem(
                text = { Text("Fale conosco (WhatsApp)") },
                onClick = {
                    aberto = false
                    WhatsApp.abrirConversa(contexto, ContatoTabelapp.WHATSAPP, Mensagens.FALE_CONOSCO)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.legal_privacidade)) },
                onClick = { aberto = false; documento = DocumentoLegal.PRIVACIDADE },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.legal_termos)) },
                onClick = { aberto = false; documento = DocumentoLegal.TERMOS },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Sair") }, onClick = { aberto = false; aoSair() })
        }
    }
}

@Composable
private fun DialogoPdv(cotacao: Cotacao, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(cotacao.pdvNome) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cotacao.lojaNome?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                cotacao.endereco?.let { endereco ->
                    Text(
                        endereco,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val local = cotacao.local
                            val geo = if (local != null) "geo:${local.latitude},${local.longitude}?q=" else "geo:0,0?q="
                            abrir(contexto, Intent(Intent.ACTION_VIEW, (geo + android.net.Uri.encode(endereco)).toUri()))
                        },
                    )
                }
                cotacao.telefone?.let { tel ->
                    // Contato pelo WhatsApp, já com a mensagem sobre a oferta.
                    Text(
                        "💬 WhatsApp: $tel",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            WhatsApp.abrirConversa(contexto, tel, Mensagens.interesseNaOferta(cotacao))
                        },
                    )
                }
                cotacao.site?.let { site ->
                    Text(
                        site,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { abrir(contexto, Intent(Intent.ACTION_VIEW, site.toUri())) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = aoFechar) { Text("Fechar") } },
    )
}

/** Abre o endereço do ponto de venda no app de mapas (Google Maps, Waze...). */
internal fun abrirNoMapa(contexto: Context, cotacao: Cotacao) {
    val endereco = cotacao.endereco?.takeIf { it.isNotBlank() } ?: return
    val local = cotacao.local
    val geo = if (local != null) "geo:${local.latitude},${local.longitude}?q=" else "geo:0,0?q="
    abrir(contexto, Intent(Intent.ACTION_VIEW, (geo + android.net.Uri.encode(endereco)).toUri()))
}

internal fun abrir(contexto: Context, intent: Intent) {
    try {
        contexto.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Nenhum app para abrir (ex.: tablet sem discador). Nada a fazer.
    }
}

/**
 * Bloco de busca do tamanho de um card: campo de busca, um botão só de filtros
 * (ordenação e "Ver no mapa") e, ao lado, o contador e o aviso permanente.
 */
@Composable
private fun BlocoBusca(
    estado: EstadoBusca,
    vm: BuscaViewModel,
    aoBuscar: () -> Unit,
    aoLimpar: () -> Unit,
    aoVerMapa: () -> Unit,
) {
    var filtrosAbertos by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(ALTURA_CARTAO),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedTextField(
                value = estado.texto,
                onValueChange = vm::aoDigitar,
                placeholder = { Text("Buscar produto ou serviço") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (estado.texto.isNotEmpty() || estado.termoBuscado != null) {
                        IconButton(onClick = aoLimpar) { Icon(Icons.Default.Clear, contentDescription = "Limpar busca") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { aoBuscar() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilledTonalButton(
                        onClick = { filtrosAbertos = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(estado.ordenacao?.rotulo ?: "Filtros", maxLines = 1)
                    }
                    DropdownMenu(expanded = filtrosAbertos, onDismissRequest = { filtrosAbertos = false }) {
                        Ordenacao.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.rotulo) },
                                leadingIcon = {
                                    if (estado.ordenacao == o) Icon(Icons.Default.Check, contentDescription = "Escolhido")
                                },
                                onClick = { filtrosAbertos = false; vm.escolherOrdenacao(o) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Ver no mapa") },
                            leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) },
                            enabled = estado.resultados.isNotEmpty(),
                            onClick = { filtrosAbertos = false; aoVerMapa() },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            estado.termoBuscado == null -> "Últimos preços lançados"
                            estado.carregando -> "Buscando \"${estado.termoBuscado}\"…"
                            else -> "${estado.resultados.size} resultado(s) para \"${estado.termoBuscado}\""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Aviso permanente e discreto (nunca repetido nos cards).
                    Text(
                        stringResource(R.string.aviso_resultados),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Sugestões do autocompletar (produtos com preço agora), como na lista de compras. */
@Composable
private fun ListaSugestoes(estado: EstadoBusca, aoEscolher: (String) -> Unit, aoBuscarDigitado: () -> Unit) {
    Card(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 320.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val digitado = estado.texto.trim()
            if (estado.sugestoes.none { Texto.normalizar(it.produto) == Texto.normalizar(digitado) }) {
                LinhaSugestao(titulo = "Buscar \"$digitado\"", detalhe = "como você digitou", aoTocar = aoBuscarDigitado)
            }
            estado.sugestoes.forEach { s ->
                HorizontalDivider()
                LinhaSugestao(
                    titulo = s.produto,
                    detalhe = "a partir de ${Dinheiro.formatar(s.menorPrecoCentavos)} · " +
                        "em ${s.lugares} ${if (s.lugares == 1) "lugar" else "lugares"}",
                    aoTocar = { aoEscolher(s.produto) },
                )
            }
            if (estado.buscandoSugestoes) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun LinhaSugestao(titulo: String, detalhe: String, aoTocar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = aoTocar).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detalhe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
