package br.com.tabelapp.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Leitor de encartes da aba Admin (1.0.4): a função `ler-encarte` lê a imagem e devolve os
 * itens; o Admin confere numa tabela editável e só então lança (`admin_lancar_encarte`).
 * Nada é lançado sem a conferência.
 */
data class ItemLidoEncarte(
    val produto: String,
    /** Preço principal (o menor; o do app quando houver dois). */
    val precoCentavos: Long,
    /** Preço normal (maior), só junto com o preço do app. */
    val precoNormalCentavos: Long? = null,
    val precoApp: Boolean = false,
    val unidade: Unidade = Unidade.UNIDADE,
    val validade: LocalDate? = null,
    val obs: String? = null,
    /** Bebida alcoólica: vem desmarcada e em destaque (o Admin decide). */
    val alcoolico: Boolean = false,
    /** Vai ser lançado? */
    val marcado: Boolean = !alcoolico,
)

/** Algo do encarte que não virou item (preço ilegível, etc.), com o motivo. */
data class IgnoradoEncarte(val texto: String, val motivo: String)

data class LeituraEncarte(
    val lojaNome: String? = null,
    val itens: List<ItemLidoEncarte> = emptyList(),
    val ignorados: List<IgnoradoEncarte> = emptyList(),
)

/** Regras de preço do encarte (as mesmas da tabela que o fundador usa e do banco). */
object RegrasEncarte {
    const val OBS_MAX = 140
    const val MAX_ITENS = 300
    /** Validade aceita: vencida há no máximo 30 dias, e no máximo 60 dias à frente. */
    const val DIAS_VENCIDA = 30L
    const val DIAS_A_FRENTE = 60L
    const val MAX_PRECO = 99_999_999L

    /**
     * Arruma um item lido:
     *  - dois preços (normal e app/clube): o menor é o preço (com `precoApp`), o maior é o normal;
     *  - preço normal só existe junto com o preço do app; igual ou menor que o preço, some;
     *  - só preço do app: preço com `precoApp`, sem normal; só preço comum: `precoApp = false`;
     *  - nome e OBS sem espaços sobrando; OBS com no máximo 140 caracteres.
     */
    fun normalizar(item: ItemLidoEncarte): ItemLidoEncarte {
        var preco = item.precoCentavos
        var normal = item.precoNormalCentavos
        var app = item.precoApp
        if (normal != null && normal < preco) {
            // Veio invertido: o menor é o do app.
            val menor = normal
            normal = preco
            preco = menor
            app = true
        }
        if (normal != null && normal > preco && !app) app = true // dois preços = normal + app
        if (normal != null && normal <= preco) normal = null
        return item.copy(
            produto = item.produto.trim().replace(Regex("\\s+"), " "),
            precoCentavos = preco,
            precoNormalCentavos = normal,
            precoApp = app,
            obs = item.obs?.trim()?.replace(Regex("\\s+"), " ")?.take(OBS_MAX)?.ifBlank { null },
        )
    }

    /** "Leve 3 por R$ 10,00": preço de cada unidade na promoção (arredondado) e a conta na OBS. */
    fun levePor(quantidade: Int, totalCentavos: Long): Pair<Long, String> {
        require(quantidade >= 2 && totalCentavos > 0)
        val cada = Math.round(totalCentavos.toDouble() / quantidade)
        return cada to "Leve $quantidade por ${Dinheiro.formatar(totalCentavos)} (${Dinheiro.formatar(cada)} cada)"
    }

    /** Problemas que impedem lançar o item (o banco confere de novo). */
    fun erros(item: ItemLidoEncarte, hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        if (item.produto.isBlank()) add("Produto sem nome.")
        if (item.produto.length > 200) add("Nome do produto muito longo.")
        if (item.precoCentavos <= 0 || item.precoCentavos > MAX_PRECO) add("Preço inválido.")
        item.precoNormalCentavos?.let {
            if (it <= item.precoCentavos) add("O preço normal precisa ser maior que o preço.")
            if (!item.precoApp) add("Preço normal só com preço do app.")
        }
        if ((item.obs?.length ?: 0) > OBS_MAX) add("OBS com mais de $OBS_MAX caracteres.")
        when {
            item.validade == null -> add("Sem validade.")
            item.validade.isBefore(hoje.minusDays(DIAS_VENCIDA)) -> add("Validade vencida há mais de $DIAS_VENCIDA dias.")
            item.validade.isAfter(hoje.plusDays(DIAS_A_FRENTE)) -> add("Validade mais de $DIAS_A_FRENTE dias à frente.")
        }
    }

    /** Mesmo produto (sem acento, maiúsculas e espaços) e mesmo preço: um item só (vale o primeiro). */
    fun unir(listas: List<List<ItemLidoEncarte>>): List<ItemLidoEncarte> {
        val vistos = HashSet<Pair<String, Long>>()
        return listas.flatten().filter { vistos.add(Texto.normalizar(it.produto) to it.precoCentavos) }
    }

    /** "12 itens, 3 com preço do app, validade 15/11/2026, loja Assaí Centro". */
    fun resumo(itens: List<ItemLidoEncarte>, loja: String): String {
        val marcados = itens.filter { it.marcado }
        val validades = marcados.mapNotNull { it.validade }.distinct().sorted()
        val validade = when {
            validades.isEmpty() -> "sem validade"
            validades.size == 1 -> "validade ${Validade.formatar(validades.first())}"
            else -> "validades de ${Validade.formatar(validades.first())} a ${Validade.formatar(validades.last())}"
        }
        val n = marcados.size
        return "$n ${if (n == 1) "item" else "itens"}, ${marcados.count { it.precoApp }} com preço do app, $validade, loja ${loja.trim()}"
    }
}

/**
 * Lê e confere o JSON devolvido pela função `ler-encarte`
 * (`{ loja: {nome}, itens: [...], ignorados: [...] }`). Item com campo inválido não derruba a
 * leitura: vai para "ignorados" com o motivo.
 */
object LeitorJsonEncarte {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataBr = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    class JsonInvalido(msg: String) : Exception(msg)

    fun ler(texto: String): LeituraEncarte {
        val raiz = runCatching { json.parseToJsonElement(texto) }.getOrNull() as? JsonObject
            ?: throw JsonInvalido("resposta não é um objeto JSON")
        val itensJson = raiz["itens"] as? JsonArray ?: throw JsonInvalido("falta a lista de itens")
        val ignorados = mutableListOf<IgnoradoEncarte>()
        (raiz["ignorados"] as? JsonArray)?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            ignorados += IgnoradoEncarte(o.texto("texto").orEmpty(), o.texto("motivo").orEmpty())
        }
        val itens = itensJson.mapNotNull { e ->
            val o = e as? JsonObject
            val item = o?.let(::item)
            if (item == null) {
                ignorados += IgnoradoEncarte(o?.texto("produto") ?: e.toString().take(80), "item com dados inválidos")
            }
            item
        }
        val loja = (raiz["loja"] as? JsonObject)?.texto("nome")?.ifBlank { null }
        return LeituraEncarte(loja, itens.map(RegrasEncarte::normalizar), ignorados)
    }

    private fun item(o: JsonObject): ItemLidoEncarte? {
        val produto = o.texto("produto")?.takeIf { it.isNotBlank() } ?: return null
        val preco = o.inteiro("preco_centavos")?.takeIf { it > 0 } ?: return null
        val normal = o.inteiro("preco_normal_centavos")
        val unidadeTexto = o.texto("unidade") ?: "unidade"
        val unidade = Unidade.entries.firstOrNull { it.codigo == unidadeTexto } ?: return null
        val validade = o.texto("validade")?.let(::data)
        return ItemLidoEncarte(
            produto = produto,
            precoCentavos = preco,
            precoNormalCentavos = normal?.takeIf { it > 0 },
            precoApp = o.booleano("preco_app") ?: false,
            unidade = unidade,
            validade = validade,
            obs = o.texto("obs"),
            alcoolico = o.booleano("alcoolico") ?: false,
        )
    }

    /** ISO (2026-11-15) ou dd/mm/aaaa. */
    fun data(texto: String): LocalDate? = try {
        if (texto.contains('/')) LocalDate.parse(texto.trim(), dataBr) else LocalDate.parse(texto.trim())
    } catch (e: DateTimeParseException) {
        null
    }

    private fun JsonObject.texto(campo: String): String? = (this[campo] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.inteiro(campo: String): Long? = (this[campo] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.booleano(campo: String): Boolean? = (this[campo] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
