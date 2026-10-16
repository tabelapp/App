package br.com.tabelapp.ui.pdv

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.R
import br.com.tabelapp.core.EstadoItem
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PrecoExibido
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.TipoEmpresa
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.core.Validade
import br.com.tabelapp.ui.tema.CoresTabelapp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Área do PDV aprovado: cadastro, saldo de operações, tabela de preços oficial e promoções. */
@Composable
fun PainelPdv(container: AppContainer, pdv: MeuPdv) {
    val vm: PainelPdvViewModel = viewModel(key = "painel-${pdv.id}") {
        PainelPdvViewModel(container.pdvs, pdv, container.geocodificador, container.localizacao)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val anunciante = pdv.tipo == TipoEmpresa.ANUNCIANTE
    var excluindo by remember { mutableStateOf<PrecoPdv?>(null) }
    var excluindoPromocao by remember { mutableStateOf<PromocaoPdv?>(null) }
    var editandoCadastro by remember { mutableStateOf(false) }
    var editandoPromocao by remember { mutableStateOf<PromocaoPdv?>(null) }
    var confirmandoLocal by remember { mutableStateOf(false) }
    val pedirLocalizacao = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.usarLocalizacaoDoCelular()
    }
    val seletorPlanilha = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lerArquivoPlanilha(contexto, uri)?.let { (bytes, nome) -> vm.abrirPlanilha(bytes, nome) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(estado.nomeFantasia, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    if (estado.loja != null) {
                        TextButton(onClick = { editandoCadastro = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Editar dados")
                        }
                    }
                }
                if (anunciante) {
                    Text(stringResource(R.string.anunciante_painel), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    estado.site?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                estado.loja?.let { l ->
                    Text(l.endereco, style = MaterialTheme.typography.bodySmall)
                    val contatos = listOfNotNull(l.telefone?.let { "Tel. $it" }, l.whatsapp?.let { "WhatsApp $it" }, estado.site)
                    if (contatos.isNotEmpty()) {
                        Text(contatos.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SituacaoNoMapa(
                        loja = l,
                        localizando = l.id in estado.localizando,
                        naoAchada = l.id in estado.naoAchadas,
                        aoEstouNaLoja = { confirmandoLocal = true },
                    )
                }
                if (!pdv.modoRede && estado.lojas.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        estado.lojas.forEach { l ->
                            FilterChip(
                                selected = l.id == estado.lojaId,
                                onClick = { vm.escolherLoja(l.id) },
                                label = { Text(l.nome ?: l.endereco.substringBefore(',')) },
                            )
                        }
                    }
                }
            }
        }
        // Anunciante (sem ponto de venda): não tem tabela de preços, só promoções.
        if (!anunciante) {
            item { estado.catalogo?.let { CartaoCatalogo(it) } }
            item {
                Button(onClick = vm::novoItem, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar produto")
                }
            }
            item { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { seletorPlanilha.launch(TIPOS_PLANILHA) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Enviar planilha")
                    }
                    OutlinedButton(onClick = { compartilharModeloPlanilha(contexto) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Modelo")
                    }
                }
                Text(
                    "Planilha Excel (.xlsx) ou .csv com as colunas Produto, Preço, Validade e OBS. " +
                        "Antes de publicar, o app mostra o que entra no seu catálogo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } }
        }
        estado.mensagem?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        estado.erro?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
        if (!anunciante) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Minha tabela de preços (${estado.precos.size})",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (estado.carregando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (estado.precos.size > 8) {
                item {
                    OutlinedTextField(
                        value = estado.filtro, onValueChange = vm::alterarFiltro,
                        placeholder = { Text("Procurar na minha tabela") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (!estado.carregando && estado.precos.isEmpty()) {
                item {
                    Text(
                        "Sua tabela está vazia. Toque em \"Adicionar produto\" para publicar o primeiro preço — " +
                            "ele aparece na busca como Preço oficial.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(estado.precosFiltrados, key = { it.id }) { p ->
                LinhaPreco(
                    p, aoEditar = { vm.editar(p) }, aoExcluir = { excluindo = p },
                    aoPausar = { pausar -> vm.pausarItem(p, pausar) },
                )
            }
        }
        item { Column {
            if (!anunciante) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.promocoes_titulo), fontWeight = FontWeight.Bold)
            Text(
                stringResource(if (estado.promocoes.isEmpty()) R.string.promocoes_vazio else R.string.promocoes_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }
        items(estado.promocoes, key = { "promo-" + it.id }) { p ->
            CartaoPromocao(p, aoEditar = { editandoPromocao = p }, aoExcluir = { excluindoPromocao = p })
        }
    }

    if (confirmandoLocal) {
        AlertDialog(
            onDismissRequest = { confirmandoLocal = false },
            title = { Text(stringResource(R.string.loja_mapa_estou_titulo)) },
            text = { Text(stringResource(R.string.loja_mapa_estou_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmandoLocal = false
                    if (container.localizacao.temPermissao()) vm.usarLocalizacaoDoCelular()
                    else pedirLocalizacao.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }) { Text(stringResource(R.string.loja_mapa_estou_confirmar)) }
            },
            dismissButton = { TextButton(onClick = { confirmandoLocal = false }) { Text("Cancelar") } },
        )
    }

    if (editandoCadastro) {
        estado.loja?.let { loja ->
            DialogoCadastro(
                nomeAtual = estado.nomeFantasia,
                siteAtual = estado.site,
                loja = loja,
                aoSalvar = { nome, site, l -> vm.salvarCadastro(nome, site, l) { editandoCadastro = false } },
                aoFechar = { editandoCadastro = false },
            )
        }
    }

    editandoPromocao?.let { promo ->
        DialogoPromocao(
            imagens = container.imagens,
            existente = promo,
            aoFechar = { editandoPromocao = null },
            aoEditar = { titulo, descricao, link, arte, removerArte, palavras, aoErro, aoTerminar ->
                vm.editarPromocao(promo, titulo, descricao, link, arte, removerArte, palavras, aoErro) {
                    aoTerminar()
                    editandoPromocao = null
                }
            },
        )
    }

    estado.planilha?.let { p ->
        DialogoPlanilha(
            estado = p,
            aoImportar = vm::importarPlanilha,
            aoFechar = vm::fecharPlanilha,
        )
    }

    estado.resultadoImportacao?.let { r ->
        DialogoResultadoImportacao(r, aoFechar = vm::fecharResultadoImportacao)
    }

    // Chegou ao limite do plano: mensagem calma, sem culpa.
    if (estado.mostrarLimite) {
        estado.catalogo?.let { c ->
            DialogoLimite(c, aoFechar = vm::fecharLimite)
        }
    }

    excluindoPromocao?.let { p ->
        AlertDialog(
            onDismissRequest = { excluindoPromocao = null },
            title = { Text("Excluir a promoção \"${p.titulo}\"?") },
            text = {
                Text(stringResource(R.string.promocao_excluir_texto))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.excluirPromocao(p)
                    excluindoPromocao = null
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { excluindoPromocao = null }) { Text("Cancelar") } },
        )
    }

    estado.rascunho?.let { r ->
        EditorPreco(
            rascunho = r,
            novo = estado.editandoId == null,
            itemNovo = estado.rascunhoEhNovo,
            erros = estado.errosEditor,
            salvando = estado.salvando,
            aoMudar = vm::alterarRascunho,
            aoSalvar = vm::salvar,
            aoFechar = vm::fecharEditor,
        )
    }

    excluindo?.let { p ->
        AlertDialog(
            onDismissRequest = { excluindo = null },
            title = { Text("Excluir ${p.produto}?") },
            text = { Text("O preço sai da busca e libera um lugar no seu catálogo.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.excluir(p)
                    excluindo = null
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { excluindo = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CartaoPromocao(p: PromocaoPdv, aoEditar: () -> Unit, aoExcluir: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.titulo, fontWeight = FontWeight.SemiBold)
                    Text(
                        p.rotuloStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (p.status) {
                            "ativa" -> MaterialTheme.colorScheme.primary
                            "aguardando_pagamento" -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = aoEditar) { Icon(Icons.Default.Edit, contentDescription = "Editar promoção") }
                IconButton(onClick = aoExcluir) { Icon(Icons.Default.Delete, contentDescription = "Excluir promoção") }
            }
            if (p.visualizacoesContratadas > 0) {
                Text("Visualizações: ${p.visualizacoesExibidas} de ${p.visualizacoesContratadas}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (p.palavrasChave.isNotEmpty()) {
                Text("Palavras: ${p.palavrasChave.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LinhaPreco(p: PrecoPdv, aoEditar: () -> Unit, aoExcluir: () -> Unit, aoPausar: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = aoEditar).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.produto, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(stringResource(R.string.pdv_oferta_valida_ate, Validade.formatar(p.validade)), p.obs).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Situação do item (fora da busca, mas guardado e editável).
                val estado = when (p.estado) {
                    EstadoItem.PAUSADO -> R.string.item_estado_pausado
                    EstadoItem.PAUSADO_LIMITE -> R.string.item_estado_pausado_limite
                    EstadoItem.VENCIDO -> R.string.item_estado_vencido
                    EstadoItem.ATIVO -> null
                }
                estado?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(PrecoExibido.texto(p.precoCentavos, p.unidade, p.aPartirDe), fontWeight = FontWeight.Bold,
                color = CoresTabelapp.Preco)
            // Pausar e reativar são grátis (reativar só até o limite do plano).
            if (p.estado != EstadoItem.VENCIDO) {
                val pausado = p.estado == EstadoItem.PAUSADO
                IconButton(onClick = { aoPausar(!pausado) }) {
                    Icon(
                        if (pausado) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = stringResource(if (pausado) R.string.item_reativar else R.string.item_pausar),
                    )
                }
            }
            IconButton(onClick = aoExcluir) { Icon(Icons.Default.Delete, contentDescription = "Excluir") }
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorPreco(
    rascunho: RascunhoPreco,
    novo: Boolean,
    itemNovo: Boolean?,
    erros: List<String>,
    salvando: Boolean,
    aoMudar: (RascunhoPreco) -> Unit,
    aoSalvar: () -> Unit,
    aoFechar: () -> Unit,
) {
    var escolhendoData by remember { mutableStateOf(false) }
    var escolhendoUnidade by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!salvando) aoFechar() },
        title = { Text(if (novo) "Adicionar produto" else "Editar preço") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rascunho.produto,
                    onValueChange = { aoMudar(rascunho.copy(produto = it.take(200))) },
                    label = { Text("Produto (ex.: Arroz Tio João 5kg)") },
                    // Mudar o nome equivale a outro produto: para renomear, exclua e crie de novo.
                    enabled = novo,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rascunho.preco,
                    onValueChange = { aoMudar(rascunho.copy(preco = it.filter { c -> c.isDigit() || c in ".," }.take(12))) },
                    label = { Text("Preço (R$)") },
                    placeholder = { Text("0,00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Unidade do preço (kg, hora, visita...) e "a partir de" para serviços.
                Box {
                    OutlinedButton(onClick = { escolhendoUnidade = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(R.string.item_unidade)}: ${rascunho.unidade.rotulo}")
                    }
                    DropdownMenu(expanded = escolhendoUnidade, onDismissRequest = { escolhendoUnidade = false }) {
                        Unidade.entries.forEach { u ->
                            DropdownMenuItem(
                                text = { Text(u.rotulo) },
                                onClick = {
                                    aoMudar(rascunho.copy(unidade = u))
                                    escolhendoUnidade = false
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { aoMudar(rascunho.copy(aPartirDe = !rascunho.aPartirDe)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rascunho.aPartirDe, onCheckedChange = { aoMudar(rascunho.copy(aPartirDe = it)) })
                    Text(stringResource(R.string.item_a_partir_de))
                }
                rascunho.precoCentavos?.let {
                    Text(PrecoExibido.texto(it, rascunho.unidade, rascunho.aPartirDe),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
                }
                OutlinedButton(onClick = { escolhendoData = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(rascunho.validade?.let { stringResource(R.string.pdv_oferta_valida_ate, Validade.formatar(it)) }
                        ?: "Validade: 30 dias (tocar para mudar)")
                }
                OutlinedTextField(
                    value = rascunho.obs,
                    onValueChange = { aoMudar(rascunho.copy(obs = it.take(140))) },
                    label = { Text("OBS (opcional, ex.: cerveja gelada)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (itemNovo) {
                    true -> Text(stringResource(R.string.item_novo_no_catalogo), style = MaterialTheme.typography.bodySmall)
                    false -> Text(stringResource(R.string.item_atualizacao_livre), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    null -> {}
                }
                erros.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = aoSalvar, enabled = !salvando) {
                if (salvando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Salvar")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !salvando) { Text("Cancelar") } },
    )

    if (escolhendoData) {
        val hoje = LocalDate.now()
        val limite = hoje.plusDays(Validade.MAXIMO_DIAS)
        // O DatePicker trabalha com meia-noite UTC do dia escolhido.
        val estadoData = rememberDatePickerState(
            initialSelectedDateMillis = (rascunho.validade ?: limite).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val dia = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !dia.isBefore(hoje) && !dia.isAfter(limite)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { escolhendoData = false },
            confirmButton = {
                TextButton(onClick = {
                    estadoData.selectedDateMillis?.let {
                        aoMudar(rascunho.copy(validade = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    escolhendoData = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { escolhendoData = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = estadoData)
        }
    }
}

/** Se a loja já tem pino no mapa; sem pino, o dono pode marcar pela localização do celular. */
@Composable
private fun SituacaoNoMapa(loja: LojaPdv, localizando: Boolean, naoAchada: Boolean, aoEstouNaLoja: () -> Unit) {
    when {
        loja.local != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(if (loja.localPeloAparelho) R.string.loja_mapa_ok_aparelho else R.string.loja_mapa_ok),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = aoEstouNaLoja) {
                Text(stringResource(R.string.loja_mapa_ajustar), style = MaterialTheme.typography.labelMedium)
            }
        }
        localizando -> Text(stringResource(R.string.loja_mapa_localizando), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Column {
            Text(
                stringResource(if (naoAchada) R.string.loja_mapa_nao_achou else R.string.loja_mapa_sem_pino),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = aoEstouNaLoja, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.loja_mapa_estou_confirmar))
            }
        }
    }
}
