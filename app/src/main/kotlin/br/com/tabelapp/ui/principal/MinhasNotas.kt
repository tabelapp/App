package br.com.tabelapp.ui.principal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.R
import br.com.tabelapp.core.MinhaNota
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.ui.tema.CoresTabelapp
import br.com.tabelapp.ui.tema.coresBarraTopo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoMinhasNotas(
    val carregando: Boolean = true,
    val notas: List<MinhaNota> = emptyList(),
    val erro: String? = null,
)

/** "Minhas notas": só consulta das notas que o próprio usuário enviou. */
class MinhasNotasViewModel(private val repositorio: NotaFiscalRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoMinhasNotas())
    val estado: StateFlow<EstadoMinhasNotas> = _estado.asStateFlow()

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val notas = repositorio.minhasNotas()
                _estado.update { it.copy(carregando = false, notas = notas) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaMinhasNotas(repositorio: NotaFiscalRepositorio, aoFechar: () -> Unit) {
    val vm: MinhasNotasViewModel = viewModel(key = "minhas-notas") { MinhasNotasViewModel(repositorio) }
    val estado by vm.estado.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding(),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.minhas_notas_titulo)) },
                        navigationIcon = {
                            IconButton(onClick = aoFechar) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.loja_voltar))
                            }
                        },
                        colors = coresBarraTopo(),
                    )
                },
            ) { margens ->
                when {
                    estado.carregando -> Box(Modifier.padding(margens).fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    estado.erro != null -> Column(
                        Modifier.padding(margens).fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(estado.erro.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = vm::carregar) { Text(stringResource(R.string.minhas_notas_tentar)) }
                    }
                    else -> LazyColumn(
                        Modifier.padding(margens).fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(stringResource(R.string.minhas_notas_texto), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (estado.notas.isEmpty()) {
                            item {
                                Text(stringResource(R.string.minhas_notas_vazio), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp))
                            }
                        }
                        items(estado.notas, key = { it.id }) { CartaoNota(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartaoNota(n: MinhaNota) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CoresTabelapp.Cartao, contentColor = CoresTabelapp.TextoPrincipal),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(n.pdvNome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            n.endereco?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${n.dataTexto} · ${n.quantidadeTexto}", style = MaterialTheme.typography.bodySmall)
            Text(
                n.situacao, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = if (n.naBusca) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            n.itens.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        item.produto + if (item.parteDeCombo) " (combo)" else "",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(item.preco, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
                }
            }
        }
    }
}
