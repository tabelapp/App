package br.com.tabelapp.ui.comum

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Envia direto pelo WhatsApp do usuário (pedido do fundador). Tenta o WhatsApp
 * comum e o Business; se nenhum estiver instalado, abre a escolha de app.
 */
object WhatsApp {
    private val pacotes = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun enviarTexto(contexto: Context, texto: String) {
        enviar(contexto, Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        })
    }

    fun enviarImagem(contexto: Context, imagem: Uri, texto: String) {
        enviar(contexto, Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imagem)
            putExtra(Intent.EXTRA_TEXT, texto)
            clipData = ClipData.newRawUri("imagem", imagem)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun enviar(contexto: Context, base: Intent) {
        for (pacote in pacotes) {
            try {
                contexto.startActivity(Intent(base).setPackage(pacote))
                return
            } catch (e: ActivityNotFoundException) {
                // Não instalado: tenta o próximo.
            }
        }
        Toast.makeText(contexto, "WhatsApp não encontrado. Escolha outro app.", Toast.LENGTH_SHORT).show()
        try {
            contexto.startActivity(Intent.createChooser(base, "Compartilhar"))
        } catch (e: ActivityNotFoundException) {
            // Nenhum app para compartilhar.
        }
    }
}
