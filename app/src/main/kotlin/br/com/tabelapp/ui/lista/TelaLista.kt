package br.com.tabelapp.ui.lista

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.MelhorPorItem
import br.com.tabelapp.core.OpcaoPdvUnico
import br.com.tabelapp.core.TextoLista
import br.com.tabelapp.core.Texto
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.coresBarraTopo
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Aba "Lista": listas de compras do usuário, montagem com busca inteligente e os dois relatórios. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaLista(container: AppContainer, usuario: Usuario) {
    val vm: ListaViewModel = viewModel(key = "lista-${usuario.id}") {
        ListaViewModel(container.listas, container.cotacoes, container.localizacao)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()
    var renomeando by remember { mutableStateOf(false) }

    BackHandler(enabled = estado.etapa != EtapaLista.LISTAS) {
        if (estado.etapa == EtapaLista.RESULTADO) vm.voltarParaEdicao() else vm.voltarParaListas()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (estado.etapa) {
                            EtapaLista.LISTAS -> "Lista de compras"
                            EtapaLista.EDITANDO -> estado.lista?.nome ?: "Lista"
                            EtapaLista.RESULTADO -> "Melhores preços"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (estado.etapa != EtapaLista.LISTAS) {
                        IconButton(onClick = {
                            if (estado.etapa == EtapaLista.RESULTADO) vm.voltarParaEdicao() else vm.voltarParaListas()
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                    }
                },
                actions = {
                    if (estado.etapa == EtapaLista.EDITANDO) {
                        IconButton(onClick = { renomeando = true }) { Icon(Icons.Default.Edit, contentDescription = "Renomear lista") }
                    }
                },
                colors = coresBarraTopo(),
            )
        },
    ) { margens ->
        Box(Modifier.padding(margens).fillMaxSize()) {
            when (estado.etapa) {
                EtapaLista.LISTAS -> MinhasListas(estado, vm)
                EtapaLista.EDITANDO -> EditarLista(estado, vm)
                EtapaLista.RESULTADO -> Resultado(estado, vm)
            }
        }
    }

    if (renomeando) {
        DialogoNome(
            titulo = "Renomear lista",
            inicial = estado.lista?.nome.orEmpty(),
            aoConfirmar = { vm.renomear(it); renomeando = false },
            aoFechar = { renomeando = false },
        )
    }
}

// ---------------------------------------------------------------- minhas listas

@Composable
private fun MinhasListas(estado: EstadoLista, vm: ListaViewModel) {
    var criando by remember { mutableStateOf(false) }
    var excluindo by remember { mutableStateOf<ListaResumo?>(null) }
    val formato = remember { DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneId.of("America/Sao_Paulo")) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Monte sua lista no seu tempo — ela fica salva. Quando terminar, o Tabelapp pesquisa onde " +
                    "sai mais barato.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(onClick = { criando = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nova lista")
            }
        }
        estado.erro?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (estado.carregando) {
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (estado.listas.isEmpty()) {
            item { Text("Você ainda não tem listas.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(estado.listas, key = { it.id }) { l ->
            Card(Modifier.fillMaxWidth().clickable { vm.abrir(l) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.nome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${l.itens} ${if (l.itens == 1) "item" else "itens"} · atualizada ${formato.format(l.atualizadaEm)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { excluindo = l }) { Icon(Icons.Default.Delete, contentDescription = "Excluir lista") }
                }
            }
        }
    }

    if (criando) {
        DialogoNome(
            titulo = "Nova lista",
            inicial = "",
            aoConfirmar = { vm.novaLista(it); criando = false },
            aoFechar = { criando = false },
        )
    }
    excluindo?.let { l ->
        AlertDialog(
            onDismissRequest = { excluindo = null },
            title = { Text("Excluir \"${l.nome}\"?") },
            confirmButton = { TextButton(onClick = { vm.excluirLista(l); excluindo = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { excluindo = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DialogoNome(titulo: String, inicial: String, aoConfirmar: (String) -> Unit, aoFechar: () -> Unit) {
    var nome by rememberSaveable { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = {
            OutlinedTextField(
                value = nome, onValueChange = { nome = it.take(80) },
                label = { Text("Nome da lista") }, placeholder = { Text("Ex.: Compras do mês") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(nome.ifBlank { "Minha lista" }) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- montar a lista

@Composable
private fun EditarLista(estado: EstadoLista, vm: ListaViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = estado.texto, onValueChange = vm::alterarTexto,
            placeholder = { Text("Adicionar produto (ex.: arroz 5kg)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (estado.texto.isNotBlank()) {
                    IconButton(onClick = { vm.adicionar(estado.texto) }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Adicionar o que digitei")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.adicionar(estado.texto) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (estado.texto.trim().length >= 2) {
            // Sugestões da busca inteligente (produtos que têm preço agora).
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column {
                    val digitadoJaSugerido = estado.sugestoes.any { Texto.normalizar(it.produto) == Texto.normalizar(estado.texto) }
                    if (!digitadoJaSugerido) {
                        LinhaSugestao(
                            titulo = "Adicionar \"${estado.texto.trim()}\"",
                            detalhe = "como você digitou",
                            aoTocar = { vm.adicionar(estado.texto) },
                        )
                    }
                    estado.sugestoes.forEach { s ->
                        HorizontalDivider()
                        LinhaSugestao(
                            titulo = s.produto,
                            detalhe = "a partir de ${Dinheiro.formatar(s.menorPrecoCentavos)} · " +
                                "em ${s.lugares} ${if (s.lugares == 1) "lugar" else "lugares"}",
                            aoTocar = { vm.adicionar(s.produto) },
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

        estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (estado.itens.isEmpty()) {
                item {
                    Text(
                        "Sua lista está vazia. Digite um produto acima: o Tabelapp sugere os que têm preço " +
                            "cadastrado.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "${estado.itens.size} ${if (estado.itens.size == 1) "item" else "itens"} · salva automaticamente",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            items(estado.itens, key = { it.id }) { item -> LinhaItem(item, vm) }
        }

        Button(
            onClick = vm::pesquisar,
            enabled = estado.itens.isNotEmpty() && !estado.processando,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (estado.processando) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Pesquisando preços…")
            } else {
                Text("Pesquisar melhores preços")
            }
        }
    }
}

@Composable
private fun LinhaSugestao(titulo: String, detalhe: String, aoTocar: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = aoTocar).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontWeight = FontWeight.SemiBold)
            Text(detalhe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Add, contentDescription = "Adicionar", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LinhaItem(item: ItemSalvo, vm: ListaViewModel) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.produto, Modifier.weight(1f))
            IconButton(onClick = { vm.mudarQuantidade(item, -1) }, enabled = item.quantidade > 1) {
                Icon(Icons.Default.Remove, contentDescription = "Menos")
            }
            Text(TextoLista.quantidade(item.quantidade), fontWeight = FontWeight.Bold)
            IconButton(onClick = { vm.mudarQuantidade(item, 1) }) { Icon(Icons.Default.Add, contentDescription = "Mais") }
            IconButton(onClick = { vm.remover(item) }) { Icon(Icons.Default.Delete, contentDescription = "Tirar da lista") }
        }
        HorizontalDivider()
    }
}

// ---------------------------------------------------------------- resultado

@Composable
private fun Resultado(estado: EstadoLista, vm: ListaViewModel) {
    val contexto = LocalContext.current
    var aba by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = aba) {
            Tab(selected = aba == 0, onClick = { aba = 0 }, text = { Text("Num lugar só") })
            Tab(selected = aba == 1, onClick = { aba = 1 }, text = { Text("Item a item") })
        }
        Button(
            onClick = {
                val texto = if (aba == 0) vm.textoPorPdv() else vm.textoPorItem()
                texto?.let { WhatsApp.enviarTexto(contexto, it) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Enviar esta lista pelo WhatsApp")
        }
        when (aba) {
            0 -> PorPdv(estado.opcoesPdv, estado.itens.size)
            else -> estado.melhorPorItem?.let { PorItem(it) }
        }
    }
}

@Composable
private fun PorPdv(opcoes: List<OpcaoPdvUnico>, totalItens: Int) {
    if (opcoes.isEmpty()) {
        Text("Nenhum preço encontrado para os itens da lista.", Modifier.padding(16.dp))
        return
    }
    var aberto by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Onde comprar a lista toda (ou o máximo dela) pagando menos:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(opcoes.take(10).size) { i ->
            val o = opcoes[i]
            val destaque = i == 0
            Card(
                Modifier.fillMaxWidth().clickable { aberto = if (aberto == i) -1 else i },
                colors = CardDefaults.cardColors(
                    containerColor = if (destaque) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}º ${o.pdvNome}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(Dinheiro.formatar(o.totalCentavos), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        listOfNotNull(
                            if (o.completo) "Tem todos os $totalItens itens" else "Tem ${o.encontrados.size} de $totalItens itens",
                            o.distanciaKm?.let(Geo::formatarDistancia),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    o.endereco?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (aberto == i) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        o.encontrados.forEach { c ->
                            Row {
                                Text("${TextoLista.quantidade(c.item.quantidade)}x ${c.item.produto}", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(Dinheiro.formatar(c.subtotalCentavos), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (o.faltando.isNotEmpty()) {
                            Text("Faltam: ${o.faltando.joinToString(", ") { it.produto }}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("Toque para ver os itens", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PorItem(r: MelhorPorItem) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Total comprando cada item onde está mais barato", style = MaterialTheme.typography.bodySmall)
                    Text(Dinheiro.formatar(r.totalCentavos), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (r.itens.isNotEmpty()) {
                        Text("em ${r.quantidadePdvs} ${if (r.quantidadePdvs == 1) "lugar" else "lugares"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(r.itens.size) { i ->
            val c = r.itens[i]
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Text("${TextoLista.quantidade(c.item.quantidade)}x ${c.item.produto}", Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    Text(Dinheiro.formatar(c.subtotalCentavos), fontWeight = FontWeight.Bold)
                }
                Text(
                    listOfNotNull(
                        c.cotacao.produto.takeIf { Texto.normalizar(it) != Texto.normalizar(c.item.produto) },
                        "${Dinheiro.formatar(c.cotacao.precoCentavos)} cada",
                        c.cotacao.pdvNome,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.cotacao.endereco?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
        if (r.semPreco.isNotEmpty()) {
            item {
                Text(
                    "Sem preço encontrado: ${r.semPreco.joinToString(", ") { it.produto }}",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
