package br.com.tabelapp.ui.pdv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.tema.coresBarraTopo
import java.io.File

/** Aba "Meu negócio" da conta CNPJ: cadastro do estabelecimento e situação da análise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaMeuNegocio(container: AppContainer, usuario: Usuario) {
    val vm: MeuNegocioViewModel = viewModel(key = "pdv-${usuario.id}") {
        MeuNegocioViewModel(container.pdvs, container.imagens, container.leitorTexto)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Meu negócio", fontWeight = FontWeight.Bold) }, colors = coresBarraTopo()) },
    ) { margens ->
        Box(Modifier.padding(margens).fillMaxSize()) {
            when (estado.etapa) {
                EtapaPdv.CARREGANDO -> Carregando(estado.erroCarregar, vm::carregar)
                EtapaPdv.FORMULARIO -> FormularioPdv(estado, vm)
                EtapaPdv.SITUACAO -> Situacao(estado.pdvs, vm, modoDemo = container.modoDemo)
            }
        }
    }
}

@Composable
private fun Carregando(erro: String?, tentarDeNovo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (erro == null) {
            CircularProgressIndicator()
        } else {
            Text(erro, color = MaterialTheme.colorScheme.error)
            Button(onClick = tentarDeNovo) { Text("Tentar de novo") }
        }
    }
}

@Composable
private fun FormularioPdv(estado: EstadoPdv, vm: MeuNegocioViewModel) {
    val contexto = LocalContext.current
    var uriFoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    val tirarFoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) uriFoto?.let(vm::alvaraEscolhido)
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::alvaraEscolhido)
    }
    fun abrirCamera() {
        val pasta = File(contexto.cacheDir, "fotos").apply { mkdirs() }
        val arquivo = File.createTempFile("alvara_", ".jpg", pasta)
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
        uriFoto = uri
        tirarFoto.launch(uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Cadastre seu estabelecimento para publicar seus preços no Tabelapp. Para evitar fraudes, " +
                "pedimos o alvará: nossa equipe confere se você responde pela empresa antes de liberar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. CNPJ
        Text("1. CNPJ da empresa", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = estado.cnpj, onValueChange = vm::alterarCnpj,
                label = { Text("CNPJ") },
                placeholder = { Text("00.000.000/0000-00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = vm::consultarCnpj, enabled = !estado.consultando) {
                if (estado.consultando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Consultar")
            }
        }
        val receita = estado.receita
        when {
            receita != null -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Receita Federal", style = MaterialTheme.typography.labelMedium)
                    Text(receita.razaoSocial ?: "—", fontWeight = FontWeight.Bold)
                    receita.nomeFantasia?.let { Text(it) }
                    Text("Situação: ${receita.situacao ?: "—"}", fontWeight = FontWeight.SemiBold)
                    receita.atividade?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            estado.consultado && estado.avisoConsulta == null ->
                Text(
                    "CNPJ não encontrado na consulta da Receita. Confira os números; se estiverem certos, " +
                        "preencha os dados — nossa equipe confere pelo alvará.",
                    color = MaterialTheme.colorScheme.error,
                )
            estado.avisoConsulta != null -> Text(estado.avisoConsulta, color = MaterialTheme.colorScheme.error)
        }

        if (estado.consultado) {
            // 2. Dados
            Text("2. Dados do estabelecimento", fontWeight = FontWeight.Bold)
            Campo("Nome do estabelecimento (como os clientes conhecem)", estado.nomeFantasia, vm::alterarNome)
            Campo("Endereço (rua e número)", estado.endereco, vm::alterarEndereco)
            Campo("Bairro", estado.bairro, vm::alterarBairro)
            Campo("Cidade", estado.cidade, vm::alterarCidade)
            Campo("Telefone", estado.telefone, vm::alterarTelefone, KeyboardType.Phone)

            // 3. Alvará
            Text("3. Foto do alvará", fontWeight = FontWeight.Bold)
            Text(
                "Fotografe o alvará de funcionamento inteiro, com o CNPJ bem legível.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            estado.alvaraMiniatura?.let {
                Image(
                    bitmap = it, contentDescription = "Foto do alvará",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            if (estado.lendoAlvara) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Lendo o alvará…")
                }
            }
            when (estado.cnpjNoAlvara) {
                true -> Aviso(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary,
                    "CNPJ encontrado no alvará.")
                false -> Aviso(Icons.Default.Warning, MaterialTheme.colorScheme.error,
                    "Não encontrei o CNPJ ${Cnpj.normalizar(estado.cnpj)?.let(Cnpj::formatar) ?: ""} nesta foto. " +
                        "Tente uma foto mais nítida, ou envie assim para a análise da nossa equipe.")
                null -> {}
            }
            estado.avisoAlvara?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::abrirCamera, enabled = !estado.lendoAlvara, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (estado.alvara == null) "Tirar foto" else "Outra foto")
                }
                OutlinedButton(
                    onClick = { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !estado.lendoAlvara,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Galeria")
                }
            }
        }

        estado.erros.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

        if (estado.consultado) {
            Button(
                onClick = vm::enviar,
                enabled = !estado.enviando && !estado.lendoAlvara,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (estado.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Enviar para análise")
            }
        }
        if (estado.pdvs.isNotEmpty()) {
            OutlinedButton(onClick = vm::voltarASituacao, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}

@Composable
private fun Campo(rotulo: String, valor: String, aoMudar: (String) -> Unit, teclado: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = valor, onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Aviso(icone: ImageVector, cor: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icone, contentDescription = null, tint = cor)
        Spacer(Modifier.width(8.dp))
        Text(texto, color = cor)
    }
}

@Composable
private fun Situacao(pdvs: List<MeuPdv>, vm: MeuNegocioViewModel, modoDemo: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        pdvs.forEach { pdv ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pdv.nomeFantasia, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("CNPJ ${Cnpj.formatar(pdv.cnpj)}", style = MaterialTheme.typography.bodySmall)
                    when (pdv.status) {
                        StatusPdv.PENDENTE -> {
                            Aviso(Icons.Default.HourglassTop, MaterialTheme.colorScheme.onSurface, "Cadastro em análise")
                            Text(
                                "Nossa equipe está conferindo o alvará. Assim que for aprovado, você poderá " +
                                    "publicar seus preços. Volte aqui para acompanhar.",
                            )
                            if (modoDemo) {
                                Text(
                                    "Demonstração: saia e entre com um e-mail começando com \"admin\" para aprovar.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        StatusPdv.APROVADO -> {
                            Aviso(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Cadastro aprovado")
                            Text(
                                "Em breve, aqui: sua tabela de preços, importação de planilha e promoções.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPdv.REJEITADO -> {
                            Aviso(Icons.Default.Warning, MaterialTheme.colorScheme.error, "Cadastro não aprovado")
                            pdv.motivoRejeicao?.let { Text("Motivo: $it") }
                            Button(onClick = { vm.novoCadastro(pdv) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Corrigir e enviar de novo")
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = vm::carregar, modifier = Modifier.fillMaxWidth()) { Text("Atualizar") }
    }
}
