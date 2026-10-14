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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.R
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.MapaPrecos
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PdvAdmin
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.PrecoDenunciado
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.core.TipoEmpresa
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.Geocodificador
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.tema.coresBarraTopo
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val nomes: List<NomeSugerido> = emptyList(),
    val pagamentos: List<PagamentoPendente> = emptyList(),
    val denunciados: List<PrecoDenunciado> = emptyList(),
    val estabelecimentos: List<PdvAdmin> = emptyList(),
    val buscaPdv: String = "",
    val buscandoPdv: Boolean = false,
    /** "Localizar lojas sem pino": rodando e o resultado da última vez. */
    val localizandoLojas: Boolean = false,
    val resultadoLocalizacao: String? = null,
)

/** Fila de cadastros de PDV esperando análise (só para o Admin). */
class AdminViewModel(
    private val repositorio: PdvRepositorio,
    private val geocodificador: Geocodificador? = null,
) : ViewModel() {
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
                val nomes = repositorio.nomesSugeridos()
                val pagamentos = repositorio.pagamentosPendentes()
                val denunciados = repositorio.precosDenunciados()
                val estabelecimentos = repositorio.estabelecimentos(_estado.value.buscaPdv)
                _estado.update {
                    it.copy(
                        carregando = false, pendentes = lista, nomes = nomes, pagamentos = pagamentos,
                        denunciados = denunciados, estabelecimentos = estabelecimentos,
                    )
                }
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

    fun decidirNome(n: NomeSugerido, aprovar: Boolean) {
        _estado.update { it.copy(ocupado = n.cnpj + n.nome, erro = null) }
        viewModelScope.launch {
            try {
                repositorio.decidirNome(n.cnpj, n.nome, aprovar)
                _estado.update { e -> e.copy(ocupado = null, nomes = e.nomes.filter { it.cnpj != n.cnpj || !aprovar && it != n }) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }

    /** Confirma (libera o banner/as operações) ou cancela um pedido de pagamento. */
    fun decidirPagamento(p: PagamentoPendente, confirmar: Boolean) {
        _estado.update { it.copy(ocupado = p.id, erro = null) }
        viewModelScope.launch {
            try {
                if (confirmar) repositorio.confirmarPagamento(p.id) else repositorio.cancelarPagamento(p.id)
                _estado.update { e -> e.copy(ocupado = null, pagamentos = e.pagamentos.filter { it.id != p.id }) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }

    /** Preço denunciado: [remover] tira da busca; senão mantém e limpa as denúncias. */
    fun moderarPreco(p: PrecoDenunciado, remover: Boolean) {
        _estado.update { it.copy(ocupado = p.cotacaoId, erro = null) }
        viewModelScope.launch {
            try {
                if (remover) repositorio.removerCotacao(p.cotacaoId) else repositorio.manterCotacao(p.cotacaoId)
                _estado.update { e -> e.copy(ocupado = null, denunciados = e.denunciados.filter { it.cotacaoId != p.cotacaoId }) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(ocupado = null, erro = e.message) }
            }
        }
    }

    /** Localiza no aparelho o endereço de cada loja ainda sem pino e grava a coordenada. */
    fun localizarLojasSemPino() {
        val g = geocodificador ?: return
        _estado.update { it.copy(localizandoLojas = true, resultadoLocalizacao = null, erro = null) }
        viewModelScope.launch {
            try {
                val lojas = repositorio.lojasSemCoordenadas()
                var achadas = 0
                val faltaram = mutableListOf<String>()
                for (loja in lojas) {
                    val ponto = MapaPrecos.enderecoParaGeocoder(loja.endereco)?.let { g.localizar(it) }
                        ?.takeIf { MapaPrecos.coordenadaValida(it) }
                    if (ponto != null) {
                        repositorio.definirCoordenadas(loja.id, ponto)
                        achadas++
                    } else {
                        faltaram += loja.nome ?: loja.endereco
                    }
                }
                val resumo = when {
                    lojas.isEmpty() -> "Todas as lojas já estão no mapa."
                    faltaram.isEmpty() -> "$achadas de ${lojas.size} loja(s) colocadas no mapa."
                    else -> "$achadas de ${lojas.size} loja(s) colocadas no mapa. Endereço não encontrado: " +
                        faltaram.take(10).joinToString("; ") + (if (faltaram.size > 10) "…" else "") +
                        ". O dono pode usar \"Estou na loja\" em Meu negócio."
                }
                _estado.update { it.copy(localizandoLojas = false, resultadoLocalizacao = resumo) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(localizandoLojas = false, erro = e.message) }
            }
        }
    }

    fun alterarBuscaPdv(texto: String) {
        _estado.update { it.copy(buscaPdv = texto.take(60)) }
    }

    fun buscarPdvs() {
        _estado.update { it.copy(buscandoPdv = true, erro = null) }
        viewModelScope.launch {
            try {
                val lista = repositorio.estabelecimentos(_estado.value.buscaPdv)
                _estado.update { it.copy(buscandoPdv = false, estabelecimentos = lista) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(buscandoPdv = false, erro = e.message) }
            }
        }
    }

    fun suspender(pdv: PdvAdmin, motivo: String) = moderarPdv(pdv) { repositorio.suspenderPdv(pdv.id, motivo) }

    fun reativar(pdv: PdvAdmin) = moderarPdv(pdv) { repositorio.reativarPdv(pdv.id) }

    private fun moderarPdv(pdv: PdvAdmin, acao: suspend () -> Unit) {
        _estado.update { it.copy(ocupado = pdv.id, erro = null) }
        viewModelScope.launch {
            try {
                acao()
                val lista = repositorio.estabelecimentos(_estado.value.buscaPdv)
                _estado.update { it.copy(ocupado = null, estabelecimentos = lista) }
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
    val vm: AdminViewModel = viewModel(key = "admin-${usuario.id}") { AdminViewModel(container.pdvs, container.geocodificador) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    var rejeitando by remember { mutableStateOf<PdvPendente?>(null) }
    var confirmando by remember { mutableStateOf<PagamentoPendente?>(null) }
    var removendo by remember { mutableStateOf<PrecoDenunciado?>(null) }
    var suspendendo by remember { mutableStateOf<PdvAdmin?>(null) }
    var reativando by remember { mutableStateOf<PdvAdmin?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Admin", fontWeight = FontWeight.Bold) }, colors = coresBarraTopo()) },
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
                if (estado.nomes.isNotEmpty()) {
                    Text(
                        "Nomes de estabelecimentos sugeridos (${estado.nomes.size})",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Quem enviou uma NF sugeriu como o lugar é conhecido. Aprovado, o nome aparece na busca " +
                            "no lugar da razão social.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    estado.nomes.forEach { n ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("\"${n.nome}\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${n.sugestoes} pessoa(s) sugeriram", style = MaterialTheme.typography.bodySmall)
                                n.razaoSocial?.let { Text("Na nota: $it", style = MaterialTheme.typography.bodySmall) }
                                Text("CNPJ ${Cnpj.formatar(n.cnpj)}", style = MaterialTheme.typography.bodySmall)
                                n.endereco?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                val ocupadoNome = estado.ocupado == n.cnpj + n.nome
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    OutlinedButton(onClick = { vm.decidirNome(n, false) }, enabled = !ocupadoNome,
                                        modifier = Modifier.weight(1f)) { Text("Rejeitar") }
                                    Button(onClick = { vm.decidirNome(n, true) }, enabled = !ocupadoNome,
                                        modifier = Modifier.weight(1f)) { Text("Aprovar nome") }
                                }
                            }
                        }
                    }
                }
                if (estado.pagamentos.isNotEmpty()) {
                    Text(
                        "Pagamentos esperando confirmação (${estado.pagamentos.size})",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Confirme só depois de ver o Pix na conta. Confirmado, o banner entra no ar ou as operações " +
                            "são liberadas na hora.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    estado.pagamentos.forEach { p ->
                        CartaoPagamento(
                            p = p,
                            ocupado = estado.ocupado == p.id,
                            aoConfirmar = { confirmando = p },
                            aoCancelar = { vm.decidirPagamento(p, confirmar = false) },
                        )
                    }
                }
                if (!estado.carregando) {
                    Text(
                        stringResource(R.string.admin_denunciados_titulo, estado.denunciados.size),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(stringResource(R.string.admin_denunciados_texto), style = MaterialTheme.typography.bodySmall)
                    if (estado.denunciados.isEmpty()) {
                        Text(stringResource(R.string.admin_denunciados_vazio), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    estado.denunciados.forEach { p ->
                        CartaoDenuncia(
                            p = p,
                            ocupado = estado.ocupado == p.cotacaoId,
                            aoRemover = { removendo = p },
                            aoManter = { vm.moderarPreco(p, remover = false) },
                        )
                    }
                    SecaoEstabelecimentos(
                        estado = estado,
                        aoBuscar = vm::alterarBuscaPdv,
                        aoConfirmarBusca = vm::buscarPdvs,
                        aoSuspender = { suspendendo = it },
                        aoReativar = { reativando = it },
                    )
                }
                if (!estado.carregando) {
                    Text(stringResource(R.string.admin_mapa_titulo), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.admin_mapa_texto), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = vm::localizarLojasSemPino,
                        enabled = !estado.localizandoLojas,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (estado.localizandoLojas) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.admin_mapa_botao))
                    }
                    estado.resultadoLocalizacao?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = vm::carregar, modifier = Modifier.fillMaxWidth()) { Text("Atualizar") }
            }
        }
    }

    confirmando?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmando = null },
            title = { Text("Confirmar ${Dinheiro.formatar(p.valorCentavos)}?") },
            text = { Text("${p.pdvNome}: ${p.descricao}. Código ${p.id.take(8).uppercase()}. O Pix já caiu na conta?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.decidirPagamento(p, confirmar = true)
                    confirmando = null
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { confirmando = null }) { Text("Voltar") } },
        )
    }

    removendo?.let { p ->
        AlertDialog(
            onDismissRequest = { removendo = null },
            title = { Text(stringResource(R.string.admin_remover_titulo)) },
            text = {
                Text(stringResource(R.string.admin_remover_texto, p.produto, Dinheiro.formatar(p.precoCentavos),
                    p.pdvNome ?: "—"))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.moderarPreco(p, remover = true)
                    removendo = null
                }) { Text(stringResource(R.string.admin_remover)) }
            },
            dismissButton = { TextButton(onClick = { removendo = null }) { Text("Cancelar") } },
        )
    }

    suspendendo?.let { pdv ->
        var motivo by remember(pdv.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { suspendendo = null },
            title = { Text(stringResource(R.string.admin_suspender_titulo, pdv.nomeFantasia)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.admin_suspender_texto), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = motivo, onValueChange = { motivo = it.take(300) },
                        label = { Text(stringResource(R.string.admin_suspender_motivo)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.suspender(pdv, motivo)
                    suspendendo = null
                }, enabled = motivo.isNotBlank()) { Text(stringResource(R.string.admin_suspender)) }
            },
            dismissButton = { TextButton(onClick = { suspendendo = null }) { Text("Cancelar") } },
        )
    }

    reativando?.let { pdv ->
        AlertDialog(
            onDismissRequest = { reativando = null },
            title = { Text(stringResource(R.string.admin_reativar_titulo, pdv.nomeFantasia)) },
            text = { Text(stringResource(R.string.admin_reativar_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.reativar(pdv)
                    reativando = null
                }) { Text(stringResource(R.string.admin_reativar)) }
            },
            dismissButton = { TextButton(onClick = { reativando = null }) { Text("Cancelar") } },
        )
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
            if (pdv.tipo == TipoEmpresa.ANUNCIANTE) {
                Text(stringResource(R.string.admin_tipo_anunciante), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
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
            if (pdv.alvaraPath == null) Text(stringResource(R.string.admin_sem_documento), fontWeight = FontWeight.SemiBold)
            else Text(
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
                // Anunciante pode vir sem documento: aí aprova pela Receita e pelo contato.
                val semDocumento = pdv.alvaraPath == null
                Button(onClick = aoAprovar, enabled = !ocupado && (alvara != null || semDocumento), modifier = Modifier.weight(1f)) {
                    if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Aprovar")
                }
            }
            if (alvara == null && pdv.alvaraPath != null) {
                Text("Veja o alvará antes de aprovar.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val formatoData = DateTimeFormatter.ofPattern("dd/MM HH:mm")

@Composable
private fun CartaoPagamento(p: PagamentoPendente, ocupado: Boolean, aoConfirmar: () -> Unit, aoCancelar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.pdvNome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(Dinheiro.formatar(p.valorCentavos), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(p.descricao)
            Text("Código ${p.id.take(8).uppercase()} · ${formatoData.format(p.criadoEm.atZone(ZoneId.of("America/Sao_Paulo")))}",
                style = MaterialTheme.typography.bodySmall)
            Text(
                "Pedido por: ${listOfNotNull(p.donoNome, p.donoEmail).joinToString(" — ").ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = aoCancelar, enabled = !ocupado, modifier = Modifier.weight(1f)) { Text("Cancelar pedido") }
                Button(onClick = aoConfirmar, enabled = !ocupado, modifier = Modifier.weight(1f)) {
                    if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Pago")
                }
            }
        }
    }
}

@Composable
private fun CartaoDenuncia(p: PrecoDenunciado, ocupado: Boolean, aoRemover: () -> Unit, aoManter: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.produto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(Dinheiro.formatar(p.precoCentavos), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(
                    p.pdvNome,
                    stringResource(if (p.oficial) R.string.admin_fonte_oficial else R.string.admin_fonte_cliente),
                    if (p.parteDeCombo) stringResource(R.string.admin_combo) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                pluralStringResource(R.plurals.admin_denuncias, p.denuncias, p.denuncias),
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error,
            )
            val motivos = listOfNotNull(
                p.comboOuPromocao.takeIf { it > 0 }?.let { stringResource(R.string.admin_motivo_combo, it) },
                p.precoMudou.takeIf { it > 0 }?.let { stringResource(R.string.admin_motivo_mudou, it) },
                p.produtoErrado.takeIf { it > 0 }?.let { stringResource(R.string.admin_motivo_produto, it) },
                p.outros.takeIf { it > 0 }?.let { stringResource(R.string.admin_motivo_outro, it) },
            )
            if (motivos.isNotEmpty()) Text(motivos.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = aoManter, enabled = !ocupado, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_manter))
                }
                Button(onClick = aoRemover, enabled = !ocupado, modifier = Modifier.weight(1f)) {
                    if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.admin_remover))
                }
            }
        }
    }
}

@Composable
private fun SecaoEstabelecimentos(
    estado: EstadoAdmin,
    aoBuscar: (String) -> Unit,
    aoConfirmarBusca: () -> Unit,
    aoSuspender: (PdvAdmin) -> Unit,
    aoReativar: (PdvAdmin) -> Unit,
) {
    Text(stringResource(R.string.admin_estabelecimentos_titulo), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.admin_estabelecimentos_texto), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = estado.buscaPdv,
        onValueChange = aoBuscar,
        label = { Text(stringResource(R.string.admin_estabelecimentos_busca)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { aoConfirmarBusca() }),
        trailingIcon = {
            IconButton(onClick = aoConfirmarBusca, enabled = !estado.buscandoPdv) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.admin_estabelecimentos_buscar))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (estado.buscandoPdv) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (estado.estabelecimentos.isEmpty()) {
        Text(stringResource(R.string.admin_estabelecimentos_vazio), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    estado.estabelecimentos.forEach { pdv ->
        val ocupado = estado.ocupado == pdv.id
        val suspenso = pdv.status == StatusPdv.SUSPENSO
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pdv.nomeFantasia, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text(
                        stringResource(if (suspenso) R.string.admin_status_suspenso else R.string.admin_status_ativo),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (suspenso) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    listOfNotNull("CNPJ ${Cnpj.formatar(pdv.cnpj)}", pdv.cidade).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.admin_estabelecimento_dono,
                        listOfNotNull(pdv.donoNome, pdv.donoEmail).joinToString(" — ").ifEmpty { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (pdv.tipo == TipoEmpresa.ANUNCIANTE) stringResource(R.string.admin_tipo_anunciante)
                    else stringResource(R.string.admin_estabelecimento_itens, pdv.itensPublicados),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (suspenso) {
                    pdv.motivoSuspensao?.let {
                        Text(stringResource(R.string.pdv_suspenso_motivo, it), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { aoReativar(pdv) }, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                        if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.admin_reativar))
                    }
                } else {
                    OutlinedButton(onClick = { aoSuspender(pdv) }, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                        if (ocupado) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.admin_suspender))
                    }
                }
            }
        }
    }
}
