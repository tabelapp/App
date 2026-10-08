package br.com.tabelapp.ui.nf

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
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val aoLerAtual by rememberUpdatedState(aoLer)
    val aoDesistirAtual by rememberUpdatedState(aoDesistir)
    val aoCapturarHtmlAtual by rememberUpdatedState(aoCapturarHtml)

    AndroidView(
        factory = { contexto ->
            WebView(contexto).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = ClienteSefaz() // mantém a navegação dentro do app e força https
                loadUrl(url)
                webView = this
            }
        },
        modifier = modifier,
    )

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
 * O portal da Sefaz-RJ, depois do QR Code, redireciona para
 * http://consultadfe.fazenda.rj.gov.br/.../resultadoQRCode2.faces — mas a porta http
 * do servidor está fechada (ERR_CONNECTION_REFUSED). Os navegadores trocam para https
 * sozinhos; aqui fazemos o mesmo: qualquer página http da Sefaz-RJ é aberta em https.
 */
private class ClienteSefaz : WebViewClient() {

    private fun paraHttps(url: Uri): Uri? =
        if (url.scheme == "http" && (url.host ?: "").endsWith("fazenda.rj.gov.br")) {
            url.buildUpon().scheme("https").build()
        } else {
            null
        }

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

    // Última garantia: se a página principal em http falhar, tenta a mesma em https.
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        val seguro = if (request.isForMainFrame) paraHttps(request.url) else null
        if (seguro != null) view.loadUrl(seguro.toString()) else super.onReceivedError(view, request, error)
    }
}
