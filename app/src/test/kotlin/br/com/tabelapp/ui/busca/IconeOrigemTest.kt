package br.com.tabelapp.ui.busca

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.OrigemPreco
import br.com.tabelapp.ui.tema.TabelappTema
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ícone de origem do preço no card (1.0.5): desenha os cards de verdade (Android simulado) e
 * confere que o ícone NÃO muda a altura do card — com nome curto, nome em duas linhas, fonte do
 * sistema em 130% e tela estreita. Os prints ficam em app/build/previa-card/ (a CI publica).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w800dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconeOrigemTest {
    @get:Rule val regra = createAndroidComposeRule<ComponentActivity>()
    private val pasta = File("build/previa-card").apply { mkdirs() }

    private fun cot(produto: String, preco: Long, fonte: Fonte) = Cotacao(
        id = produto, produto = produto, precoCentavos = preco, validade = LocalDate.now().plusDays(5), obs = null,
        fonte = fonte, lojaId = null, pdvId = null, pdvNome = "ARMAZEM DO GRÃO LTDA", criadoEm = Instant.EPOCH,
    )

    private val cartoes = listOf(
        cot("ACUCAR REFINADO CARAVELAS PCT 1kg", 378, Fonte.USUARIO_NF),
        cot("LEITE LV UHT INTEGRAL PIRACANJUBA TP 1lt", 498, Fonte.USUARIO_ENCARTE),
        cot("MILHO DE PIPOCA COMBRASIL 500g", 499, Fonte.PDV_MANUAL),
        cot("PR:BATATA MONALISA kg — NOME BEM COMPRIDO PARA OCUPAR DUAS LINHAS NO CARD", 549, Fonte.USUARIO_ENCARTE),
    )

    @Composable
    private fun Lista(prefixo: String, largura: Int, comIcone: Boolean) {
        Column(Modifier.width(largura.dp).background(Color(0xFF16171C)).padding(12.dp).testTag(prefixo)) {
            cartoes.forEachIndexed { i, c ->
                Column(Modifier.padding(vertical = 4.dp).testTag("$prefixo$i")) {
                    CartaoCotacao(
                        cotacao = c, destaque = null, posicao = null, aoAbrir = {}, alternado = i % 2 == 1,
                        origem = if (comIcone) OrigemPreco.de(c.fonte) else null,
                    )
                }
            }
        }
    }

    /** Desenha as listas com e sem ícone; confere que cada card tem a mesma altura e salva o print. */
    private fun conferir(largura: Int, escalaFonte: Float, print: String) {
        regra.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, escalaFonte)) {
                TabelappTema {
                    Row {
                        Lista("com", largura, comIcone = true)
                        Lista("sem", largura, comIcone = false)
                    }
                }
            }
        }
        regra.waitForIdle()
        cartoes.indices.forEach { i ->
            val com = regra.onNodeWithTag("com$i").fetchSemanticsNode().size.height
            val sem = regra.onNodeWithTag("sem$i").fetchSemanticsNode().size.height
            assertEquals("card $i ($print): o ícone não pode mudar a altura", sem, com)
        }
        // Print: desenha a tela inteira num bitmap e recorta a lista "com ícone".
        val raiz = regra.activity.window.decorView.rootView
        val tela = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        regra.runOnUiThread { raiz.draw(Canvas(tela)) }
        val b = regra.onNodeWithTag("com").fetchSemanticsNode().boundsInRoot
        val x = b.left.toInt().coerceAtLeast(0)
        val y = b.top.toInt().coerceAtLeast(0)
        val recorte = Bitmap.createBitmap(tela, x, y, minOf(b.width.toInt(), tela.width - x), minOf(b.height.toInt(), tela.height - y))
        File(pasta, "$print.png").outputStream().use { recorte.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `os tres icones - card do tamanho de sempre`() = conferir(360, 1f, "cards-360dp")

    @Test fun `fonte 130 por cento e tela estreita - card nao cresce`() = conferir(300, 1.3f, "cards-300dp-fonte130")
}
