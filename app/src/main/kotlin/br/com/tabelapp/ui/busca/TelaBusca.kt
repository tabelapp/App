package br.com.tabelapp.ui.busca

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Ordenacao
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaBusca(container: AppContainer, usuario: Usuario) {
    val vm: BuscaViewModel = viewModel(key = "busca-${usuario.id}") {
        BuscaViewModel(container.cotacoes, container.preferencias, container.localizacao)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val foco = LocalFocusManager.current
    val escopo = rememberCoroutineScope()
    var pdvAberto by remember { mutableStateOf<Cotacao?>(null) }

    // "Mais perto" precisa da localização: pede permissão na hora em que o usuário escolhe.
    val pedirLocalizacao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.atualizarPosicao()
        vm.escolherOrdenacao(Ordenacao.MAIS_PERTO)
    }
    fun escolher(ordenacao: Ordenacao) {
        if (ordenacao == Ordenacao.MAIS_PERTO && estado.ordenacao != ordenacao && !container.localizacao.temPermissao()) {
            pedirLocalizacao.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            vm.escolherOrdenacao(ordenacao)
        }
    }

    Scaffold(
        // A barra de baixo (abas) já cuida da borda inferior da tela.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tabelapp", fontWeight = FontWeight.Bold)
                        Text("Quem pesquisa economiza", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    MenuUsuario(usuario, aoSair = { escopo.launch { runCatching { container.auth.sair() } } })
                },
            )
        },
    ) { margens ->
        Column(Modifier.padding(margens).fillMaxSize()) {
            OutlinedTextField(
                value = estado.texto,
                onValueChange = vm::aoDigitar,
                placeholder = { Text("Buscar produto ou serviço") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (estado.texto.isNotEmpty() || estado.termoBuscado != null) {
                        IconButton(onClick = { vm.limpar(); foco.clearFocus() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.buscar(); foco.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Ordenacao.entries.forEach { o ->
                    FilterChip(
                        selected = estado.ordenacao == o,
                        onClick = { escolher(o) },
                        label = { Text(o.rotulo) },
                    )
                }
            }

            Text(
                text = when {
                    estado.termoBuscado == null -> "Últimos preços lançados"
                    estado.carregando -> "Buscando \"${estado.termoBuscado}\"…"
                    else -> "${estado.resultados.size} resultado(s) para \"${estado.termoBuscado}\""
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(Modifier.fillMaxSize()) {
                when {
                    estado.carregando && estado.resultados.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    estado.erro != null -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(estado.erro.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::tentarDeNovo) { Text("Tentar de novo") }
                    }

                    estado.resultados.isEmpty() -> Text(
                        if (estado.termoBuscado == null) "Ainda não há preços lançados."
                        else "Nenhum preço encontrado. Tente outra palavra.",
                        Modifier.align(Alignment.Center).padding(24.dp),
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(estado.resultados, key = { it.id }) { cotacao ->
                            CartaoCotacao(
                                cotacao = cotacao,
                                maisBarato = cotacao.id in estado.maisBaratos,
                                posicao = estado.posicao,
                                aoAbrirPdv = { pdvAberto = cotacao },
                            )
                        }
                    }
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
}

@Composable
private fun MenuUsuario(usuario: Usuario, aoSair: () -> Unit) {
    var aberto by remember { mutableStateOf(false) }
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
            // Próximas etapas do MVP — aparecem desabilitadas para mostrar o que vem por aí.
            DropdownMenuItem(text = { Text("Lista de compras (em breve)") }, onClick = {}, enabled = false)
            if (usuario.tipo == TipoConta.CNPJ) {
                DropdownMenuItem(text = { Text("Área do PDV (em breve)") }, onClick = {}, enabled = false)
            }
            if (usuario.tipo == TipoConta.ADMIN) {
                DropdownMenuItem(text = { Text("Painel Admin (em breve)") }, onClick = {}, enabled = false)
            }
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
                    Text(
                        tel,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            abrir(contexto, Intent(Intent.ACTION_DIAL, "tel:${tel.filter { it.isDigit() }}".toUri()))
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

internal fun abrir(contexto: Context, intent: Intent) {
    try {
        contexto.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Nenhum app para abrir (ex.: tablet sem discador). Nada a fazer.
    }
}
