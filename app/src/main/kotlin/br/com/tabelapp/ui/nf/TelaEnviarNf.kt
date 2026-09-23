package br.com.tabelapp.ui.nf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.ui.tema.VermelhoTabelapp
import br.com.tabelapp.ui.tema.coresBarraTopo
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.Usuario
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Aba "Enviar NF": QR Code do cupom -> leitura na Sefaz -> conferência -> confirmação única -> envio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaEnviarNf(container: AppContainer, usuario: Usuario, aoVerNaBusca: () -> Unit) {
    val vm: EnviarNfViewModel = viewModel(key = "nf-${usuario.id}") { EnviarNfViewModel(container.notasFiscais) }
    val estado by vm.estado.collectAsStateWithLifecycle()

    Scaffold(
        // A barra de baixo (abas) já cuida da borda inferior da tela.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Enviar nota fiscal", fontWeight = FontWeight.Bold) },
                colors = coresBarraTopo(),
                actions = {
                    if (estado.etapa != EtapaNf.INICIO && estado.etapa != EtapaNf.ENVIADO) {
                        TextButton(onClick = vm::novaNota) {
                            Text("Recomeçar", color = VermelhoTabelapp)
                        }
                    }
                },
            )
        },
    ) { margens ->
        Box(Modifier.padding(margens).fillMaxSize()) {
            when (estado.etapa) {
                EtapaNf.INICIO -> EtapaInicio(estado, vm)
                EtapaNf.LENDO_SEFAZ -> EtapaLendoSefaz(estado, vm)
                EtapaNf.FALHA -> EtapaFalha(estado, vm)
                EtapaNf.CONFIRMACAO -> EtapaConfirmacao(estado, vm)
                EtapaNf.ENVIADO -> EtapaEnviado(estado, vm, aoVerNaBusca)
            }
        }
    }
}

@Composable
private fun EtapaInicio(estado: EstadoNf, vm: EnviarNfViewModel) {
    val contexto = LocalContext.current
    var textoChave by rememberSaveable { mutableStateOf("") }

    fun abrirLeitor() {
        val opcoes = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(contexto, opcoes).startScan()
            .addOnSuccessListener { codigo -> vm.qrLido(codigo.rawValue.orEmpty()) }
            .addOnFailureListener {
                // Na primeira vez o Google Play baixa o leitor; pode falhar até terminar.
                vm.erroNoLeitor(
                    "Não foi possível abrir o leitor de QR Code agora. Tente de novo em instantes " +
                        "ou digite a chave da nota abaixo."
                )
            }
        // Cancelado pelo usuário: nada a fazer.
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.QrCodeScanner, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp),
        )
        Text(
            "Compartilhe os preços da sua compra",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(
            "Aponte a câmera para o QR Code impresso no cupom fiscal. O Tabelapp lê os produtos na Sefaz " +
                "e você só confere antes de enviar. Seu CPF não é lido nem guardado.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = ::abrirLeitor, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Ler QR Code da nota")
        }

        estado.erroInicio?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("O leitor não abriu? Cole o link do QR Code (ex.: lido por outro app de câmera):")
        OutlinedTextField(
            value = textoChave, onValueChange = { textoChave = it },
            label = { Text("Link do QR Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { vm.qrLido(textoChave) },
            enabled = textoChave.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuar com o link") }
    }
}

@Composable
private fun EtapaLendoSefaz(estado: EstadoNf, vm: EnviarNfViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Column(Modifier.weight(1f)) {
                Text("Lendo os produtos na Sefaz…", fontWeight = FontWeight.SemiBold)
                Text(
                    "Se aparecer alguma verificação abaixo, resolva-a que a leitura continua.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LeitorSefaz(
            url = estado.urlSefaz!!,
            aoLer = vm::notaLidaNaSefaz,
            aoDesistir = vm::leituraSefazFalhou,
            aoCapturarHtml = vm::htmlCapturado,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        TextButton(onClick = vm::novaNota, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun EtapaFalha(estado: EstadoNf, vm: EnviarNfViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            "Não consegui ler esta nota na Sefaz",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(
            "Pode ser instabilidade no site da Sefaz. Tente de novo em instantes. " +
                "Só enviamos preços lidos direto da nota, por isso não dá para digitar os produtos.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::tentarDeNovo, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
        OutlinedButton(onClick = vm::novaNota, modifier = Modifier.fillMaxWidth()) { Text("Ler outra nota") }
        if (estado.temPaginaSefaz) {
            Text(
                "Ajude a melhorar o Tabelapp: envie a página da Sefaz para analisarmos por que a leitura falhou.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            BotaoEnviarParaAnalise(vm)
        }
    }
}

@Composable
private fun EtapaConfirmacao(estado: EstadoNf, vm: EnviarNfViewModel) {
    val rascunho = estado.rascunho()
    val itens = rascunho.itensParaEnvio()
    val loja = estado.lojas.firstOrNull { it.lojaId == estado.lojaId }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Confira antes de enviar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Dados lidos da nota na Sefaz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (estado.lojas.size > 1) {
            Text("Este estabelecimento tem ${estado.lojas.size} lojas. Em qual foi a compra?")
            estado.lojas.forEach { l ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = estado.lojaId == l.lojaId, onClick = { vm.escolherLoja(l.lojaId) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = estado.lojaId == l.lojaId, onClick = { vm.escolherLoja(l.lojaId) })
                    Column {
                        Text(l.titulo, fontWeight = FontWeight.SemiBold)
                        Text(l.endereco, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(loja?.titulo ?: rascunho.pdvNome, fontWeight = FontWeight.Bold)
                (loja?.endereco ?: rascunho.pdvEndereco.ifBlank { null })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                ChaveAcessoNfe.deTexto(rascunho.chaveAcesso)?.let {
                    Text("Chave: ${it.formatada()}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Data da compra: ${Validade.formatar(rascunho.dataNf)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${itens.size} produto(s)", fontWeight = FontWeight.SemiBold)
                itens.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(item.produto, Modifier.weight(1f))
                        Text(Dinheiro.formatar(item.precoCentavos), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Text(
            "Na busca, os preços aparecem com a marcação \"NF\" e \"Preço praticado dia " +
                "${Validade.formatar(rascunho.dataNf)}\", e ficam visíveis por ${Validade.NF_DIAS} dias.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        estado.erros.forEach { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = vm::enviar, enabled = !estado.enviando && estado.erros.isEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (estado.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Enviar nota")
        }
        OutlinedButton(
            onClick = vm::novaNota, enabled = !estado.enviando,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancelar") }
    }
}

@Composable
private fun EtapaEnviado(estado: EstadoNf, vm: EnviarNfViewModel, aoVerNaBusca: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.CheckCircle, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp),
        )
        Text("Obrigado!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${estado.itensEnviados} preço(s) enviados. Quem pesquisa economiza — e agora você ajudou quem pesquisa.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = { vm.novaNota(); aoVerNaBusca() }, modifier = Modifier.fillMaxWidth()) {
            Text("Ver na busca")
        }
        OutlinedButton(onClick = vm::novaNota, modifier = Modifier.fillMaxWidth()) { Text("Enviar outra nota") }
    }
}

/**
 * Compartilha a página da Sefaz (sem scripts, CPF mascarado) pelo menu do Android
 * (e-mail, WhatsApp, Drive...), para ajustar o leitor quando a leitura automática falha.
 */
@Composable
private fun BotaoEnviarParaAnalise(vm: EnviarNfViewModel) {
    val contexto = LocalContext.current
    TextButton(onClick = {
        vm.paginaParaAnalise()?.let { pagina ->
            val envio = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Tabelapp - página da Sefaz para análise")
                putExtra(android.content.Intent.EXTRA_TEXT, pagina.take(400_000))
            }
            contexto.startActivity(android.content.Intent.createChooser(envio, "Enviar página para análise"))
        }
    }) { Text("Enviar página para análise") }
}
