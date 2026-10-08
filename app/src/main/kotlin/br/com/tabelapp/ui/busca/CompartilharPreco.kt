package br.com.tabelapp.ui.busca

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import br.com.tabelapp.core.Compartilhamento
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Obs
import br.com.tabelapp.core.Validade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compartilha o "card de preço": uma imagem com produto, preço e local (nas
 * cores da marca) + o texto "Olha esse preço!... Quem pesquisa economiza" com
 * o link para baixar o app. Abre a escolha do app (WhatsApp, Instagram...).
 */
object CompartilharPreco {

    private const val LARGURA = 1080
    private const val ALTURA = 1080
    private const val MARGEM = 72f
    private val AMARELO = Color.parseColor("#FFE000")
    private val VERMELHO = Color.parseColor("#C00000")
    private val PRETO = Color.parseColor("#1A1A1A")
    private val CINZA = Color.parseColor("#5F5F5F")

    suspend fun compartilhar(contexto: Context, cotacao: Cotacao) {
        val arquivo = withContext(Dispatchers.Default) {
            val bitmap = desenhar(cotacao)
            val pasta = File(contexto.cacheDir, "compartilhar").apply { mkdirs() }
            File(pasta, "preco.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, Compartilhamento.texto(cotacao))
            clipData = ClipData.newRawUri("preco", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        abrir(contexto, Intent.createChooser(envio, "Compartilhar preço"))
    }

    private fun desenhar(c: Cotacao): Bitmap {
        val bmp = Bitmap.createBitmap(LARGURA, ALTURA, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val larguraTexto = (LARGURA - 2 * MARGEM).toInt()

        // Faixa amarela com a marca.
        val faixa = 200f
        canvas.drawRect(0f, 0f, LARGURA.toFloat(), faixa, Paint().apply { color = AMARELO })
        canvas.drawText("Tabelapp", MARGEM, 115f, texto(84f, VERMELHO, negrito = true))
        canvas.drawText("Quem pesquisa economiza", MARGEM, 165f, texto(38f, PRETO))

        var y = faixa + 70f
        // Produto (até 3 linhas) e preço em destaque.
        y = escrever(canvas, c.produto, texto(64f, PRETO, negrito = true), larguraTexto, y, maxLinhas = 3) + 20f
        val preco = texto(150f, VERMELHO, negrito = true)
        canvas.drawText(Dinheiro.formatar(c.precoCentavos), MARGEM, y + 140f, preco)
        y += 200f

        // Local, endereço, validade e OBS.
        val local = listOfNotNull(c.pdvNome, c.lojaNome).joinToString(" — ")
        y = escrever(canvas, local, texto(44f, PRETO, negrito = true), larguraTexto, y, maxLinhas = 2) + 8f
        c.endereco?.let { y = escrever(canvas, it, texto(36f, CINZA), larguraTexto, y, maxLinhas = 2) + 8f }
        y = escrever(canvas, "${Validade.exibir(c)} · ${Obs.exibir(c)}", texto(32f, CINZA), larguraTexto, y, maxLinhas = 2)

        // Rodapé vermelho com a chamada.
        val rodape = 130f
        canvas.drawRect(RectF(0f, ALTURA - rodape, LARGURA.toFloat(), ALTURA.toFloat()), Paint().apply { color = VERMELHO })
        val chamada = texto(40f, Color.WHITE, negrito = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Pesquise mais preços com o Tabelapp", LARGURA / 2f, ALTURA - rodape / 2f + 14f, chamada)
        return bmp
    }

    private fun texto(tamanho: Float, cor: Int, negrito: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho
        color = cor
        typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Escreve com quebra de linha a partir de [y] (topo); devolve o y logo abaixo do texto. */
    private fun escrever(canvas: Canvas, texto: String, paint: TextPaint, largura: Int, y: Float, maxLinhas: Int): Float {
        val layout = StaticLayout.Builder.obtain(texto, 0, texto.length, paint, largura)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLinhas)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(MARGEM, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }
}
