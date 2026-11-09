package br.com.tabelapp.ui.busca

import android.graphics.Bitmap
import android.graphics.Color
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.Unidade
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gera a arte de compartilhar de verdade (Android simulado, desenho nativo) nos casos pedidos e
 * salva as prévias em app/build/previa-arte/ (a CI publica como artefato "previa-arte").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArteCompartilharTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val pasta = File("build/previa-arte").apply { mkdirs() }

    private fun cot(
        produto: String, preco: Long, normal: Long? = null, app: Boolean = false, fonte: Fonte = Fonte.USUARIO_ENCARTE,
        unidade: Unidade = Unidade.UNIDADE, pdv: String = "Assaí Atacadista", endereco: String = "Rua Washington Luiz, 300 - Centro, Petrópolis",
    ) = Cotacao(
        id = "1", produto = produto, precoCentavos = preco, validade = LocalDate.now().plusDays(6), obs = null,
        fonte = fonte, lojaId = null, pdvId = null, pdvNome = pdv, endereco = endereco, criadoEm = Instant.EPOCH,
        unidade = unidade, precoNormalCentavos = normal, precoApp = app,
        dataNf = if (fonte == Fonte.USUARIO_NF) LocalDate.now().minusDays(1) else null,
    )

    private fun gerar(nome: String, c: Cotacao): Bitmap {
        val bmp = ArteCompartilhar.desenhar(res, c)
        File(pasta, "$nome.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(1080, bmp.width)
        assertEquals(1080, bmp.height)
        // Faixa amarela no topo (à direita, longe da lupa) e rodapé vermelho (à esquerda, longe da Tabelinha).
        assertEquals(Color.parseColor("#FDDE03"), bmp.getPixel(1070, 10))
        assertEquals(Color.parseColor("#B00000"), bmp.getPixel(10, 1075))
        return bmp
    }

    @Test fun `nome curto`() { gerar("1-nome-curto", cot("Café Pilão 500g", 1899)) }

    @Test fun `nome muito longo`() {
        gerar("2-nome-longo", cot(
            "Sabão em Pó Omo Lavagem Perfeita Multiação Tira Manchas Pacote Econômico Edição Especial 2,2kg com Brinde",
            12990, pdv = "Supermercado Serra Imperial Loja Centro Histórico de Petrópolis — Unidade 2",
            endereco = "Rua do Imperador, 1500, Loja 3, Galeria Central, Centro Histórico, Petrópolis - RJ, CEP 25620-000",
        ))
    }

    @Test fun `so preco do app`() { gerar("3-so-app", cot("Leite Integral Italac 1L", 479, app = true)) }

    @Test fun `dois precos - app e normal riscado`() { gerar("4-dois-precos", cot("Picanha Bovina Friboi", 6990, 7990, app = true, unidade = Unidade.KG)) }

    @Test fun `nota fiscal sem selo`() {
        val bmp = gerar("5-nota-fiscal", cot("Arroz Tio João 5kg", 2349, fonte = Fonte.USUARIO_NF, app = true))
        assertTrue(bmp.width > 0)
    }
}
