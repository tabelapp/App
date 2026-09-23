package br.com.tabelapp.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/** Um produto da nota: nome e preço unitário (o que vai para a busca). */
data class ItemNota(
    val produto: String,
    val precoCentavos: Long,
    val quantidade: Double = 1.0,
    val unidade: String? = null,
)

/** O que foi possível ler da página de consulta da NFC-e na Sefaz. */
data class NotaLida(
    val emitenteNome: String?,
    val emitenteCnpj: String?,
    val emitenteEndereco: String?,
    val itens: List<ItemNota>,
    /** Data de emissão da nota (= data da compra), se a página mostrar. */
    val dataEmissao: LocalDate? = null,
)

/**
 * Lê a página pública de consulta da NFC-e (a que abre pelo QR Code do cupom).
 *
 * A página é carregada no próprio celular do usuário (ver briefing, seção 8 —
 * o portal da Sefaz-RJ bloqueia servidores), e o HTML final vem para cá.
 *
 * Segue o layout padrão do portal NFC-e usado pela Sefaz-RJ e outros estados:
 *   - emitente: `#u20` (nome) e `.txtCenter .text` (CNPJ e endereço)
 *   - itens: linhas de `#tabResult`, com `.txtTit` (produto), `.Rqtd`, `.RUN`,
 *     `.RvlUnit` (valor unitário) e `.valor` (valor total)
 * ⚠️ O layout foi escrito a partir do padrão conhecido, sem acesso a uma nota
 * real do RJ neste ambiente. Se a Sefaz mudar a página, só este arquivo muda.
 */
object LeitorNfce {

    private val numero = Regex("""\d{1,3}(?:\.\d{3})*(?:,\d+)?|\d+(?:[.,]\d+)?""")
    private val cnpjRegex = Regex("""\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}""")

    /** @return null se a página ainda não tem itens (carregando, captcha, erro). */
    fun ler(html: String): NotaLida? {
        val doc = Jsoup.parse(html)
        val itens = lerItens(doc)
        if (itens.isEmpty()) return null
        return NotaLida(
            emitenteNome = doc.selectFirst("#u20, .txtTopo")?.text()?.limpo(),
            emitenteCnpj = lerCnpj(doc),
            emitenteEndereco = lerEndereco(doc),
            itens = itens,
            dataEmissao = lerDataEmissao(doc),
        )
    }

    private val emissaoRegex = Regex("""Emiss[aã]o:?\s*(\d{2}/\d{2}/\d{4})""", RegexOption.IGNORE_CASE)

    private fun lerDataEmissao(doc: Document): LocalDate? =
        emissaoRegex.find(doc.text())?.groupValues?.get(1)?.let {
            try {
                LocalDate.parse(it, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            } catch (e: DateTimeParseException) {
                null
            }
        }

    private fun lerItens(doc: Document): List<ItemNota> {
        val linhas = doc.select("#tabResult tr").ifEmpty { doc.select("tr[id^=Item]") }
        return linhas.mapNotNull { linha ->
            val produto = linha.selectFirst(".txtTit, .txtTit2")?.ownText()?.limpo()
                ?.takeIf { it.isNotEmpty() && !it.equals("Vl. Total", ignoreCase = true) }
                ?: return@mapNotNull null
            val quantidade = linha.valorDe(".Rqtd")?.let(::decimal) ?: 1.0
            val unitario = linha.valorDe(".RvlUnit")?.let(::centavos)
            val total = linha.selectFirst(".valor")?.text()?.let(::centavos)
            val preco = unitario
                ?: total?.let { if (quantidade > 0) (it / quantidade).roundToLong() else it }
                ?: return@mapNotNull null
            if (preco <= 0) return@mapNotNull null
            ItemNota(
                produto = produto,
                precoCentavos = preco,
                quantidade = quantidade,
                unidade = linha.valorDe(".RUN")?.limpo()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun lerCnpj(doc: Document): String? =
        doc.select(".txtCenter .text, .text").asSequence()
            .mapNotNull { cnpjRegex.find(it.text())?.value }
            .firstOrNull()
            ?.filter { it.isDigit() }

    private fun lerEndereco(doc: Document): String? =
        doc.select(".txtCenter .text").asSequence()
            .map { it.text().limpo() }
            .firstOrNull { it.isNotEmpty() && !it.contains("CNPJ", ignoreCase = true) }
            // O portal separa campos vazios com ", ," — deixa só o que tem conteúdo.
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")

    /** Texto do elemento sem o rótulo em negrito (ex.: "<strong>Qtde.:</strong>2" -> "2"). */
    private fun Element.valorDe(seletor: String): String? {
        val el = selectFirst(seletor) ?: return null
        val rotulo = el.selectFirst("strong")?.text().orEmpty()
        return el.text().removePrefix(rotulo).replace(Regex("""^[^\d\p{L}]*"""), "").trim()
    }

    private fun centavos(texto: String): Long? =
        numero.find(texto.replace('\u00A0', ' '))?.value?.let { Dinheiro.parse(it) }

    private fun decimal(texto: String): Double? {
        val v = numero.find(texto)?.value ?: return null
        // "1,5" / "1.234,5" (padrão BR) ou "0.345" (algumas páginas usam ponto).
        return (if (',' in v) v.replace(".", "").replace(',', '.') else v).toDoubleOrNull()
    }

    private fun String.limpo(): String = replace('\u00A0', ' ').replace(Regex("""\s+"""), " ").trim()
}

/**
 * Formulário de envio de NF (manual ou preenchido pelo QR Code).
 * Uma nota tem vários produtos e é enviada de uma vez só, após a confirmação.
 * CPF do comprador nunca é pedido.
 */
data class RascunhoNf(
    val chaveAcesso: String = "",
    val lojaId: String? = null,
    val pdvNome: String = "",
    val pdvEndereco: String = "",
    val itens: List<ItemNota> = emptyList(),
    /** Data da compra (emissão da NF). */
    val dataNf: LocalDate = LocalDate.now(),
) {
    fun erros(hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        val chave = ChaveAcessoNfe.deTexto(chaveAcesso)
        if (chaveAcesso.isNotBlank() && chave == null) {
            add("Chave de acesso inválida: confira os 44 números.")
        }
        if (dataNf.isAfter(hoje)) add("A data da compra não pode ser no futuro.")
        if (dataNf.isBefore(hoje.minusDays(Validade.NF_DIAS))) {
            add("Só aceitamos notas dos últimos ${Validade.NF_DIAS} dias.")
        }
        if (chave != null && chave.anoMes != dataNf.format(DateTimeFormatter.ofPattern("yyMM"))) {
            add("A data da compra não confere com a nota (mês/ano de emissão).")
        }
        if (lojaId == null && pdvNome.isBlank()) add("Informe o nome do estabelecimento.")
        if (itens.isEmpty()) add("Adicione pelo menos um produto.")
        if (itens.any { it.produto.isBlank() }) add("Há produto sem nome.")
        if (itens.any { it.precoCentavos <= 0 }) add("Há produto sem preço.")
    }

    /** Junta linhas repetidas (mesmo produto e mesmo preço), comum em cupom de mercado. */
    fun itensParaEnvio(): List<ItemNota> =
        itens.map { it.copy(produto = it.produto.trim()) }
            .groupBy { Texto.normalizar(it.produto) to it.precoCentavos }
            .map { (_, repetidos) -> repetidos.first().copy(quantidade = repetidos.sumOf { it.quantidade }) }
}
