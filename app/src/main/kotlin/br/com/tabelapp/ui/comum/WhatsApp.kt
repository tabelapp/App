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

    /**
     * Abre uma conversa com [numero] já com [texto] escrito. Aceita "(24) 98802-9067",
     * "24988029067" ou "5524988029067"; sem código do país, usa o do Brasil (55).
     */
    fun abrirConversa(contexto: Context, numero: String, texto: String) {
        val digitos = numero.filter { it.isDigit() }.let { if (it.length in 10..11) "55$it" else it }
        if (digitos.length < 12) {
            Toast.makeText(contexto, "Número de WhatsApp inválido.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://wa.me/$digitos?text=" + Uri.encode(texto))
        for (pacote in pacotes) {
            try {
                contexto.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pacote))
                return
            } catch (e: ActivityNotFoundException) {
                // Não instalado: tenta o próximo.
            }
        }
        try {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, uri)) // abre no navegador (wa.me)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(contexto, "WhatsApp não encontrado.", Toast.LENGTH_SHORT).show()
        }
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
