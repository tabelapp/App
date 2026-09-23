package br.com.tabelapp.ui.pdv

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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.RegrasCota
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.Validade
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Área do PDV aprovado: saldo de operações e tabela de preços oficial. */
@Composable
fun PainelPdv(container: AppContainer, pdv: MeuPdv) {
    val vm: PainelPdvViewModel = viewModel(key = "painel-${pdv.id}") { PainelPdvViewModel(container.pdvs, pdv) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    var excluindo by remember { mutableStateOf<PrecoPdv?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pdv.nomeFantasia, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                estado.loja?.let { Text(it.endereco, style = MaterialTheme.typography.bodySmall) }
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
        item { estado.cota?.let { CartaoCota(it) } }
        item {
            Button(onClick = vm::novoItem, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Adicionar produto")
            }
        }
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
        item {
            Text(
                "Planilha de preços e promoções: em breve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
private fun CartaoCota(cota: SaldoCota) {
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
