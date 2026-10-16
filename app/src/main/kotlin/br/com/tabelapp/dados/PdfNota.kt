package br.com.tabelapp.dados

import android.annotation.SuppressLint
import android.content.Context
import android.print.ImpressoraPdf
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume

/**
 * Cópia em PDF da nota fiscal: a mesma página da Sefaz que o app leu, "impressa"
 * em PDF no próprio celular. O arquivo fica só no cache do app (até ser enviado).
 */
object PdfNota {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun gerar(contexto: Context, html: String, enderecoPagina: String?, nomeArquivo: String): File =
        withContext(Dispatchers.Main) {
            val pasta = File(contexto.cacheDir, "compartilhar").apply { mkdirs() }
            val destino = File(pasta, nomeArquivo)
            val webView = WebView(contexto).apply {
                // A página já veio pronta: não roda os scripts de novo (só usa o estilo da Sefaz).
                settings.javaScriptEnabled = false
            }
            try {
                val ok = withTimeout(30_000) {
                    suspendCancellableCoroutine { cont ->
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                val atributos = PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build()
                                ImpressoraPdf(atributos).imprimir(view.createPrintDocumentAdapter(nomeArquivo), destino) {
                                    if (cont.isActive) cont.resume(it)
                                }
                            }
                        }
                        webView.loadDataWithBaseURL(enderecoPagina, html, "text/html", "UTF-8", null)
                    }
                }
                if (!ok || destino.length() == 0L) throw ErroAmigavel("Não consegui gerar o PDF da nota.")
                destino
            } catch (e: ErroAmigavel) {
                throw e
            } catch (e: Exception) {
                throw ErroAmigavel("Não consegui gerar o PDF da nota.", e)
            } finally {
                webView.destroy()
            }
        }
}
