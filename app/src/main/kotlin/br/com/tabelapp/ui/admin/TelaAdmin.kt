package br.com.tabelapp.ui.admin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.tema.coresBarraTopo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EstadoAdmin(
    val carregando: Boolean = true,
    val pendentes: List<PdvPendente> = emptyList(),
    val alvaras: Map<String, ImageBitmap> = emptyMap(),
    val ocupado: String? = null,
    val erro: String? = null,
)

/** Fila de cadastros de PDV esperando análise (só para o Admin). */
class AdminViewModel(private val repositorio: PdvRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoAdmin())
    val estado: StateFlow<EstadoAdmin> = _estado.asStateFlow()

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val lista = repositorio.pendentes()
                _estado.update { it.copy(carregando = false, pendentes = lista) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message) }
            }
        }
    }

    fun verAlvara(pdv: PdvPendente) {
        val caminho = pdv.alvaraPath ?: return
        _estado.update { it.copy(ocupado = pdv.id, erro = null) }
        viewModelScope.launch {
            try {
                val bytes = repositorio.fotoAlvara(caminho)
                val imagem = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } ?: throw ErroAmigavel("Não consegui abrir a foto do alvará.")
                _estado.update { it.copy(ocupado = null, alvaras = it.alvaras + (pdv.id to imagem)) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }

    fun aprovar(pdv: PdvPendente) = decidir(pdv) { repositorio.aprovar(pdv.id) }

    fun rejeitar(pdv: PdvPendente, motivo: String) = decidir(pdv) { repositorio.rejeitar(pdv.id, motivo) }

    private fun decidir(pdv: PdvPendente, acao: suspend () -> Unit) {
        _estado.update { it.copy(ocupado = pdv.id, erro = null) }
        viewModelScope.launch {
            try {
                acao()
                _estado.update { e -> e.copy(ocupado = null, pendentes = e.pendentes.filter { it.id != pdv.id }) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaAdmin(container: AppContainer, usuario: Usuario) {
    val vm: AdminViewModel = viewModel(key = "admin-${usuario.id}") { AdminViewModel(container.pdvs) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    var rejeitando by remember { mutableStateOf<PdvPendente?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Admin — cadastros", fontWeight = FontWeight.Bold) }, colors = coresBarraTopo()) },
    ) { margens ->
        Box(Modifier.padding(margens).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    estado.carregando -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    estado.pendentes.isEmpty() -> Text("Nenhum cadastro de estabelecimento esperando análise.")
                    else -> Text("${estado.pendentes.size} cadastro(s) esperando análise", fontWeight = FontWeight.Bold)
                }
                estado.pendentes.forEach { pdv ->
                    CartaoPendente(
                        pdv = pdv,
                        alvara = estado.alvaras[pdv.id],
                        ocupado = estado.ocupado == pdv.id,
                        aoVerAlvara = { vm.verAlvara(pdv) },
                        aoAprovar = { vm.aprovar(pdv) },
                        aoRejeitar = { rejeitando = pdv },
                    )
                }
                OutlinedButton(onClick = vm::carregar, modifier = Modifier.fillMaxWidth()) { Text("Atualizar") }
            }
        }
    }

    rejeitando?.let { pdv ->
        var motivo by remember(pdv.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejeitando = null },
            title = { Text("Rejeitar ${pdv.nomeFantasia}?") },
            text = {
                OutlinedTextField(
                    value = motivo, onValueChange = { motivo = it.take(300) },
                    label = { Text("Motivo (o dono vai ver)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rejeitar(pdv, motivo)
                    rejeitando = null
                }, enabled = motivo.isNotBlank()) { Text("Rejeitar") }
            },
            dismissButton = { TextButton(onClick = { rejeitando = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CartaoPendente(
    pdv: PdvPendente,
    alvara: ImageBitmap?,
    ocupado: Boolean,
    aoVerAlvara: () -> Unit,
    aoAprovar: () -> Unit,
    aoRejeitar: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pdv.nomeFantasia, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("CNPJ ${Cnpj.formatar(pdv.cnpj)}")
            pdv.razaoSocial?.let { Text("Razão social informada: $it", style = MaterialTheme.typography.bodySmall) }
            pdv.endereco?.let { Text("Endereço informado: $it", style = MaterialTheme.typography.bodySmall) }
            pdv.telefone?.let { Text("Telefone: $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Enviado por: ${listOfNotNull(pdv.donoNome, pdv.donoEmail).joinToString(" — ").ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Receita Federal", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            if (pdv.situacaoReceita == null && pdv.razaoSocialReceita == null) {
                Text("Consulta não feita (Receita fora do ar no envio). Confira o CNPJ.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Situação: ${pdv.situacaoReceita ?: "—"}", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                pdv.razaoSocialReceita?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                pdv.enderecoReceita?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                if (pdv.cnpjConferidoNoAlvara) "✓ O app achou o CNPJ na foto do alvará"
                else "✗ O app não achou o CNPJ na foto do alvará — confira com atenção",
                color = if (pdv.cnpjConferidoNoAlvara) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (alvara != null) {
                Image(
                    bitmap = alvara, contentDescription = "Alvará",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                )
            } else if (pdv.alvaraPath != null) {
                OutlinedButton(onClick = aoVerAlvara, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver foto do alvará")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = aoRejeitar, enabled = !ocupado, modifier = Modifier.weight(1f)) { Text("Rejeitar") }
                Button(onClick = aoAprovar, enabled = !ocupado && alvara != null, modifier = Modifier.weight(1f)) {
                    if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Aprovar")
                }
            }
            if (alvara == null) {
                Text("Veja o alvará antes de aprovar.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
