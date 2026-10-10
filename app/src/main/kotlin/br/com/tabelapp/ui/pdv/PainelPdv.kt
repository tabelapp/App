package br.com.tabelapp.ui.pdv

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.RegrasCota
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.Validade
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Área do PDV aprovado: cadastro, saldo de operações, tabela de preços oficial e promoções. */
@Composable
fun PainelPdv(container: AppContainer, pdv: MeuPdv) {
    val vm: PainelPdvViewModel = viewModel(key = "painel-${pdv.id}") { PainelPdvViewModel(container.pdvs, pdv) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    var excluindo by remember { mutableStateOf<PrecoPdv?>(null) }
    var excluindoPromocao by remember { mutableStateOf<PromocaoPdv?>(null) }
    var editandoCadastro by remember { mutableStateOf(false) }
    var criandoPromocao by remember { mutableStateOf(false) }
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
                estado.loja?.let { l ->
                    Text(l.endereco, style = MaterialTheme.typography.bodySmall)
                    val contatos = listOfNotNull(l.telefone?.let { "Tel. $it" }, l.whatsapp?.let { "WhatsApp $it" }, estado.site)
                    if (contatos.isNotEmpty()) {
                        Text(contatos.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
        item { estado.cota?.let { CartaoCota(it, aoComprar = vm::comprarPacote) } }
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
                    "Antes de publicar, o app mostra quantas operações vai usar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } }
        estado.mensagem?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        estado.erro?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
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
            LinhaPreco(p, aoEditar = { vm.editar(p) }, aoExcluir = { excluindo = p })
        }
        item { Column {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Promoções (banner na busca)", fontWeight = FontWeight.Bold)
            Text(
                "Seu banner aparece no topo da busca para quem procura as palavras escolhidas. " +
                    "Pacotes de 100, 250 ou 500 visualizações.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }
        item {
            Button(onClick = { criandoPromocao = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Campaign, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Criar promoção")
            }
        }
        items(estado.promocoes, key = { "promo-" + it.id }) { p ->
            CartaoPromocao(p, aoPagar = { vm.pagarPromocao(p) }, aoExcluir = { excluindoPromocao = p })
        }
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

    if (criandoPromocao) {
        DialogoPromocao(
            imagens = container.imagens,
            aoCriar = { titulo, descricao, link, arte, palavras, vis, aoErro, aoTerminar ->
                vm.criarPromocao(titulo, descricao, link, arte, palavras, vis, aoErro) {
                    aoTerminar()
                    criandoPromocao = false
                }
            },
            aoFechar = { criandoPromocao = false },
        )
    }

    estado.planilha?.let { p ->
        DialogoPlanilha(
            estado = p,
            aoImportar = vm::importarPlanilha,
            aoComprarPacote = vm::comprarPacote,
            aoConferirDeNovo = vm::refazerSimulacao,
            aoFechar = vm::fecharPlanilha,
        )
    }

    estado.pagamentoCriado?.let { pg ->
        DialogoPagamento(pdvNome = estado.nomeFantasia, pagamento = pg, aoFechar = vm::fecharPagamento)
    }

    excluindoPromocao?.let { p ->
        AlertDialog(
            onDismissRequest = { excluindoPromocao = null },
            title = { Text("Excluir a promoção \"${p.titulo}\"?") },
            text = {
                Text(
                    if (p.status == "aguardando_pagamento") "O pedido de pagamento também é cancelado."
                    else "O banner sai do ar. Visualizações não usadas não são devolvidas."
                )
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
            custo = estado.custoDoRascunho?.contaNaCota,
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
            text = { Text("O preço sai da busca. Excluir não gasta operação.") },
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
private fun CartaoPromocao(p: PromocaoPdv, aoPagar: () -> Unit, aoExcluir: () -> Unit) {
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
                            "aguardando_pagamento" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
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
            if (p.pagamentoPendenteId != null) {
                TextButton(onClick = aoPagar) {
                    Text("Pagar ${p.valorPendenteCentavos?.let { Dinheiro.formatar(it) }.orEmpty()}".trim())
                }
            }
        }
    }
}

@Composable
private fun CartaoCota(cota: SaldoCota, aoComprar: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Operações disponíveis: ${cota.restantes}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text("Grátis este mês: ${cota.gratisRestantes} de ${RegrasCota.GRATIS_POR_MES}" +
                if (cota.saldoPacotes > 0) " · pacotes: ${cota.saldoPacotes}" else "")
            Text(
                "Criar produto ou aumentar preço usa 1 operação. Baixar preço, mudar OBS/validade e excluir são grátis.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = aoComprar, modifier = Modifier.fillMaxWidth()) {
                Text("Comprar +50 operações (R$ 10)")
            }
        }
    }
}

@Composable
private fun LinhaPreco(p: PrecoPdv, aoEditar: () -> Unit, aoExcluir: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = aoEditar).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.produto, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull("Válido até ${Validade.formatar(p.validade)}", p.obs).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Dinheiro.formatar(p.precoCentavos), fontWeight = FontWeight.Bold)
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
    custo: Boolean?,
    erros: List<String>,
    salvando: Boolean,
    aoMudar: (RascunhoPreco) -> Unit,
    aoSalvar: () -> Unit,
    aoFechar: () -> Unit,
) {
    var escolhendoData by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!salvando) aoFechar() },
        title = { Text(if (novo) "Adicionar produto" else "Editar preço") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                OutlinedButton(onClick = { escolhendoData = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(rascunho.validade?.let { "Válido até ${Validade.formatar(it)}" } ?: "Validade: 30 dias (tocar para mudar)")
                }
                OutlinedTextField(
                    value = rascunho.obs,
                    onValueChange = { aoMudar(rascunho.copy(obs = it.take(140))) },
                    label = { Text("OBS (opcional, ex.: cerveja gelada)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (custo) {
                    true -> Text("Vai usar 1 operação.", fontWeight = FontWeight.SemiBold)
                    false -> Text("Grátis (não usa operação).", fontWeight = FontWeight.SemiBold,
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
