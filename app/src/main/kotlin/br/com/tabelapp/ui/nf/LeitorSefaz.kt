package br.com.tabelapp.ui.nf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.tabelapp.core.ClassificadorErros
import br.com.tabelapp.core.EstadosNfce
import br.com.tabelapp.core.LeitorNfce
import br.com.tabelapp.core.NotaLida
import br.com.tabelapp.core.PaginaSefaz
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.erros.Erros
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private const val INTERVALO_MS = 1_500L
private const val DESISTIR_APOS_MS = 90_000L
/** Na consulta pela chave digitada o usuário ainda resolve a verificação da Sefaz: mais tempo. */
private const val DESISTIR_APOS_MS_MANUAL = 240_000L
/** A página da Sefaz precisa abrir em até 20 s; depois disso, tela calma (sem insistir sozinho). */
private const val TEMPO_CARREGAMENTO_MS = 20_000L


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
 *
 * O usuário nunca vê página de erro: enquanto a página não é conferida, um aviso
 * cobre o WebView; falha de rede, HTTP 403/429/5xx, página de bloqueio de IP ou
 * demora acima de 20 s chamam [aoFalhar] (uma vez só, sem novas tentativas).
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
    /** A Sefaz falhou: tipo para a mensagem calma + detalhe técnico (só para o log). */
    aoFalhar: (TipoErro, String) -> Unit = { _, _ -> },
    /** Leitor do HTML do estado da nota (EstadosNfce); padrão: o do RJ. */
    lerNota: (String) -> NotaLida? = LeitorNfce::ler,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val aoLerAtual by rememberUpdatedState(aoLer)
    val aoDesistirAtual by rememberUpdatedState(aoDesistir)
    val aoCapturarHtmlAtual by rememberUpdatedState(aoCapturarHtml)
    val aoFalharAtual by rememberUpdatedState(aoFalhar)

    // A página só aparece depois de conferida (nunca mostramos a página crua de erro/bloqueio).
    var paginaConferida by remember { mutableStateOf(false) }
    var carregouAlguma by remember { mutableStateOf(false) }
    var falhou by remember { mutableStateOf(false) }

    fun falhar(tipo: TipoErro, detalhe: String) {
        if (falhou) return
        falhou = true
        webView?.stopLoading()
        aoFalharAtual(tipo, detalhe)
    }

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
                    webViewClient = ClienteSefaz(
                        aoComecar = { paginaConferida = false },
                        aoTerminar = { view ->
                            // Confere o conteúdo antes de mostrar: bloqueio de IP ou página de erro do navegador.
                            htmlDaPaginaAsync(view) { html ->
                                when {
                                    falhou -> Unit
                                    html != null && PaginaSefaz.ehBloqueio(html) ->
                                        falhar(TipoErro.SEFAZ_INDISPONIVEL, "página de bloqueio de IP da Sefaz (${view.url})")
                                    html != null && PaginaSefaz.ehErroDoNavegador(html) ->
                                        falhar(ClassificadorErros.deFalhaDePagina(Erros.temInternet()),
                                            "página de erro do navegador (${view.url})")
                                    else -> {
                                        carregouAlguma = true
                                        paginaConferida = true
                                    }
                                }
                            }
                        },
                        aoFalhar = { tipo, detalhe -> falhar(tipo, detalhe) },
                    )
                    webChromeClient = WebChromeClient()
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!paginaConferida) {
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

    // A primeira página precisa abrir em até 20 s.
    LaunchedEffect(url) {
        delay(TEMPO_CARREGAMENTO_MS)
        if (!carregouAlguma) {
            falhar(ClassificadorErros.deFalhaDePagina(Erros.temInternet()), "Sefaz não abriu em ${TEMPO_CARREGAMENTO_MS / 1000} s")
        }
    }

    LaunchedEffect(url) {
        val inicio = System.currentTimeMillis()
        val limite = if (chaveParaPreencher != null) DESISTIR_APOS_MS_MANUAL else DESISTIR_APOS_MS
        while (System.currentTimeMillis() - inicio < limite) {
            delay(INTERVALO_MS)
            if (falhou) return@LaunchedEffect
            val wv = webView ?: continue
            chaveParaPreencher?.let { preencherChave(wv, it) }
            val html = htmlDaPagina(wv) ?: continue
            if (PaginaSefaz.ehBloqueio(html)) {
                falhar(TipoErro.SEFAZ_INDISPONIVEL, "página de bloqueio de IP da Sefaz (${wv.url})")
                return@LaunchedEffect
            }
            aoCapturarHtmlAtual(html, wv.url)
            val nota = withContext(Dispatchers.Default) { runCatching { lerNota(html) }.getOrNull() }
            if (nota != null) {
                aoLerAtual(nota)
                return@LaunchedEffect
            }
        }
        if (!falhou) aoDesistirAtual()
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

/** Mesma leitura do HTML, com retorno por callback (usada no fim do carregamento). */
private fun htmlDaPaginaAsync(webView: WebView, aoReceber: (String?) -> Unit) {
    webView.evaluateJavascript("document.documentElement.outerHTML") { resultado ->
        aoReceber(runCatching {
            (Json.parseToJsonElement(resultado) as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
        }.getOrNull())
    }
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
 * Navegação dentro da Sefaz-RJ.
 *
 *  - Depois do QR Code, o portal redireciona para páginas em http:// cuja porta
 *    está fechada (ERR_CONNECTION_REFUSED): como os navegadores, abrimos em https.
 *    (É a mesma página no endereço seguro, não uma nova tentativa.)
 *  - Qualquer falha da página principal (conexão cortada, tempo esgotado, sem
 *    internet, HTTP 403/429/5xx) vai para [aoFalhar] na hora. Não tentamos de
 *    novo sozinhos: insistir com a Sefaz piora o bloqueio.
 */
private class ClienteSefaz(
    private val aoComecar: () -> Unit,
    private val aoTerminar: (WebView) -> Unit,
    private val aoFalhar: (TipoErro, String) -> Unit,
) : WebViewClient() {

    /** Dentro de alguma Sefaz ligada em EstadosNfce (hoje só a do RJ). */
    private fun ehSefaz(url: Uri) = (url.host ?: "").let { host -> EstadosNfce.dominiosAtivos.any { host.endsWith(it) } }

    private fun paraHttps(url: Uri): Uri? =
        if (url.scheme == "http" && ehSefaz(url)) url.buildUpon().scheme("https").build() else null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val seguro = paraHttps(request.url) ?: return false
        view.loadUrl(seguro.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // Alguns redirecionamentos não passam pelo método acima: corrige no início do carregamento.
        val seguro = paraHttps(Uri.parse(url))
        if (seguro != null) {
            view.stopLoading()
            view.loadUrl(seguro.toString())
            return
        }
        aoComecar()
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        aoTerminar(view)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        aoFalhar(
            ClassificadorErros.deFalhaDePagina(Erros.temInternet()),
            "erro de rede ${error.errorCode} ${error.description} em ${request.url}",
        )
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame) return
        val tipo = ClassificadorErros.deStatusHttpSefaz(errorResponse.statusCode) ?: return
        aoFalhar(tipo, "HTTP ${errorResponse.statusCode} em ${request.url}")
    }
}
