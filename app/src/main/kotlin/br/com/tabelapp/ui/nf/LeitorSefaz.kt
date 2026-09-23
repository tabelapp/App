package br.com.tabelapp.ui.nf

import android.annotation.SuppressLint
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
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val aoLerAtual by rememberUpdatedState(aoLer)
    val aoDesistirAtual by rememberUpdatedState(aoDesistir)

    AndroidView(
        factory = { contexto ->
            WebView(contexto).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = WebViewClient() // mantém a navegação dentro do app
                loadUrl(url)
                webView = this
            }
        },
        modifier = modifier,
    )

    LaunchedEffect(url) {
        val inicio = System.currentTimeMillis()
        while (System.currentTimeMillis() - inicio < DESISTIR_APOS_MS) {
            delay(INTERVALO_MS)
            val html = webView?.let { htmlDaPagina(it) } ?: continue
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
