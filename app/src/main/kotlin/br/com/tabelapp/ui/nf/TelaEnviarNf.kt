package br.com.tabelapp.ui.nf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.tabelapp.AppContainer
import br.com.tabelapp.R
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.EstadosNfce
import br.com.tabelapp.core.LeitorNfce
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.PdfNota
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.comum.AcaoErro
import br.com.tabelapp.ui.comum.PainelErro
import br.com.tabelapp.ui.tema.CoresTabelapp
import br.com.tabelapp.ui.tema.coresBarraTopo
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                            Text("Recomeçar", color = MaterialTheme.colorScheme.error)
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
    val textoChave = estado.chaveManual
    val focoChave = remember { FocusRequester() }

    fun abrirLeitor() {
        val opcoes = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(contexto, opcoes).startScan()
            .addOnSuccessListener { codigo -> vm.qrLido(codigo.rawValue.orEmpty()) }
            // Câmera negada, ou leitor ainda sendo baixado pelo Google Play: mensagem calma.
            .addOnFailureListener { vm.erroNoLeitor(it) }
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

        if (estado.temQrGuardado) {
            OutlinedButton(onClick = vm::usarQrGuardado, modifier = Modifier.fillMaxWidth()) {
                Text("Tentar de novo com o QR Code já lido")
            }
        }

        estado.erroInicio?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        val preencherManual = AcaoErro(stringResource(R.string.erro_botao_preencher_manualmente), {
            vm.fecharErroInicio()
            runCatching { focoChave.requestFocus() }
        })
        when (estado.erroInicioTipo) {
            TipoErro.CAMERA_NEGADA -> PainelErro(
                TipoErro.CAMERA_NEGADA,
                principal = AcaoErro(stringResource(R.string.erro_botao_abrir_configuracoes), {
                    vm.fecharErroInicio()
                    abrirConfiguracoesDoApp(contexto)
                }),
                secundario = preencherManual,
                compacto = true,
            )
            TipoErro.QR_INVALIDO -> PainelErro(
                TipoErro.QR_INVALIDO,
                principal = AcaoErro(stringResource(R.string.erro_botao_tentar_de_novo), {
                    vm.fecharErroInicio()
                    abrirLeitor()
                }),
                secundario = preencherManual,
                compacto = true,
            )
            else -> Unit
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("Entrada manual", fontWeight = FontWeight.Bold)
        Text(
            "Digite os 44 números da nota (a \"chave de acesso\", impressa no cupom perto do QR Code).",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = textoChave,
            onValueChange = vm::alterarChaveManual,
            label = { Text("Chave de acesso (${textoChave.length}/44)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // Mostra em grupos de 4, como no cupom, para facilitar a conferência.
            visualTransformation = GruposDeQuatro,
            modifier = Modifier.fillMaxWidth().focusRequester(focoChave),
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
            aoFalhar = vm::sefazFalhou,
            lerNota = ChaveAcessoNfe.deTexto(estado.chave)?.let(EstadosNfce::ativoDaChave)?.leitor ?: LeitorNfce::ler,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        TextButton(onClick = vm::novaNota, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun EtapaFalha(estado: EstadoNf, vm: EnviarNfViewModel) {
    // Intervalo mínimo entre tentativas: o botão "Tentar de novo" libera após a contagem.
    var espera by remember { mutableLongStateOf(vm.esperaParaTentarMs()) }
    LaunchedEffect(estado.falha) {
        while (true) {
            espera = vm.esperaParaTentarMs()
            if (espera <= 0) break
            delay(250)
        }
    }
    val segundos = ((espera + 999) / 1000).toInt()
    val tentar = AcaoErro(stringResource(R.string.erro_botao_tentar_de_novo), vm::tentarDeNovo, habilitado = espera <= 0)
    val aguarde = if (espera > 0) stringResource(R.string.erro_aguarde_segundos, segundos) else null
    val manual = AcaoErro(stringResource(R.string.erro_botao_preencher_manualmente), vm::preencherManualmente)
    val rolagem = Modifier.fillMaxSize().verticalScroll(rememberScrollState())

    Column(rolagem, verticalArrangement = Arrangement.Center) {
        when (estado.falha) {
            FalhaNf.SEM_INTERNET -> PainelErro(
                TipoErro.SEM_INTERNET, principal = tentar, secundario = manual, observacao = aguarde,
            )
            FalhaNf.NOTA_DIFERENTE -> PainelErro(
                titulo = stringResource(R.string.erro_nota_diferente_titulo),
                texto = stringResource(R.string.erro_nota_diferente_texto),
                principal = tentar, secundario = manual, observacao = aguarde,
            )
            FalhaNf.LEITURA -> {
                PainelErro(
                    titulo = stringResource(R.string.erro_leitura_nf_titulo),
                    texto = stringResource(R.string.erro_leitura_nf_texto),
                    principal = tentar, secundario = manual, observacao = aguarde,
                )
                if (estado.temPaginaSefaz) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Ajude a melhorar o Tabelapp: envie a página da Sefaz para analisarmos por que a leitura falhou.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        BotaoEnviarParaAnalise(vm)
                    }
                }
            }
            // Sefaz fora, bloqueio de IP, HTTP 403/429/5xx ou demora: a tela calma pedida.
            else -> PainelErro(
                TipoErro.SEFAZ_INDISPONIVEL, principal = manual, secundario = tentar, observacao = aguarde,
            )
        }
        TextButton(onClick = vm::novaNota, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Ler outra nota")
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
                        Text(Dinheiro.formatar(item.precoCentavos), fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
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
            Text("Quero compartilhar esta NF-e em PDF")
        }

        estado.erros.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        estado.avisoEnvio?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // Falha ao enviar: os dados continuam na tela; o botão de baixo tenta de novo.
        estado.falhaEnvio?.let { tipo -> PainelErro(tipo, principal = null, compacto = true) }

        Button(
            onClick = vm::enviar, enabled = !estado.enviando && estado.erros.isEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (estado.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (estado.falhaEnvio != null) stringResource(R.string.erro_botao_tentar_de_novo) else "Enviar nota")
        }
        OutlinedButton(
            onClick = vm::novaNota, enabled = !estado.enviando,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancelar") }
    }
}

@Composable
private fun EtapaEnviado(estado: EstadoNf, vm: EnviarNfViewModel, aoVerNaBusca: () -> Unit) {
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
                compartilharPdf(contexto, arquivo, estado)
            } catch (e: ErroAmigavel) {
                erroPdf = e.message
            } finally {
                gerandoPdf = false
            }
        }
    }
    // Marcou a opção na confirmação: já abre o compartilhamento do PDF.
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
            else Text("Compartilhe essa NF-e em PDF")
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
 * Compartilha o PDF da NF-e: a pessoa escolhe para onde (WhatsApp, e-mail, Drive,
 * salvar no celular...). O Tabelapp não envia nada sozinho.
 */
private fun compartilharPdf(contexto: Context, arquivo: File, estado: EstadoNf) {
    val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_SUBJECT, "Nota fiscal - ${estado.pdvNome.ifBlank { "compra" }} - ${Validade.formatar(estado.dataNf)}")
        putExtra(Intent.EXTRA_TEXT, "Cópia da nota fiscal consultada na Sefaz pelo Tabelapp.")
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("nota", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        contexto.startActivity(Intent.createChooser(envio, "Compartilhar NF-e em PDF"))
    } catch (e: ActivityNotFoundException) {
        // Nenhum app para receber o arquivo: nada a fazer.
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

/** Configurações do app no Android (para liberar a câmera). */
private fun abrirConfiguracoesDoApp(contexto: Context) {
    try {
        contexto.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", contexto.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        // Sem a tela de configurações: nada a fazer.
    }
}
