package br.com.tabelapp.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.R
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.OrigemLimite
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PlanoPdvAdmin
import br.com.tabelapp.core.PrecoPlano
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.PdvRepositorio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class EstadoPlanosPdvs(
    val carregando: Boolean = true,
    val busca: String = "",
    val lista: List<PlanoPdvAdmin> = emptyList(),
    /** Planos pagos (essencial, loja, mercado) para escolher. */
    val planosPagos: List<Plano> = emptyList(),
    val ocupado: String? = null,
    val erro: String? = null,
    val mensagem: String? = null,
)

/** Admin: ativa, renova, troca ou cancela o plano de um PDV e cria banners (tudo combinado fora do app). */
class PlanosPdvsViewModel(private val repositorio: PdvRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoPlanosPdvs())
    val estado: StateFlow<EstadoPlanosPdvs> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            val planos = runCatching { repositorio.planos() }.getOrDefault(emptyList()).filter { !it.gratis }
            _estado.update { it.copy(planosPagos = planos) }
        }
        buscar()
    }

    fun alterarBusca(texto: String) = _estado.update { it.copy(busca = texto.take(60)) }

    fun buscar() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val lista = repositorio.planosPdvs(_estado.value.busca)
                _estado.update { it.copy(carregando = false, lista = lista) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message) }
            }
        }
    }

    fun definir(pdv: PlanoPdvAdmin, plano: Plano, validade: LocalDate, observacao: String) = agir(pdv) {
        repositorio.definirPlano(pdv.id, plano.codigo, validade, observacao)
        "${pdv.nomeFantasia}: plano ${plano.nome} até ${Validade.formatar(validade)}."
    }

    fun cancelar(pdv: PlanoPdvAdmin, observacao: String) = agir(pdv) {
        repositorio.cancelarPlano(pdv.id, observacao)
        "${pdv.nomeFantasia}: plano cancelado (voltou ao grátis)."
    }

    fun criarBanner(pdv: PlanoPdvAdmin, titulo: String, descricao: String, link: String, palavras: List<String>, visualizacoes: Int) =
        agir(pdv) {
            repositorio.adminCriarBanner(pdv.id, titulo, descricao.ifBlank { null }, link.ifBlank { null }, palavras, visualizacoes)
            "${pdv.nomeFantasia}: banner \"${titulo.trim()}\" no ar com $visualizacoes visualizações."
        }

    private fun agir(pdv: PlanoPdvAdmin, acao: suspend () -> String) {
        _estado.update { it.copy(ocupado = pdv.id, erro = null, mensagem = null) }
        viewModelScope.launch {
            try {
                val mensagem = acao()
                val lista = repositorio.planosPdvs(_estado.value.busca)
                _estado.update { it.copy(ocupado = null, lista = lista, mensagem = mensagem) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }
}

private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Validade sugerida: renovação soma 30 dias ao fim atual; senão, hoje + 30. */
private fun validadeSugerida(pdv: PlanoPdvAdmin, hoje: LocalDate): LocalDate =
    pdv.planoAte?.takeIf { pdv.plano != Plano.GRATIS && !it.isBefore(hoje) }?.plusDays(30) ?: hoje.plusDays(30)

/** Tela "Planos dos PDVs" (tela cheia, aberta pela aba Admin). */
@Composable
fun TelaPlanosPdvs(repositorio: PdvRepositorio, aoFechar: () -> Unit) {
    val vm: PlanosPdvsViewModel = viewModel(key = "admin-planos") { PlanosPdvsViewModel(repositorio) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    var ativando by remember { mutableStateOf<PlanoPdvAdmin?>(null) }
    var cancelando by remember { mutableStateOf<PlanoPdvAdmin?>(null) }
    var criandoBanner by remember { mutableStateOf<PlanoPdvAdmin?>(null) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_planos_titulo), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.planos_fechar)) }
                }
                Text(stringResource(R.string.admin_planos_texto), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = estado.busca, onValueChange = vm::alterarBusca,
                    label = { Text(stringResource(R.string.admin_estabelecimentos_busca)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.buscar() }),
                    trailingIcon = {
                        IconButton(onClick = vm::buscar) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.admin_estabelecimentos_buscar))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                estado.mensagem?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    estado.carregando -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    estado.lista.isEmpty() -> Text(stringResource(R.string.admin_planos_vazio))
                }
                estado.lista.forEach { pdv ->
                    CartaoPlanoPdv(
                        pdv = pdv,
                        ocupado = estado.ocupado == pdv.id,
                        aoAtivar = { ativando = pdv },
                        aoCancelar = { cancelando = pdv },
                        aoCriarBanner = { criandoBanner = pdv },
                    )
                }
            }
        }
    }

    ativando?.let { pdv ->
        DialogoDefinirPlano(
            pdv = pdv,
            planos = estado.planosPagos,
            aoSalvar = { plano, validade, obs ->
                vm.definir(pdv, plano, validade, obs)
                ativando = null
            },
            aoFechar = { ativando = null },
        )
    }

    criandoBanner?.let { pdv ->
        DialogoCriarBanner(
            pdv = pdv,
            aoSalvar = { titulo, descricao, link, palavras, visualizacoes ->
                vm.criarBanner(pdv, titulo, descricao, link, palavras, visualizacoes)
                criandoBanner = null
            },
            aoFechar = { criandoBanner = null },
        )
    }

    cancelando?.let { pdv ->
        var obs by remember(pdv.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancelando = null },
            title = { Text(stringResource(R.string.admin_plano_cancelar)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pdv.nomeFantasia} — ${pdv.planoNome}")
                    Text(stringResource(R.string.admin_plano_cancelar_texto), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = obs, onValueChange = { obs = it.take(500) },
                        label = { Text(stringResource(R.string.admin_plano_observacao)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.cancelar(pdv, obs)
                    cancelando = null
                }) { Text(stringResource(R.string.admin_plano_cancelar)) }
            },
            dismissButton = { TextButton(onClick = { cancelando = null }) { Text("Voltar") } },
        )
    }
}

@Composable
private fun CartaoPlanoPdv(
    pdv: PlanoPdvAdmin, ocupado: Boolean, aoAtivar: () -> Unit, aoCancelar: () -> Unit, aoCriarBanner: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pdv.nomeFantasia, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("CNPJ ${Cnpj.formatar(pdv.cnpj)} · ${pdv.itensAtivos} itens ativos", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.admin_plano_atual, pdv.planoNome), fontWeight = FontWeight.SemiBold)
            pdv.planoAte?.let { Text(stringResource(R.string.admin_plano_ate, Validade.formatar(it)), style = MaterialTheme.typography.bodySmall) }
            pdv.testeAte?.let { Text(stringResource(R.string.admin_teste_ate, Validade.formatar(it)), style = MaterialTheme.typography.bodySmall) }
            val origem = when (pdv.origem) {
                OrigemLimite.PAGO -> "plano pago"
                OrigemLimite.TESTE -> "teste grátis"
                OrigemLimite.GRATIS -> "grátis"
            } + if (pdv.emTolerancia) ", tolerância" else ""
            Text(stringResource(R.string.admin_plano_vale_hoje, pdv.limite, origem), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (pdv.plano != Plano.GRATIS) {
                    OutlinedButton(onClick = aoCancelar, enabled = !ocupado, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_plano_cancelar))
                    }
                }
                Button(onClick = aoAtivar, enabled = !ocupado, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_plano_ativar))
                }
            }
            OutlinedButton(onClick = aoCriarBanner, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.admin_banner_criar))
            }
        }
    }
}

@Composable
private fun DialogoDefinirPlano(
    pdv: PlanoPdvAdmin,
    planos: List<Plano>,
    aoSalvar: (Plano, LocalDate, String) -> Unit,
    aoFechar: () -> Unit,
) {
    val hoje = LocalDate.now()
    var escolhido by remember(pdv.id) {
        mutableStateOf(planos.firstOrNull { it.codigo == pdv.plano } ?: planos.firstOrNull())
    }
    var data by remember(pdv.id) { mutableStateOf(formatoData.format(validadeSugerida(pdv, hoje))) }
    var obs by remember(pdv.id) { mutableStateOf("") }
    val validade = try {
        LocalDate.parse(data.trim(), formatoData).takeIf { !it.isBefore(hoje) }
    } catch (e: DateTimeParseException) {
        null
    }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(stringResource(R.string.admin_plano_dialogo_titulo, pdv.nomeFantasia)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                planos.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { escolhido = p }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = escolhido == p, onClick = { escolhido = p })
                        Text("${p.nome} — ${stringResource(R.string.plano_limite_itens, p.limiteItens)}" +
                            (p.precoCentavos?.takeIf { it > 0 }?.let { " · ${PrecoPlano.porMes(it)}" } ?: ""))
                    }
                }
                OutlinedTextField(
                    value = data, onValueChange = { data = it.take(10) },
                    label = { Text(stringResource(R.string.admin_plano_validade)) },
                    singleLine = true,
                    isError = validade == null,
                    supportingText = { if (validade == null) Text(stringResource(R.string.admin_plano_data_invalida)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = obs, onValueChange = { obs = it.take(500) },
                    label = { Text(stringResource(R.string.admin_plano_observacao)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { val p = escolhido; if (p != null && validade != null) aoSalvar(p, validade, obs) },
                enabled = escolhido != null && validade != null,
            ) { Text(stringResource(R.string.admin_plano_salvar)) }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

/** Admin: banner já no ar, com as visualizações combinadas fora do app. Imagem: o dono põe em "Meu negócio". */
@Composable
private fun DialogoCriarBanner(
    pdv: PlanoPdvAdmin,
    aoSalvar: (titulo: String, descricao: String, link: String, palavras: List<String>, visualizacoes: Int) -> Unit,
    aoFechar: () -> Unit,
) {
    var titulo by remember { mutableStateOf("") }
    var descricao by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var palavras by remember { mutableStateOf("") }
    var visualizacoes by remember { mutableStateOf("100") }
    var erro by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(stringResource(R.string.admin_banner_titulo, pdv.nomeFantasia)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.admin_banner_texto), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(titulo, { titulo = it.take(80) }, label = { Text("Título") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(descricao, { descricao = it.take(500) }, label = { Text("Texto (opcional)") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(link, { link = it.take(300) }, label = { Text("Link (opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(palavras, { palavras = it.take(300) }, label = { Text("Palavras-chave (vírgula)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(visualizacoes, { visualizacoes = it.filter(Char::isDigit).take(6) },
                    label = { Text("Visualizações") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val vis = visualizacoes.toIntOrNull() ?: 0
                when {
                    titulo.isBlank() -> erro = "Dê um título para o banner."
                    vis < 1 -> erro = "Informe quantas visualizações o banner terá."
                    else -> {
                        val l = link.trim().let { if (it.isNotEmpty() && !it.startsWith("http")) "https://$it" else it }
                        aoSalvar(titulo, descricao, l, palavras.split(',').map { it.trim() }.filter { it.isNotEmpty() }, vis)
                    }
                }
            }) { Text(stringResource(R.string.admin_plano_salvar)) }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}
