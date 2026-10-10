package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Situação de um encarte enviado (mesmos códigos do enum `status_encarte`). */
enum class StatusEncarte(val codigo: String, val rotulo: String) {
    PENDENTE("pendente", "Aguardando"),
    APROVADO("aprovado", "Publicado — já está na busca"),
    REJEITADO("rejeitado", "Não publicado");

    companion object {
        fun doCodigo(codigo: String): StatusEncarte = entries.firstOrNull { it.codigo == codigo } ?: PENDENTE
    }
}

/** Um encarte que o usuário já enviou (lista "Meus encartes"). */
data class EncarteEnviado(
    val id: String,
    val pdvNome: String,
    val status: StatusEncarte,
    val validade: LocalDate?,
    val enviadoEm: Instant,
    val motivoRejeicao: String? = null,
    /** Quantos preços o encarte publicou. */
    val itens: Int = 0,
)

/** Um produto com preço lido do encarte. */
data class ItemEncarte(val produto: String, val precoCentavos: Long)

/**
 * Envio de encarte pelo usuário comum (briefing, seção 4 — decisão do fundador):
 * o app lê as fotos, mostra os produtos e preços encontrados e o usuário só
 * confirma (pode desmarcar o que foi lido errado, mas não digita produto nem preço).
 * Os preços vão direto para a busca, valendo até a data impressa no encarte.
 */
data class RascunhoEncarte(
    val quantidadeFotos: Int = 0,
    val lojaId: String? = null,
    val pdvNome: String = "",
    val validade: LocalDate? = null,
    val itens: List<ItemEncarte> = emptyList(),
) {
    fun erros(hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        if (quantidadeFotos == 0) add("Tire ou escolha pelo menos uma foto do encarte.")
        if (quantidadeFotos > MAX_FOTOS) add("Envie no máximo $MAX_FOTOS fotos por encarte.")
        if (lojaId == null && pdvNome.isBlank()) add("Diga de qual estabelecimento é o encarte.")
        when {
            validade == null -> add("Informe até quando valem as ofertas (a data impressa no encarte).")
            validade.isBefore(hoje) -> add("A validade informada já passou.")
            validade.isAfter(hoje.plusDays(Validade.MAXIMO_DIAS)) ->
                add("A validade pode ser de no máximo ${Validade.MAXIMO_DIAS} dias a partir de hoje.")
        }
        if (itens.isEmpty()) add("Selecione pelo menos um produto.")
    }

    companion object {
        const val MAX_FOTOS = 5
    }
}

/** Uma linha de texto reconhecida numa foto (OCR), com a posição em pixels. */
data class LinhaOcr(
    val texto: String,
    val esquerda: Int,
    val topo: Int,
    val direita: Int,
    val base: Int,
    /** Índice da foto de onde a linha veio (posições só se comparam dentro da mesma foto). */
    val foto: Int = 0,
) {
    val altura: Int get() = max(base - topo, 1)
    val largura: Int get() = max(direita - esquerda, 1)
}

/** O que foi possível ler das fotos de um encarte. */
data class EncarteLido(val itens: List<ItemEncarte>, val validade: LocalDate?)

/**
 * Monta a lista de produtos e preços a partir do texto reconhecido nas fotos.
 *
 * Encarte não é tabela: o nome do produto costuma ficar acima ou à esquerda do
 * preço, o preço é grande e às vezes os centavos vêm menores e separados
 * ("4," + "99"). Por isso a leitura usa a POSIÇÃO de cada linha: cada preço fica
 * com o nome mais próximo (preferindo acima/à esquerda), e linhas logo acima
 * do nome (nome em duas linhas) ou entre o nome e o preço ("5kg") são juntadas.
 * O resultado é sempre mostrado ao usuário para confirmar.
 */
object LeitorEncarte {
    const val MAX_ITENS = 200

    private data class Caixa(val esquerda: Int, val topo: Int, val direita: Int, val base: Int) {
        val altura get() = max(base - topo, 1)
        val centroX get() = (esquerda + direita) / 2
    }

    private class Preco(val centavos: Long, val caixa: Caixa, val porKg: Boolean)

    private fun LinhaOcr.caixa() = Caixa(esquerda, topo, direita, base)

    // "4,99", "R$ 4,99", "R$4.99", "12, 90" — mas não datas ("23.09.2026") nem pesos ("1,5kg").
    private val precoCompleto = Regex("""(?i)(?<![\d/,.])(?:r\s?\$\s*)?(\d{1,4})\s?[,.]\s?(\d{2})(?![\d,./])""")
    // Centavos em fonte menor, separados: "4," ou "R$ 4" numa linha e "99" em outra.
    private val parteInteira = Regex("""(?i)^\s*(?:r\s?\$\s*)?(\d{1,4})\s*[,.]?\s*$""")
    private val parteCentavos = Regex("""(?i)^\s*[,.]?\s*(\d{2})\s*(?:/?\s*(kg|un|und|cada))?\s*$""")
    private val kg = Regex("""(?i)(^|[^a-z])kg\b""")
    private val unidadeDeMedida = Regex("""(?i)\d+\s?(kg|g|gr|mg|ml|l|lt|litros?|un|und|unid)\b""")

    // Linhas que não são nome de produto (chamadas, avisos, rodapé, endereço...).
    private val naoEhProduto = listOf(
        "valid", "oferta", "promoc", "estoque", "imagen", "ilustrativ", "www", "http", ".com", "@",
        "whatsapp", "zap", "telefone", "tel:", "horario", "cnpj", "endereco", "entrega", "delivery",
        "cartao", "pix", "parcel", "limite", "por cliente", "enquanto durar", "aproveite", "imperdivel",
        "confira", "so hoje", "somente", "leve ", "pague", "desconto", "economiz", "rua ", "avenida",
        "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo", "fim de semana",
        "ate ", "reservamo", "erros de", "clube", "app ", "baixe",
    )
    private val palavrasVazias = setOf(
        "r", "rs", "kg", "un", "und", "unid", "unidade", "cada", "o", "a", "de", "por", "apenas", "so",
        "pct", "cx", "lt", "g", "gr", "ml", "l", "e", "com", "ou", "x",
    )

    fun ler(linhas: List<LinhaOcr>, hoje: LocalDate): EncarteLido {
        val itens = linhas.groupBy { it.foto }.toSortedMap().values
            .flatMap { lerFoto(it) }
            .distinctBy { Texto.normalizar(it.produto) }
            .take(MAX_ITENS)
        return EncarteLido(itens, validade(linhas.joinToString("\n") { it.texto }, hoje))
    }

    private fun lerFoto(linhas: List<LinhaOcr>): List<ItemEncarte> {
        val diretos = mutableListOf<Pair<ItemEncarte, Caixa>>() // nome e preço na mesma linha
        val precos = mutableListOf<Preco>()
        val nomes = mutableListOf<LinhaOcr>()
        val complementos = mutableListOf<LinhaOcr>() // "5kg", "Tipo 1", "Pct 500g"
        val usadas = mutableSetOf<LinhaOcr>()

        // Centavos separados da parte inteira.
        for (inteira in linhas) {
            if (inteira in usadas) continue
            val mi = parteInteira.matchEntire(inteira.texto) ?: continue
            val centavos = linhas.filter { it !in usadas && it !== inteira }
                .mapNotNull { c -> parteCentavos.matchEntire(c.texto)?.let { c to it } }
                .filter { (c, _) ->
                    c.esquerda >= inteira.caixa().centroX &&
                        c.esquerda - inteira.direita <= inteira.altura &&
                        c.topo >= inteira.topo - inteira.altura * 0.4 &&
                        c.topo <= inteira.topo + inteira.altura * 0.6
                }
                .minByOrNull { (c, _) -> c.esquerda - inteira.direita }
                ?: continue
            val (linhaCent, mc) = centavos
            val valor = mi.groupValues[1].toLong() * 100 + mc.groupValues[1].toLong()
            val caixa = Caixa(
                inteira.esquerda, min(inteira.topo, linhaCent.topo),
                max(inteira.direita, linhaCent.direita), max(inteira.base, linhaCent.base),
            )
            if (valor in 1..999_999) precos += Preco(valor, caixa, mc.groupValues[2].equals("kg", ignoreCase = true))
            usadas += inteira
            usadas += linhaCent
        }

        for (linha in linhas) {
            if (linha in usadas) continue
            val achados = precoCompleto.findAll(linha.texto).toList()
            if (achados.isNotEmpty()) {
                val m = achados.last() // "De 5,99 por 4,99": vale o último
                val valor = m.groupValues[1].toLong() * 100 + m.groupValues[2].toLong()
                val antes = linha.texto.substring(0, achados.first().range.first)
                val resto = linha.texto.removeRange(m.range)
                val porKg = kg.containsMatchIn(linha.texto.substring(m.range.last + 1))
                val nome = limparNome(antes)
                when {
                    valor !in 1..999_999 -> {}
                    ehPrecoAntigo(resto) -> {}
                    nome != null && ehNome(nome) -> diretos += ItemEncarte(comKg(nome, porKg), valor) to linha.caixa()
                    else -> precos += Preco(valor, linha.caixa(), porKg || kg.containsMatchIn(resto))
                }
                continue
            }
            when {
                ehNome(linha.texto) -> nomes += linha
                ehComplemento(linha.texto) -> complementos += linha
            }
        }

        // Cada preço fica com o nome mais próximo; nenhum nome serve a dois preços.
        val pares = precos.flatMap { p -> nomes.mapNotNull { n -> custo(p.caixa, n)?.let { Triple(p, n, it) } } }
            .sortedBy { it.third }
        val nomeDoPreco = linkedMapOf<Preco, LinhaOcr>()
        val nomesUsados = mutableSetOf<LinhaOcr>()
        for ((p, n, _) in pares) {
            if (p in nomeDoPreco || n in nomesUsados) continue
            nomeDoPreco[p] = n
            nomesUsados += n
        }

        val complementosUsados = mutableSetOf<LinhaOcr>()
        val lidos = precos.mapNotNull { p ->
            val principal = nomeDoPreco[p] ?: return@mapNotNull null
            val partes = mutableListOf(principal)
            // Nome em mais de uma linha: até 2 linhas logo acima, alinhadas com ele.
            var topo = principal
            repeat(2) {
                val acima = (nomes + complementos)
                    .filter { it !in nomesUsados && it !in complementosUsados && it !in partes }
                    .filter { it.base <= topo.topo + topo.altura * 0.3 && topo.topo - it.base <= topo.altura * 0.8 }
                    .filter { sobreposicaoHorizontal(it, topo) }
                    .minByOrNull { topo.topo - it.base } ?: return@repeat
                partes += acima
                if (acima in nomes) nomesUsados += acima else complementosUsados += acima
                topo = acima
            }
            // Complemento entre o nome e o preço ("5kg", "Tipo 1").
            complementos
                .filter { it !in complementosUsados }
                .filter { it.topo >= principal.topo && it.base <= p.caixa.topo + p.caixa.altura * 0.5 }
                .filter { sobreposicaoHorizontal(it, principal) || sobreposicaoHorizontal(it, p.caixa) }
                .filter { it.topo - principal.base <= principal.altura * 1.5 }
                .forEach { partes += it; complementosUsados += it }

            val nome = partes.sortedWith(compareBy({ it.topo }, { it.esquerda }))
                .joinToString(" ") { it.texto }
                .let(::limparNome) ?: return@mapNotNull null
            ItemEncarte(comKg(nome, p.porKg), p.centavos) to p.caixa
        }

        // Ordem de leitura: de cima para baixo, da esquerda para a direita.
        return (diretos + lidos).sortedWith(compareBy({ it.second.topo }, { it.second.esquerda })).map { it.first }
    }

    /** Custo de ligar um preço a uma linha de nome; null = longe demais. */
    private fun custo(p: Caixa, n: LinhaOcr): Double? {
        val escala = p.altura.toDouble()
        val sobrepoe = min(p.direita, n.direita) - max(p.esquerda, n.esquerda)
        val distH = max(0, -sobrepoe).toDouble()
        val custo = when {
            n.base <= p.topo + escala * 0.5 -> max(0, p.topo - n.base) + distH * 1.5          // acima
            n.topo >= p.base - escala * 0.5 -> max(0, n.topo - p.base) * 2.5 + distH * 1.5 + escala * 0.5 // abaixo
            n.direita <= p.esquerda + escala -> distH                                          // à esquerda
            else -> distH * 2 + escala * 0.5                                                   // à direita
        }
        return custo.takeIf { it <= escala * 3.5 }
    }

    private fun sobreposicaoHorizontal(a: LinhaOcr, b: LinhaOcr): Boolean {
        val s = min(a.direita, b.direita) - max(a.esquerda, b.esquerda)
        return s >= min(a.largura, b.largura) * 0.3
    }

    private fun sobreposicaoHorizontal(a: LinhaOcr, b: Caixa): Boolean {
        val s = min(a.direita, b.direita) - max(a.esquerda, b.esquerda)
        return s >= min(a.largura, max(b.direita - b.esquerda, 1)) * 0.3
    }

    /** Palavras do aviso contam só no começo de uma palavra ("ate" não pega "tomate"). */
    private fun temAviso(normalizado: String): Boolean {
        val t = " $normalizado "
        return naoEhProduto.any { if (it.first().isLetter()) " $it" in t else it in t }
    }

    private fun ehPrecoAntigo(resto: String): Boolean {
        val r = Texto.normalizar(resto).replace(Regex("[^a-z ]"), " ").trim()
        return (r == "de" || r.startsWith("de ") || r.endsWith(" de")) && "por" !in r.split(' ')
    }

    private fun ehNome(texto: String): Boolean {
        val n = Texto.normalizar(texto)
        if (temAviso(n)) return false
        val palavras = n.replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }
        val boas = palavras.filter { p -> p !in palavrasVazias && p.count { it.isLetter() } >= 3 }
        return boas.isNotEmpty() && n.count { it.isLetter() } >= 3
    }

    private fun ehComplemento(texto: String): Boolean {
        val n = Texto.normalizar(texto)
        if (n.length > 30 || temAviso(n)) return false
        return unidadeDeMedida.containsMatchIn(n) || Regex("""^tipo \d$""").matches(n)
    }

    private fun limparNome(texto: String): String? {
        val limpo = texto
            .replace(Regex("""(?i)r\s?\$"""), " ")
            .replace(Regex("""[*•|_~=<>#]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', '.', ',', ';', '/')
            .take(120)
            .trim()
        return limpo.takeIf { it.length >= 2 }
    }

    private fun comKg(nome: String, porKg: Boolean): String =
        if (porKg && !kg.containsMatchIn(nome)) "$nome (kg)" else nome

    // ------------------------------------------------------------------ validade

    private const val DATA = """(\d{1,2})\s*/\s*(\d{1,2})(?:\s*/\s*(\d{2,4}))?"""
    private val meses = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    private val MES = """(\d{1,2})\s*(?:de\s*)?(${meses.joinToString("|")})(?:\s*(?:de\s*)?(\d{4}))?"""

    // "até 30/09", "válido até o dia 30/09/2026", "até 30 de setembro"
    private val ateData = Regex("""ate\s*(?:o\s*)?(?:dia\s*)?$DATA""")
    private val ateMes = Regex("""ate\s*(?:o\s*)?(?:dia\s*)?$MES""")
    // "de 20/09 a 26/09", "20 a 26/09", "20/09 - 26/09", "de 20 a 26 de setembro"
    private val faixaData = Regex("""\d{1,2}(?:\s*/\s*\d{1,2})?(?:\s*/\s*\d{2,4})?\s*(?:a|-|–)\s*$DATA""")
    private val faixaMes = Regex("""\d{1,2}(?:\s*de\s*[a-z]+)?\s*(?:a|-|–)\s*$MES""")

    /**
     * Até quando valem as ofertas, se o encarte disser ("válido até 30/09",
     * "ofertas de 20 a 26/09"...). Só aceita datas de hoje até 30 dias à frente;
     * fora disso (ou sem data), o usuário informa.
     */
    fun validade(texto: String, hoje: LocalDate): LocalDate? {
        val t = Texto.normalizar(texto)
        val candidatas = buildList {
            ateData.findAll(t).forEach { add(data(it.groupValues[1], it.groupValues[2], it.groupValues[3], hoje)) }
            ateMes.findAll(t).forEach { add(dataMes(it.groupValues[1], it.groupValues[2], it.groupValues[3], hoje)) }
            faixaData.findAll(t).forEach { add(data(it.groupValues[1], it.groupValues[2], it.groupValues[3], hoje)) }
            faixaMes.findAll(t).forEach { add(dataMes(it.groupValues[1], it.groupValues[2], it.groupValues[3], hoje)) }
        }
        return candidatas.filterNotNull()
            .filter { !it.isBefore(hoje) && !it.isAfter(hoje.plusDays(Validade.MAXIMO_DIAS)) }
            .maxOrNull()
    }

    private fun data(dia: String, mes: String, ano: String, hoje: LocalDate): LocalDate? =
        montar(dia.toIntOrNull(), mes.toIntOrNull(), ano, hoje)

    private fun dataMes(dia: String, mes: String, ano: String, hoje: LocalDate): LocalDate? =
        montar(dia.toIntOrNull(), meses.indexOf(mes) + 1, ano, hoje)

    private fun montar(dia: Int?, mes: Int?, ano: String, hoje: LocalDate): LocalDate? {
        if (dia == null || mes == null || mes !in 1..12) return null
        val anoInformado = ano.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        return runCatching {
            val d = LocalDate.of(anoInformado ?: hoje.year, mes, dia)
            // Encarte de dezembro com oferta até janeiro: sem ano, é o próximo.
            if (anoInformado == null && d.isBefore(hoje.minusDays(180))) d.plusYears(1) else d
        }.getOrNull()
    }
}
