package br.com.tabelapp.ui.busca

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import br.com.tabelapp.R
import br.com.tabelapp.core.ArtePreco
import br.com.tabelapp.core.ConteudoArte
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.PrecoExibido

/**
 * A arte (1080x1080) que vai pelo WhatsApp ao compartilhar um preço:
 *  - faixa amarela no topo com a LUPA da marca + "Tabelapp" (sempre);
 *  - produto, preço em vermelho (com o selo "no app" e o preço normal riscado quando houver);
 *  - local, endereço, validade e OBS (cortam com reticências, nunca saem da imagem);
 *  - rodapé vermelho com a chamada e a Tabelinha (mascote) saindo dele, no canto direito.
 * A Tabelinha é carregada uma vez; se falhar, a arte sai sem ela (nunca trava o compartilhamento).
 */
object ArteCompartilhar {
    const val LADO = 1080
    private const val MARGEM = 64f
    private const val FAIXA = 190f
    private const val RODAPE = 130f
    private val AMARELO = Color.parseColor("#FDDE03")
    private val VERMELHO = Color.parseColor("#B00000")
    private val PRETO = Color.parseColor("#1A1A1A")
    private val CINZA = Color.parseColor("#5F5F5F")

    /** Tabelinha já decodificada (só uma vez por processo). */
    @Volatile private var tabelinha: Bitmap? = null
    @Volatile private var tabelinhaFalhou = false

    private fun tabelinha(res: Resources): Bitmap? {
        tabelinha?.let { return it }
        if (tabelinhaFalhou) return null
        return runCatching { BitmapFactory.decodeResource(res, R.drawable.tabelinha_compartilhar) }
            .getOrNull()
            .also { if (it == null) tabelinhaFalhou = true else tabelinha = it }
    }

    fun desenhar(res: Resources, c: Cotacao): Bitmap = desenhar(res, ArtePreco.conteudo(c))

    fun desenhar(res: Resources, a: ConteudoArte): Bitmap {
        val bmp = Bitmap.createBitmap(LADO, LADO, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val larguraCheia = (LADO - 2 * MARGEM).toInt()

        // Tabelinha: canto inferior direito, "saindo" do rodapé (desenhada por último, por cima dele).
        val mascote = tabelinha(res)
        val alturaMascote = 380f
        val larguraMascote = mascote?.let { alturaMascote * it.width / it.height } ?: 0f
        val topoMascote = LADO - 18f - alturaMascote
        // Abaixo do topo da Tabelinha o texto não passa da borda esquerda dela.
        val larguraEstreita = if (mascote != null) (LADO - MARGEM - larguraMascote - 36f - MARGEM).toInt() else larguraCheia
        val limiteTexto = LADO - RODAPE - 16f

        // 1. Faixa amarela: lupa (marca, sempre) + "Tabelapp" + "Quem pesquisa economiza".
        canvas.drawRect(0f, 0f, LADO.toFloat(), FAIXA, Paint().apply { color = AMARELO })
        // O vetor da lupa tem margem interna (cabe no círculo do ícone): a área desenhada é maior que a lupa visível.
        val lado = 200
        val esquerdaLupa = MARGEM.toInt() - 54
        val lupa: Drawable? = runCatching { ResourcesCompat.getDrawable(res, R.drawable.lupa_marca, null) }.getOrNull()
        val xTexto = if (lupa != null) {
            lupa.setBounds(esquerdaLupa, ((FAIXA - lado) / 2).toInt(), esquerdaLupa + lado, ((FAIXA + lado) / 2).toInt())
            lupa.draw(canvas)
            esquerdaLupa + lado - 30f
        } else {
            MARGEM
        }
        canvas.drawText("Tabelapp", xTexto, 106f, texto(80f, VERMELHO, negrito = true))
        canvas.drawText("Quem pesquisa economiza", xTexto, 154f, texto(36f, PRETO))

        // 2. Produto (até 3 linhas).
        var y = FAIXA + 50f
        y = escrever(canvas, a.produto, texto(60f, PRETO, negrito = true), larguraCheia, y, 3, limiteTexto) + 16f

        // 3. Preço: o principal em destaque; "no app" ao lado (ou abaixo, se não couber); normal riscado.
        val prefixo = if (a.aPartirDe) PrecoExibido.A_PARTIR_DE.lowercase() + " " else ""
        val pPreco = texto(140f, VERMELHO, negrito = true)
        val pSufixo = texto(54f, VERMELHO, negrito = true)
        val pPrefixo = texto(44f, VERMELHO, negrito = true)
        val pSelo = texto(46f, PRETO, negrito = true)
        val larguraSelo = if (a.seloApp) pSelo.measureText(ArtePreco.SELO) + 44f else 0f
        val sufixo = if (a.sufixoUnidade.isNotEmpty()) " " + a.sufixoUnidade else ""
        fun larguraLinha() = pPrefixo.measureText(prefixo) + pPreco.measureText(a.preco) + pSufixo.measureText(sufixo)
        // Nada sai da imagem: diminui o preço até caber.
        while (larguraLinha() > larguraCheia && pPreco.textSize > 70f) {
            pPreco.textSize -= 6f
            pSufixo.textSize = pPreco.textSize * 0.38f
        }
        val base = y + pPreco.textSize * 0.82f
        var x = MARGEM
        if (prefixo.isNotEmpty()) {
            canvas.drawText(prefixo, x, base, pPrefixo)
            x += pPrefixo.measureText(prefixo)
        }
        canvas.drawText(a.preco, x, base, pPreco)
        x += pPreco.measureText(a.preco)
        if (sufixo.isNotEmpty()) {
            canvas.drawText(sufixo, x, base, pSufixo)
            x += pSufixo.measureText(sufixo)
        }
        y = base + 24f
        var xLinha2 = MARGEM
        var seloAbaixo = false
        if (a.seloApp) {
            val cabeAoLado = x + 24f + larguraSelo <= LADO - MARGEM
            seloAbaixo = !cabeAoLado
            val (sx, sy) = if (cabeAoLado) (x + 24f) to (base - 62f) else MARGEM to y
            selo(canvas, sx, sy, larguraSelo, pSelo)
            // Selo na linha de baixo: o preço normal vai ao lado dele.
            if (!cabeAoLado) xLinha2 = MARGEM + larguraSelo + 24f
        }
        a.precoNormal?.let { normal ->
            val pNormal = texto(44f, CINZA).apply { isStrikeThruText = true }
            val rotulo = texto(34f, CINZA)
            val yNormal = y + 50f
            canvas.drawText(normal, xLinha2, yNormal, pNormal)
            canvas.drawText(" " + ArtePreco.ROTULO_NORMAL, xLinha2 + pNormal.measureText(normal), yNormal, rotulo)
        }
        if (seloAbaixo || a.precoNormal != null) y += 74f
        y += 20f

        // 4. Local, endereço e validade/OBS: abaixo do topo da Tabelinha, mais estreito.
        fun largura(yAtual: Float) = if (yAtual + 50f > topoMascote) larguraEstreita else larguraCheia
        if (a.local.isNotBlank()) {
            y = escrever(canvas, a.local, texto(42f, PRETO, negrito = true), largura(y), y, 2, limiteTexto) + 6f
        }
        a.endereco?.let { y = escrever(canvas, it, texto(34f, CINZA), largura(y), y, 2, limiteTexto) + 6f }
        escrever(canvas, a.detalhes, texto(30f, CINZA), largura(y), y, 2, limiteTexto)

        // 5. Rodapé vermelho com a chamada, à esquerda da Tabelinha.
        canvas.drawRect(RectF(0f, LADO - RODAPE, LADO.toFloat(), LADO.toFloat()), Paint().apply { color = VERMELHO })
        val chamada = "Pesquise mais preços com o Tabelapp"
        val larguraChamada = if (mascote != null) LADO - larguraMascote - 40f - MARGEM else LADO - 2 * MARGEM
        val pChamada = texto(40f, Color.WHITE, negrito = true)
        while (pChamada.measureText(chamada) > larguraChamada && pChamada.textSize > 24f) pChamada.textSize -= 2f
        if (mascote != null) {
            canvas.drawText(chamada, MARGEM, LADO - RODAPE / 2f + pChamada.textSize / 3f, pChamada)
            val destino = RectF(LADO - 24f - larguraMascote, topoMascote, LADO - 24f, topoMascote + alturaMascote)
            canvas.drawBitmap(mascote, null, destino, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            pChamada.textAlign = Paint.Align.CENTER
            canvas.drawText(chamada, LADO / 2f, LADO - RODAPE / 2f + pChamada.textSize / 3f, pChamada)
        }
        return bmp
    }

    /** Selo "no app": pílula amarela com contorno preto (as cores da marca). */
    private fun selo(canvas: Canvas, x: Float, topo: Float, largura: Float, paint: TextPaint) {
        val altura = 66f
        val r = RectF(x, topo, x + largura, topo + altura)
        canvas.drawRoundRect(r, altura / 2, altura / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMARELO })
        canvas.drawRoundRect(r, altura / 2, altura / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PRETO; style = Paint.Style.STROKE; strokeWidth = 4f
        })
        canvas.drawText(ArtePreco.SELO, x + 22f, topo + altura / 2 + paint.textSize / 3f, paint)
    }

    private fun texto(tamanho: Float, cor: Int, negrito: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho
        color = cor
        typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /**
     * Escreve com quebra de linha a partir de [y] (topo), com reticências; só as linhas que cabem
     * antes de [limite] (nada sai da imagem nem invade o rodapé). Devolve o y logo abaixo do texto.
     */
    private fun escrever(canvas: Canvas, texto: String, paint: TextPaint, largura: Int, y: Float, maxLinhas: Int, limite: Float): Float {
        val alturaLinha = paint.fontSpacing
        val cabem = ((limite - y) / alturaLinha).toInt().coerceAtMost(maxLinhas)
        if (cabem <= 0 || texto.isBlank()) return y
        val layout = StaticLayout.Builder.obtain(texto, 0, texto.length, paint, largura.coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(cabem)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(MARGEM, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }
}
