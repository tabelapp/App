package br.com.tabelapp.ui.nf

import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import android.webkit.WebChromeClient
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import br.com.tabelapp.core.LeitorNfce
import br.com.tabelapp.core.NotaLida
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

private const val INTERVALO_MS = 1_500L
private const val DESISTIR_APOS_MS = 90_000L
/** Na consulta pela chave digitada o usuário ainda resolve a verificação da Sefaz: mais tempo. */
private const val DESISTIR_APOS_MS_MANUAL = 240_000L

/**
 * Consulta pública da NFC-e pela chave de acesso na Sefaz-RJ (é o endereço impresso
 * no cupom: "Consulte pela Chave de Acesso em www.fazenda.rj.gov.br/nfce/consulta").
 */
const val URL_CONSULTA_CHAVE_RJ = "https://www.fazenda.rj.gov.br/nfce/consulta"

/**
 * Abre a consulta da NFC-e (URL do QR Code) DENTRO do celular e lê os produtos.
 *
 * Por que no celular e não no servidor: o portal da Sefaz-RJ bloqueia acessos
 * vindos de servidores (briefing, seção 8), mas abre normalmente na conexão do
 * usuário — é o mesmo que ele abrir o link no navegador.
 *
 * A página fica visível: se a Sefaz pedir alguma verificação ("não sou robô"),
 * o próprio usuário resolve ali. A cada 1,5 s o HTML é conferido; quando os
 * produtos aparecem, [aoLer] é chamado. Depois de 90 s, [aoDesistir].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeitorSefaz(
    url: String,
    aoLer: (NotaLida) -> Unit,
    aoDesistir: () -> Unit,
    modifier: Modifier = Modifier,
    /** Recebe o HTML e o endereço atuais a cada leitura (diagnóstico e cópia em PDF). */
    aoCapturarHtml: (html: String, url: String?) -> Unit = { _, _ -> },
    /** Entrada manual: chave de 44 números para preencher no formulário de consulta da Sefaz. */
    chaveParaPreencher: String? = null,
    /** O site da Sefaz não respondeu em nenhum dos endereços (recebe o erro técnico). */
    aoFalharRede: (String) -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val aoLerAtual by rememberUpdatedState(aoLer)
    val aoDesistirAtual by rememberUpdatedState(aoDesistir)
    val aoCapturarHtmlAtual by rememberUpdatedState(aoCapturarHtml)
    val aoFalharRedeAtual by rememberUpdatedState(aoFalharRede)

    // Enquanto a Sefaz não responde, a página fica em branco: mostramos um aviso por cima.
    var progresso by remember { mutableIntStateOf(0) }

    Box(modifier) {
        AndroidView(
            factory = { contexto ->
                WebView(contexto).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Alguns portais recusam o "navegador embutido": apresenta-se como o Chrome do celular.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    webViewClient = ClienteSefaz { erro -> aoFalharRedeAtual(erro) }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progresso = newProgress
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (progresso < 100) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator()
                Text(
                    "Um instante, por favor…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Estamos consultando sua nota fiscal na Sefaz. Isso pode levar alguns segundos.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    LaunchedEffect(url) {
        val inicio = System.currentTimeMillis()
        val limite = if (chaveParaPreencher != null) DESISTIR_APOS_MS_MANUAL else DESISTIR_APOS_MS
        while (System.currentTimeMillis() - inicio < limite) {
            delay(INTERVALO_MS)
            val wv = webView ?: continue
            chaveParaPreencher?.let { preencherChave(wv, it) }
            val html = htmlDaPagina(wv) ?: continue
            aoCapturarHtmlAtual(html, wv.url)
            val nota = withContext(Dispatchers.Default) { runCatching { LeitorNfce.ler(html) }.getOrNull() }
            if (nota != null) {
                aoLerAtual(nota)
                return@LaunchedEffect
            }
        }
        aoDesistirAtual()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }
}

/**
 * Preenche a chave no formulário de consulta da Sefaz (o campo cujo nome fala em
 * "chave" ou que aceita 44 caracteres), se ainda estiver vazio. O usuário só
 * resolve a verificação ("não sou robô") e toca em consultar.
 */
private fun preencherChave(webView: WebView, chave: String) {
    val digitos = chave.filter { it.isDigit() }
    webView.evaluateJavascript(
        """
        (function(c){
          var campos = document.querySelectorAll('input');
          for (var i = 0; i < campos.length; i++) {
            var e = campos[i];
            if (e.type && ['text','tel','number','search'].indexOf(e.type) < 0) continue;
            var nome = ((e.name||'') + ' ' + (e.id||'') + ' ' + (e.placeholder||'')).toLowerCase();
            if (nome.indexOf('chave') >= 0 || e.maxLength >= 44) {
              if (!e.value) {
                e.value = c;
                e.dispatchEvent(new Event('input', {bubbles: true}));
                e.dispatchEvent(new Event('change', {bubbles: true}));
              }
              return true;
            }
          }
          return false;
        })('$digitos')
        """.trimIndent(),
        null,
    )
}

/** HTML atual da página (já com o que o JavaScript da Sefaz montou). */
private suspend fun htmlDaPagina(webView: WebView): String? = suspendCancellableCoroutine { cont ->
    webView.evaluateJavascript("document.documentElement.outerHTML") { resultado ->
        // O resultado vem como string JSON ("\"<html>...\"") ou "null".
        val html = runCatching {
            (Json.parseToJsonElement(resultado) as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
        }.getOrNull()
        if (cont.isActive) cont.resume(html)
    }
}

/**
 * Navegação dentro da Sefaz-RJ, tolerante às falhas do portal:
 *
 *  - Depois do QR Code, o portal redireciona para páginas em http:// cuja porta
 *    está fechada (ERR_CONNECTION_REFUSED): como os navegadores, abrimos em https.
 *  - Mas há endereços que só respondem em http, ou que cortam a conexão em https
 *    (ERR_CONNECTION_RESET), e o portal tem dois endereços (consultadfe e www4).
 *    Se a página principal falhar, tentamos as outras combinações de endereço e
 *    protocolo antes de desistir — e então avisamos [aoFalhar].
 */
private class ClienteSefaz(private val aoFalhar: (String) -> Unit) : WebViewClient() {

    private val hostsSefaz = listOf("consultadfe.fazenda.rj.gov.br", "www4.fazenda.rj.gov.br")
    /** Endereços (exatos) que já falharam: não insistimos neles. */
    private val falharam = mutableSetOf<String>()
    private var tentativas = 0

    private fun ehSefaz(url: Uri) = (url.host ?: "").endsWith("fazenda.rj.gov.br")

    private fun paraHttps(url: Uri): Uri? {
        if (url.scheme != "http" || !ehSefaz(url)) return null
        val seguro = url.buildUpon().scheme("https").build()
        return seguro.takeIf { it.toString() !in falharam }
    }

    /** Mesma página nos outros endereços/protocolos da Sefaz, na ordem de tentativa. */
    private fun alternativas(url: Uri): List<Uri> =
        (listOfNotNull(url.host) + hostsSefaz).distinct().flatMap { host ->
            listOf("https", "http").map { esquema -> url.buildUpon().scheme(esquema).authority(host).build() }
        }.filter { it.toString() != url.toString() && it.toString() !in falharam }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val seguro = paraHttps(request.url) ?: return false
        view.loadUrl(seguro.toString())
        return true
    }

    // Alguns redirecionamentos não passam pelo método acima: corrige no início do carregamento.
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val seguro = paraHttps(Uri.parse(url))
        if (seguro != null) {
            view.stopLoading()
            view.loadUrl(seguro.toString())
            return
        }
        super.onPageStarted(view, url, favicon)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || !ehSefaz(request.url)) {
            super.onReceivedError(view, request, error)
            return
        }
        falharam += request.url.toString()
        val proxima = alternativas(request.url).firstOrNull()
        if (proxima != null && tentativas < MAX_TENTATIVAS) {
            tentativas++
            view.loadUrl(proxima.toString())
        } else {
            aoFalhar(error.description?.toString().orEmpty())
        }
    }

    private companion object {
        const val MAX_TENTATIVAS = 6
    }
}
