package br.com.tabelapp.ui.encarte

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.RascunhoEncarte
import br.com.tabelapp.core.StatusEncarte
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.Usuario
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Aba "Encarte": fotos do encarte -> leitura automática (OCR no celular) ->
 * resumo com os produtos e preços lidos -> o usuário confirma -> direto na busca.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaEnviarEncarte(container: AppContainer, usuario: Usuario) {
    val vm: EnviarEncarteViewModel = viewModel(key = "encarte-${usuario.id}") {
        EnviarEncarteViewModel(container.encartes, container.imagens, container.leitorTexto)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Enviar encarte", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { margens ->
        Box(Modifier.padding(margens).fillMaxSize()) {
            when (estado.etapa) {
                EtapaEncarte.FOTOS -> EtapaFotos(estado, vm)
                EtapaEncarte.CONFIRMACAO -> EtapaConfirmacao(estado, vm)
                EtapaEncarte.PUBLICADO -> EncartePublicado(estado, vm)
            }
        }
    }
}

@Composable
private fun EtapaFotos(estado: EstadoEncarte, vm: EnviarEncarteViewModel) {
    val contexto = LocalContext.current
    var uriFoto by rememberSaveable { mutableStateOf<Uri?>(null) }

    val tirarFoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) uriFoto?.let { vm.adicionarFotos(listOf(it)) }
    }
    val escolherDaGaleria = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RascunhoEncarte.MAX_FOTOS)
    ) { uris -> vm.adicionarFotos(uris) }

    fun abrirCamera() {
        val pasta = File(contexto.cacheDir, "fotos").apply { mkdirs() }
        val arquivo = File.createTempFile("encarte_", ".jpg", pasta)
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
        uriFoto = uri
        tirarFoto.launch(uri)
    }

    val podeAdicionar = estado.fotos.size < RascunhoEncarte.MAX_FOTOS && !estado.lendoFotos

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Viu um encarte ou cartaz de ofertas? Fotografe: o app lê os produtos e preços, você confere " +
                "e eles já aparecem na busca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Dica: fotografe de perto, com boa luz e o encarte reto — uma página ou um trecho por foto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Fotos do encarte (até ${RascunhoEncarte.MAX_FOTOS})", fontWeight = FontWeight.Bold)
        if (estado.fotos.isNotEmpty() || estado.lendoFotos) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                estado.fotos.forEachIndexed { i, foto ->
                    Box {
                        Image(
                            bitmap = foto.miniatura, contentDescription = "Foto ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
                        ) {
                            IconButton(onClick = { vm.removerFoto(i) }, enabled = !estado.lendoFotos) {
                                Icon(Icons.Default.Close, contentDescription = "Remover foto", Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (estado.lendoFotos) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::abrirCamera, enabled = podeAdicionar, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Tirar foto")
            }
            OutlinedButton(
                onClick = {
                    escolherDaGaleria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = podeAdicionar,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Galeria")
            }
        }

        estado.erros.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = { vm.lerEncarte() },
            enabled = estado.fotos.isNotEmpty() && !estado.lendoFotos,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (estado.lendoFotos) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Lendo as fotos…")
            } else {
                Icon(Icons.Default.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ler encarte")
            }
        }

        if (estado.meusEncartes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MeusEncartes(estado.meusEncartes)
        }
    }
}

/** Resumo do que foi lido. O usuário não digita produto nem preço: só desmarca o que estiver errado. */
@Composable
private fun EtapaConfirmacao(estado: EstadoEncarte, vm: EnviarEncarteViewModel) {
    if (estado.itens.isEmpty()) {
        NadaLido(vm)
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Confira o que foi lido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "${estado.itens.size} produto(s) encontrado(s). Desmarque o que estiver errado — só os marcados " +
                "vão para a busca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                estado.itens.forEachIndexed { i, item ->
                    val marcado = i !in estado.desmarcados
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.alternarItem(i) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = marcado, onCheckedChange = { vm.alternarItem(i) })
                        Text(
                            item.produto,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (marcado) null else TextDecoration.LineThrough,
                            color = if (marcado) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            Dinheiro.formatar(item.precoCentavos),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                            color = if (marcado) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        Text("De qual estabelecimento é?", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = estado.busca, onValueChange = vm::alterarBusca,
            label = { Text("Nome do estabelecimento") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (estado.lojaEscolhida == null && estado.sugestoes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Cadastrados no Tabelapp:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    )
                    estado.sugestoes.forEach { loja ->
                        Column(
                            Modifier.fillMaxWidth().clickable { vm.escolherLoja(loja) }.padding(12.dp)
                        ) {
                            Text(loja.titulo, fontWeight = FontWeight.SemiBold)
                            Text(loja.endereco, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        val loja = estado.lojaEscolhida
        if (loja != null) {
            Text("✓ ${loja.endereco}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else if (estado.busca.isNotBlank()) {
            OutlinedTextField(
                value = estado.pdvEndereco, onValueChange = vm::alterarEndereco,
                label = { Text("Endereço ou bairro (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text("Até quando valem as ofertas?", fontWeight = FontWeight.Bold)
        CampoValidade(estado.validade, estado.validadeLida, aoEscolher = vm::alterarValidade)

        estado.erros.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

        val n = estado.selecionados.size
        Button(
            onClick = vm::publicar,
            enabled = !estado.enviando && n > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (estado.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (n == 1) "Confirmar e publicar 1 preço" else "Confirmar e publicar $n preços")
        }
        OutlinedButton(onClick = vm::voltarAsFotos, enabled = !estado.enviando, modifier = Modifier.fillMaxWidth()) {
            Text("Voltar às fotos")
        }
    }
}

@Composable
private fun NadaLido(vm: EnviarEncarteViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Não encontrei produtos com preço nestas fotos.", fontWeight = FontWeight.Bold)
                Text(
                    "Tente fotografar mais de perto, com boa luz e o encarte reto — de preferência um " +
                        "trecho do encarte por foto, com o nome e o preço dos produtos bem legíveis.",
                )
            }
        }
        Button(onClick = vm::voltarAsFotos, modifier = Modifier.fillMaxWidth()) { Text("Voltar às fotos") }
    }
}

/** Validade: a lida do encarte (se achou) ou a que o usuário escolher no calendário. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampoValidade(data: LocalDate?, lida: Boolean, aoEscolher: (LocalDate) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { aberto = true }, modifier = Modifier.fillMaxWidth()) {
        Text(data?.let { "Válido até ${Validade.formatar(it)}" } ?: "Informar a data impressa no encarte")
    }
    Text(
        when {
            data == null -> "Não achei a data no encarte. Toque acima e escolha a data impressa nele."
            lida -> "Data lida do encarte. Se estiver errada, toque para corrigir."
            else -> "Os preços saem da busca depois dessa data."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (data == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (aberto) {
        val hoje = LocalDate.now()
        val limite = hoje.plusDays(Validade.MAXIMO_DIAS)
        // O DatePicker trabalha com meia-noite UTC do dia escolhido.
        val estado = rememberDatePickerState(
            initialSelectedDateMillis = (data ?: hoje).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val dia = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !dia.isBefore(hoje) && !dia.isAfter(limite)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { aberto = false },
            confirmButton = {
                TextButton(onClick = {
                    estado.selectedDateMillis?.let {
                        aoEscolher(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    aberto = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { aberto = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = estado)
        }
    }
}

@Composable
private fun MeusEncartes(encartes: List<EncarteEnviado>) {
    Text("Meus encartes", fontWeight = FontWeight.Bold)
    encartes.forEach { e ->
        val cor = when (e.status) {
            StatusEncarte.PENDENTE -> MaterialTheme.colorScheme.secondary
            StatusEncarte.APROVADO -> MaterialTheme.colorScheme.primary
            StatusEncarte.REJEITADO -> MaterialTheme.colorScheme.error
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(e.pdvNome, fontWeight = FontWeight.SemiBold)
            val detalhes = listOfNotNull(
                e.status.rotulo,
                e.itens.takeIf { it > 0 }?.let { if (it == 1) "1 preço" else "$it preços" },
                e.validade?.let { "válido até ${Validade.formatar(it)}" },
            )
            Text(detalhes.joinToString(" · "), color = cor, style = MaterialTheme.typography.bodySmall)
            e.motivoRejeicao?.let { Text("Motivo: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EncartePublicado(estado: EstadoEncarte, vm: EnviarEncarteViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.CheckCircle, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp),
        )
        Text(
            if (estado.publicados == 1) "1 preço publicado!" else "${estado.publicados} preços publicados!",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        )
        Text(
            "Já aparecem na busca com a marcação \"produto de encarte\". Obrigado por ajudar quem pesquisa!",
            textAlign = TextAlign.Center,
        )
        Button(onClick = vm::novoEncarte, modifier = Modifier.fillMaxWidth()) { Text("Enviar outro encarte") }
    }
}
