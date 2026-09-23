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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.RascunhoEncarte
import br.com.tabelapp.core.StatusEncarte
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.Usuario
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Aba "Encarte": fotos do encarte + estabelecimento + validade -> fila de aprovação do Admin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaEnviarEncarte(container: AppContainer, usuario: Usuario) {
    val vm: EnviarEncarteViewModel = viewModel(key = "encarte-${usuario.id}") {
        EnviarEncarteViewModel(container.encartes, container.imagens)
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
            if (estado.enviado) EncarteEnviadoOk(vm) else FormularioEncarte(estado, vm)
        }
    }
}

@Composable
private fun FormularioEncarte(estado: EstadoEncarte, vm: EnviarEncarteViewModel) {
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

    val podeAdicionar = estado.fotos.size < RascunhoEncarte.MAX_FOTOS && !estado.processandoFotos

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Viu um encarte ou cartaz de ofertas? Fotografe e compartilhe. Depois de conferido pela " +
                "nossa equipe, os preços aparecem na busca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. Fotos
        Text("1. Fotos do encarte (até ${RascunhoEncarte.MAX_FOTOS})", fontWeight = FontWeight.Bold)
        if (estado.fotos.isNotEmpty() || estado.processandoFotos) {
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
                            IconButton(onClick = { vm.removerFoto(i) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remover foto", Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (estado.processandoFotos) {
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

        // 2. Estabelecimento
        Text("2. De qual estabelecimento é?", fontWeight = FontWeight.Bold)
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

        // 3. Validade
        Text("3. Até quando valem as ofertas?", fontWeight = FontWeight.Bold)
        CampoValidade(estado.validade, aoEscolher = vm::alterarValidade)

        OutlinedTextField(
            value = estado.comentario, onValueChange = vm::alterarComentario,
            label = { Text("Comentário (opcional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        estado.erros.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = vm::enviar,
            enabled = !estado.enviando && !estado.processandoFotos,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (estado.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Enviar para aprovação")
        }

        if (estado.meusEncartes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MeusEncartes(estado.meusEncartes)
        }
    }
}

/** Validade opcional: o usuário informa a data impressa no encarte, se houver. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampoValidade(data: LocalDate?, aoEscolher: (LocalDate?) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { aberto = true }, modifier = Modifier.weight(1f)) {
            Text(data?.let { "Válido até ${Validade.formatar(it)}" } ?: "Informar a data do encarte")
        }
        if (data != null) TextButton(onClick = { aoEscolher(null) }) { Text("Limpar") }
    }
    Text(
        if (data == null) "Se o encarte não mostrar a data, deixe em branco: nossa equipe confere."
        else "Os preços saem da busca depois dessa data.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (aberto) {
        // O DatePicker trabalha com meia-noite UTC do dia escolhido.
        val estado = rememberDatePickerState(
            initialSelectedDateMillis = (data ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
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
            Text(e.status.rotulo, color = cor, style = MaterialTheme.typography.bodySmall)
            e.motivoRejeicao?.let { Text("Motivo: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EncarteEnviadoOk(vm: EnviarEncarteViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.CheckCircle, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp),
        )
        Text("Encarte enviado!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Nossa equipe vai conferir as fotos. Assim que aprovado, os preços aparecem na busca " +
                "com a marcação \"produto de encarte\".",
            textAlign = TextAlign.Center,
        )
        Button(onClick = vm::novoEncarte, modifier = Modifier.fillMaxWidth()) { Text("Enviar outro encarte") }
    }
}
