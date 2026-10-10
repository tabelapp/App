package br.com.tabelapp.ui.nf

import java.io.File
import kotlinx.coroutines.launch
import br.com.tabelapp.dados.PdfNota
import br.com.tabelapp.dados.ErroAmigavel
import androidx.core.content.FileProvider
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
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

/** Aba "Enviar preços": QR Code (ou chave) do cupom -> leitura na Sefaz -> conferência -> envio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaEnviarNf(container: AppContainer, usuario: Usuario, aoVerNaBusca: () -> Unit) {
    val vm: EnviarNfViewModel = viewModel(key = "nf-${usuario.id}") {
        EnviarNfViewModel(container.notasFiscais, container.pdvs::consultarCnpj)
    }
    val estado by vm.estado.collectAsStateWithLifecycle()

    Scaffold(
        // A barra de baixo (abas) já cuida da borda inferior da tela.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Enviar preços", fontWeight = FontWeight.Bold) },
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
                EtapaNf.ENVIADO -> EtapaEnviado(estado, vm, usuario, aoVerNaBusca)
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
                        "ou digite os 44 números da nota abaixo."
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
            "Aponte a câmera para o QR Code impresso no final do cupom fiscal. O Tabelapp guarda somente " +
                "as informações do vendedor, produtos e preços. As informações do comprador nunca serão armazenadas.",
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

        Text("Entrada manual", fontWeight = FontWeight.Bold)
        Text(
            "Digite os 44 números da nota (a \"chave de acesso\", impressa no cupom perto do QR Code).",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = textoChave,
            onValueChange = { textoChave = it.filter { c -> c.isDigit() }.take(44) },
            label = { Text("Chave de acesso (${textoChave.length}/44)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // Mostra em grupos de 4, como no cupom, para facilitar a conferência.
            visualTransformation = GruposDeQuatro,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                // Copia a chave: se a página da Sefaz não deixar preencher sozinha, é só colar.
                (contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("Chave de acesso", textoChave))
                vm.chaveDigitada(textoChave)
            },
            enabled = textoChave.length == 44,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Consultar a nota") }
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
                    if (estado.modoManual) {
                        "Confira se a chave foi preenchida (se não, toque no campo e cole: ela já está copiada), " +
                            "resolva a verificação da Sefaz e toque em consultar."
                    } else {
                        "Se aparecer alguma verificação abaixo, resolva-a que a leitura continua."
                    },
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
            chaveParaPreencher = estado.chave.takeIf { estado.modoManual },
            aoFalharRede = vm::sefazFoiDoAr,
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
            estado.motivoFalha ?: ("Pode ser instabilidade no site da Sefaz. Tente de novo em instantes. " +
                "Só enviamos preços lidos direto da nota, por isso não dá para digitar os produtos."),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::tentarDeNovo, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
        estado.urlSefaz?.let { url ->
            val contexto = LocalContext.current
            OutlinedButton(
                onClick = {
                    try {
                        contexto.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: ActivityNotFoundException) {
                        // Sem navegador: nada a fazer.
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Abrir a nota no navegador") }
        }
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
                if (loja == null && estado.razaoSocialNota.isNotBlank() && estado.razaoSocialNota != rascunho.pdvNome) {
                    Text("Razão social: ${estado.razaoSocialNota}", style = MaterialTheme.typography.bodySmall)
                }
                (loja?.endereco ?: rascunho.pdvEndereco.ifBlank { null })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (estado.consultandoReceita) {
                    Text("Buscando o nome do estabelecimento…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChaveAcessoNfe.deTexto(rascunho.chaveAcesso)?.let {
                    Text("Chave: ${it.formatada()}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Data da compra: ${Validade.formatar(rascunho.dataNf)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (estado.podeSugerirNome) {
            Text("Como este lugar é conhecido?", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = estado.nomeSugerido, onValueChange = vm::alterarNomeSugerido,
                label = { Text("Nome do estabelecimento (opcional)") },
                placeholder = { Text("Ex.: Padaria do Zé") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A nota mostra só a razão social. Para evitar uso indevido, o nome que você sugerir aparece " +
                    "na busca depois de confirmado por outra pessoa que comprou lá ou pela nossa equipe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

        Row(
            Modifier.fillMaxWidth().clickable { vm.alternarPdf(!estado.querPdf) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = estado.querPdf, onCheckedChange = vm::alternarPdf)
            Text("Quero uma cópia em PDF desta nota no meu e-mail")
        }

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
private fun EtapaEnviado(estado: EstadoNf, vm: EnviarNfViewModel, usuario: Usuario, aoVerNaBusca: () -> Unit) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var gerandoPdf by remember { mutableStateOf(false) }
    var erroPdf by remember { mutableStateOf<String?>(null) }

    fun enviarPdf() {
        val pagina = vm.paginaDaNota()
        if (pagina == null) {
            erroPdf = "A página da nota não está mais disponível."
            return
        }
        gerandoPdf = true
        erroPdf = null
        escopo.launch {
            try {
                val arquivo = PdfNota.gerar(contexto, pagina.first, pagina.second, "nota-fiscal-${estado.chave.takeLast(8)}.pdf")
                abrirEmailComPdf(contexto, arquivo, usuario.email, estado)
            } catch (e: ErroAmigavel) {
                erroPdf = e.message
            } finally {
                gerandoPdf = false
            }
        }
    }
    // Marcou a opção na confirmação: já abre o e-mail com o PDF.
    LaunchedEffect(Unit) { if (estado.querPdf) enviarPdf() }

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
            if (estado.itensEnviados == 0) {
                "Esses preços já estavam no Tabelapp (mesmo local, dia e preço) — confirmados por você. " +
                    "Quem pesquisa economiza!"
            } else {
                "${estado.itensEnviados} preço(s) novos enviados. Itens repetidos (mesmo local, dia e preço) " +
                    "não entram duas vezes. Quem pesquisa economiza — e agora você ajudou quem pesquisa."
            },
            textAlign = TextAlign.Center,
        )
        estado.resultadoSugestao?.let { Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = ::enviarPdf, enabled = !gerandoPdf, modifier = Modifier.fillMaxWidth()) {
            if (gerandoPdf) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Receber cópia em PDF no e-mail")
        }
        erroPdf?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
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

/**
 * Abre o app de e-mail com o PDF da nota anexado, já endereçado ao e-mail da
 * conta. A pessoa só toca em enviar (o Tabelapp não envia e-mails sozinho).
 */
private fun abrirEmailComPdf(contexto: Context, arquivo: File, email: String?, estado: EstadoNf) {
    val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        email?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        putExtra(Intent.EXTRA_SUBJECT, "Nota fiscal - ${estado.pdvNome.ifBlank { "compra" }} - ${Validade.formatar(estado.dataNf)}")
        putExtra(Intent.EXTRA_TEXT, "Cópia da nota fiscal consultada na Sefaz pelo Tabelapp.")
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("nota", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        contexto.startActivity(Intent.createChooser(envio, "Enviar PDF para o seu e-mail"))
    } catch (e: ActivityNotFoundException) {
        // Sem app de e-mail/compartilhamento: nada a fazer.
    }
}

/** Mostra a chave em grupos de 4 números ("3525 0911 ..."), sem mudar o que foi digitado. */
private object GruposDeQuatro : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val formatado = original.chunked(4).joinToString(" ")
        val mapa = object : OffsetMapping {
            // Antes da posição n há (n - 1) / 4 espaços inseridos.
            override fun originalToTransformed(offset: Int): Int = offset + (offset - 1).coerceAtLeast(0) / 4
            override fun transformedToOriginal(offset: Int): Int = (offset - offset / 5).coerceIn(0, original.length)
        }
        return TransformedText(AnnotatedString(formatado), mapa)
    }
}
