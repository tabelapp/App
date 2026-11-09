package br.com.tabelapp.ui.admin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.ItemLidoEncarte
import br.com.tabelapp.core.LeitorJsonEncarte
import br.com.tabelapp.core.RegrasEncarte
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.EncartesAdminRepositorio
import br.com.tabelapp.dados.Imagens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PDF maior que isto pede para converter em imagem (custo e limite do servidor). */
private const val MAX_PDF_BYTES = 7_000_000

/**
 * "Encartes" (aba Admin, tela cheia): escolher imagens/PDF -> loja e endereço -> "Ler encarte" ->
 * tabela editável para conferir -> resumo -> "Lançar preços" -> "Desfazer este lote" se precisar.
 * Nada é lançado sem a confirmação do Admin.
 */
@Composable
fun TelaEncartesAdmin(repositorio: EncartesAdminRepositorio, imagens: Imagens, modoDemo: Boolean, aoFechar: () -> Unit) {
    val vm: EncartesAdminViewModel = viewModel(key = "admin-encartes") { EncartesAdminViewModel(repositorio) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()

    fun carregar(uris: List<Uri>) {
        escopo.launch {
            val arquivos = withContext(Dispatchers.IO) { uris.mapNotNull { arquivoDe(contexto, imagens, it) } }
            if (arquivos.size < uris.size) vm.avisarErro("Algum arquivo não pôde ser aberto (PDF muito grande? Converta em imagem).")
            vm.adicionarArquivos(arquivos)
        }
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { carregar(it) }
    val arquivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { carregar(it) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Encartes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = "Fechar") }
                    }
                    Text(
                        if (modoDemo) "Demonstração: a leitura usa dados de exemplo (nada vai para o servidor)."
                        else "O app lê produtos e preços da imagem. Confira tudo na tabela antes de lançar: nada é lançado sem a sua confirmação.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 1. Imagens
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Galeria")
                        }
                        OutlinedButton(onClick = { arquivo.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Arquivo")
                        }
                    }
                    estado.arquivos.forEachIndexed { i, a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${a.nome} (${a.bytes.size / 1024} KB)", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.removerArquivo(i) }) { Text("Tirar") }
                        }
                    }
                }
                // 2. Loja e endereço
                item { CamposLoja(estado, vm) }
                // 3. Ler
                item {
                    Button(
                        onClick = vm::lerEncartes, enabled = estado.arquivos.isNotEmpty() && !estado.lendo,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (estado.itens.isEmpty()) "Ler encarte" else "Ler de novo") }
                    if (estado.lendo) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(estado.progresso ?: "Lendo…")
                        }
                    }
                    estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                    estado.aviso?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                }
                // 4. Tabela editável
                if (estado.itens.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Itens lidos (${estado.itens.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.marcarTodos(true) }) { Text("Marcar todos") }
                            TextButton(onClick = { vm.marcarTodos(false) }) { Text("Nenhum") }
                        }
                    }
                    itemsIndexed(estado.itens, key = { i, item -> "$i-${item.produto}-${item.precoCentavos}" }) { i, item ->
                        LinhaItem(item, estado.validadePadraoData, aoAlternar = { vm.alternar(i) }, aoEditar = { m -> vm.editar(i, m) })
                    }
                }
                if (estado.ignorados.isNotEmpty()) {
                    item {
                        Text("Não lidos (${estado.ignorados.size})", fontWeight = FontWeight.Bold)
                        estado.ignorados.forEach {
                            Text("• ${it.texto.ifBlank { "(sem texto)" }}: ${it.motivo}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // 5 e 6. Resumo, lançar e desfazer
                if (estado.itens.isNotEmpty()) item { Confirmacao(estado, vm) }
            }
        }
    }
}

@Composable
private fun CamposLoja(estado: EstadoEncartes, vm: EncartesAdminViewModel) {
    var sugestoes by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedTextField(
                value = estado.loja, onValueChange = { vm.alterarLoja(it); sugestoes = true },
                label = { Text("Loja (nome)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val filtradas = estado.lojasConhecidas.filter {
                estado.loja.isNotBlank() && it.nome.contains(estado.loja.trim(), ignoreCase = true) && it.nome != estado.loja
            }.take(6)
            DropdownMenu(expanded = sugestoes && filtradas.isNotEmpty(), onDismissRequest = { sugestoes = false }) {
                filtradas.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.nome + (l.endereco?.let { " — $it" } ?: "")) },
                        onClick = { vm.escolherLoja(l); sugestoes = false },
                    )
                }
            }
        }
        OutlinedTextField(
            value = estado.endereco, onValueChange = vm::alterarEndereco,
            label = { Text("Endereço (texto livre)") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = estado.cidadeUf, onValueChange = vm::alterarCidadeUf, singleLine = true,
                label = { Text("Cidade - UF") }, placeholder = { Text("Petrópolis - RJ") }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = estado.validadePadrao, onValueChange = vm::alterarValidadePadrao, singleLine = true,
                label = { Text("Validade padrão") }, placeholder = { Text("dd/mm/aaaa") }, modifier = Modifier.weight(1f),
            )
        }
        Text("Sem endereço, vale a cidade - UF. A validade padrão vale para os itens que vierem sem data.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Uma linha da tabela: marcar, produto, preço, normal (riscado na busca), app, unidade, validade e OBS. */
@Composable
private fun LinhaItem(
    item: ItemLidoEncarte,
    validadePadrao: java.time.LocalDate?,
    aoAlternar: () -> Unit,
    aoEditar: ((ItemLidoEncarte) -> ItemLidoEncarte) -> Unit,
) {
    var preco by remember { mutableStateOf(Dinheiro.formatar(item.precoCentavos).removePrefix("R$ ")) }
    var normal by remember { mutableStateOf(item.precoNormalCentavos?.let { Dinheiro.formatar(it).removePrefix("R$ ") }.orEmpty()) }
    var validade by remember { mutableStateOf(item.validade?.let(Validade::formatar).orEmpty()) }
    var unidades by remember { mutableStateOf(false) }
    val erros = RegrasEncarte.erros(if (item.validade == null) item.copy(validade = validadePadrao) else item)
    val cores = if (item.alcoolico) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = cores) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.alcoolico) {
                Text("Bebida alcoólica: veio desmarcada. Você decide.", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.marcado, onCheckedChange = { aoAlternar() })
                OutlinedTextField(
                    value = item.produto, onValueChange = { t -> aoEditar { it.copy(produto = t.take(200)) } },
                    label = { Text("Produto") }, modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = preco, singleLine = true, label = { Text("Preço") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    onValueChange = { t -> preco = t; Dinheiro.parse(t)?.let { v -> aoEditar { it.copy(precoCentavos = v) } } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = normal, singleLine = true, label = { Text("Normal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    onValueChange = { t ->
                        normal = t
                        val v = if (t.isBlank()) null else Dinheiro.parse(t)
                        if (t.isBlank() || v != null) aoEditar { it.copy(precoNormalCentavos = v, precoApp = it.precoApp || v != null) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(checked = item.precoApp, onCheckedChange = { c -> aoEditar { it.copy(precoApp = c) } })
                Text("Preço do app")
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { unidades = true }) { Text("Unidade: ${item.unidade.codigo}") }
                    DropdownMenu(expanded = unidades, onDismissRequest = { unidades = false }) {
                        Unidade.entries.forEach { u ->
                            DropdownMenuItem(text = { Text(u.rotulo) }, onClick = { aoEditar { it.copy(unidade = u) }; unidades = false })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = validade, singleLine = true, label = { Text("Validade") }, placeholder = { Text("dd/mm/aaaa") },
                    onValueChange = { t ->
                        validade = t.take(10)
                        if (t.isBlank()) aoEditar { it.copy(validade = null) }
                        else LeitorJsonEncarte.data(t)?.let { d -> aoEditar { it.copy(validade = d) } }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = item.obs.orEmpty(), label = { Text("OBS") },
                    onValueChange = { t -> aoEditar { it.copy(obs = t.take(RegrasEncarte.OBS_MAX).ifBlank { null }) } },
                    modifier = Modifier.weight(1.4f),
                )
            }
            if (item.marcado && erros.isNotEmpty()) {
                Text(erros.joinToString(" "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Confirmacao(estado: EstadoEncartes, vm: EncartesAdminViewModel) {
    val lote = estado.lote
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        if (lote == null) {
            Text(RegrasEncarte.resumo(estado.paraLancar(), estado.loja.ifBlank { "(sem nome)" }), fontWeight = FontWeight.Bold)
            estado.problemas().forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = vm::lancar, enabled = !estado.lancando && estado.problemas().isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (estado.lancando) "Lançando…" else "Lançar preços") }
        } else {
            Text("Preços lançados: ${lote.lancados} de ${lote.recebidos}" +
                if (lote.lancados < lote.recebidos) " (os repetidos foram ignorados)." else ".", fontWeight = FontWeight.Bold)
            Text("Lote ${lote.loteId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::desfazerLote, enabled = !estado.lancando, modifier = Modifier.fillMaxWidth()) {
                Text("Desfazer este lote")
            }
            Button(onClick = vm::novo, modifier = Modifier.fillMaxWidth()) { Text("Ler outro encarte") }
        }
    }
}

/** Imagem: JPEG reduzido (lado maior até 1600 px, qualidade média). PDF: como está, se não for grande demais. */
private fun arquivoDe(contexto: Context, imagens: Imagens, uri: Uri): ArquivoEncarte? {
    val nome = runCatching {
        contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "arquivo"
    val tipo = contexto.contentResolver.getType(uri).orEmpty()
    return if (tipo == "application/pdf") {
        runCatching { contexto.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?.takeIf { it.size <= MAX_PDF_BYTES }?.let { ArquivoEncarte(nome, it, "application/pdf") }
    } else {
        imagens.jpegReduzido(uri, ladoMaximo = 1600, qualidade = 70)?.let { ArquivoEncarte(nome, it, "image/jpeg") }
    }
}
