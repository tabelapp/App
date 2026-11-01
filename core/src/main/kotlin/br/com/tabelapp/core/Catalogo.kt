package br.com.tabelapp.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Limite por TAMANHO DO CATÁLOGO (substituiu a cota de 50 operações/mês).
 *
 *  - O limite é de itens ATIVOS ao mesmo tempo (na validade e não pausados). Varejo:
 *    por loja. Rede: o catálogo único da rede.
 *  - Só criar ou reativar item é conferido; atualizar preço, renovar validade,
 *    mudar OBS/unidade e excluir nunca são bloqueados.
 *  - Planos na tabela `planos` (grátis 5, essencial 30, loja 300, mercado 3000) e
 *    teste grátis de 30 dias com o limite do mercado. O banco é quem manda.
 */
enum class TipoOperacao {
    CRIAR_ITEM, AUMENTAR_PRECO, DIMINUIR_PRECO, EXCLUIR_ITEM, EDITAR_DADOS;

    companion object {
        /**
         * @param precoAtual preço vigente do item (null = item ainda não existe)
         * @param precoNovo  preço que o PDV quer gravar (null = excluir)
         */
        fun classificar(precoAtual: Long?, precoNovo: Long?): TipoOperacao = when {
            precoNovo == null -> EXCLUIR_ITEM
            precoAtual == null -> CRIAR_ITEM
            precoNovo > precoAtual -> AUMENTAR_PRECO
            precoNovo < precoAtual -> DIMINUIR_PRECO
            else -> EDITAR_DADOS
        }
    }
}

/** Um plano (faixa de itens ativos). Espelha a tabela `planos` (editável sem publicar app). */
data class Plano(
    val codigo: String,
    val nome: String,
    val limiteItens: Int,
    /** Preço por mês em centavos (0 = grátis). Vem da tabela; nada fixo no código. */
    val precoCentavos: Long?,
    val ordem: Int,
) {
    val gratis: Boolean get() = codigo == GRATIS

    companion object {
        const val GRATIS = "gratis"
        /** Plano cujo limite vale no teste grátis (o banco lê de `configuracao_app`). */
        const val PLANO_TESTE = "mercado"
    }
}

/** Preço do plano a partir dos centavos: "R$ 9,99 por mês". */
object PrecoPlano {
    fun porMes(centavos: Long): String = Dinheiro.formatar(centavos) + " por mês"
}

/** De onde vem o limite que vale hoje. */
enum class OrigemLimite(val codigo: String) {
    PAGO("pago"), TESTE("teste"), GRATIS("gratis");

    companion object {
        fun doCodigo(codigo: String?): OrigemLimite = entries.firstOrNull { it.codigo == codigo } ?: GRATIS
    }
}

/** Limite que vale hoje (espelha `_limite_efetivo()` do banco, que é quem manda). */
data class LimiteEfetivo(
    val limite: Int,
    val plano: String,
    val origem: OrigemLimite,
    /** O teste ou o plano já terminou, mas ainda está nos dias de tolerância. */
    val emTolerancia: Boolean,
    /** Fim do plano pago ou do teste que vale agora (null no grátis). */
    val fim: LocalDate?,
    val toleranciaAte: LocalDate?,
)

object Limites {
    /**
     * O maior entre: o plano pago (até [planoAte]), o plano do teste (até [testeAte]) e o grátis.
     * Até [diasTolerancia] dias depois do fim, o limite anterior continua valendo. No empate,
     * vale o que não está na tolerância (e o pago antes do teste).
     */
    fun efetivo(
        planos: List<Plano>, plano: String, planoAte: LocalDate?, testeAte: LocalDate?, hoje: LocalDate,
        diasTolerancia: Int, planoTeste: String = Plano.PLANO_TESTE,
    ): LimiteEfetivo {
        val tol = diasTolerancia.coerceAtLeast(0).toLong()
        fun limiteDe(codigo: String) = planos.firstOrNull { it.codigo == codigo }?.limiteItens
        data class Cand(val codigo: String, val origem: OrigemLimite, val fim: LocalDate?, val prio: Int)
        val candidatos = buildList {
            add(Cand(Plano.GRATIS, OrigemLimite.GRATIS, null, 3))
            if (plano != Plano.GRATIS && planoAte != null && !planoAte.plusDays(tol).isBefore(hoje)) {
                add(Cand(plano, OrigemLimite.PAGO, planoAte, 1))
            }
            if (testeAte != null && !testeAte.plusDays(tol).isBefore(hoje)) add(Cand(planoTeste, OrigemLimite.TESTE, testeAte, 2))
        }.filter { limiteDe(it.codigo) != null }
        val escolhido = candidatos.sortedWith(
            compareByDescending<Cand> { limiteDe(it.codigo) }
                .thenBy { it.fim?.isBefore(hoje) == true }
                .thenBy { it.prio }
        ).first()
        return LimiteEfetivo(
            limite = limiteDe(escolhido.codigo)!!,
            plano = escolhido.codigo,
            origem = escolhido.origem,
            emTolerancia = escolhido.fim?.isBefore(hoje) == true,
            fim = escolhido.fim,
            toleranciaAte = escolhido.fim?.plusDays(tol),
        )
    }
}

/** Situação de um item na tabela do PDV (espelha `meus_precos().estado`). */
enum class EstadoItem(val codigo: String) {
    /** Publicado na busca. */
    ATIVO("ativo"),
    /** Pausado pelo próprio PDV (não conta no limite). */
    PAUSADO("pausado"),
    /** Acima do limite do plano: guardado, fora da busca, volta ao assinar/renovar. */
    PAUSADO_LIMITE("pausado_limite"),
    VENCIDO("vencido");

    val publicado: Boolean get() = this == ATIVO

    companion object {
        fun doCodigo(codigo: String?): EstadoItem = entries.firstOrNull { it.codigo == codigo } ?: ATIVO
    }
}

/** Avisos calmos do painel do PDV sobre o teste e o plano. */
sealed interface AvisoPlano {
    /** Faixa discreta durante o teste: "Teste grátis: catálogo liberado até {data}". */
    data class FaixaTeste(val ate: LocalDate) : AvisoPlano
    /** Faltam [dias] (0 = hoje) para o fim do teste. */
    data class TesteTermina(val dias: Int, val limiteGratis: Int) : AvisoPlano
    /** Faltam [dias] (0 = hoje) para o fim do plano pago. */
    data class PlanoTermina(val planoNome: String, val dias: Int, val ate: LocalDate) : AvisoPlano
    /** Terminou, mas o limite anterior vale até [ate]. */
    data class Tolerancia(val origem: OrigemLimite, val ate: LocalDate, val limiteGratis: Int) : AvisoPlano
    /** [quantidade] itens pausados porque o teste ou o plano terminou. */
    data class ItensPausados(val quantidade: Int) : AvisoPlano
}

/** Situação do catálogo da loja (ou da rede). Espelha `status_catalogo()` do banco. */
data class StatusCatalogo(
    /** Plano contratado (gratis, essencial, loja, mercado). */
    val plano: String,
    val planoNome: String,
    val limitePlano: Int,
    val planoAte: LocalDate?,
    val testeAte: LocalDate?,
    /** Plano cujo limite vale hoje. */
    val planoEfetivo: String,
    val planoEfetivoNome: String,
    val limite: Int,
    val origem: OrigemLimite,
    val emTolerancia: Boolean,
    val fim: LocalDate?,
    val toleranciaAte: LocalDate?,
    /** Itens que contam no limite (na validade e não pausados pelo PDV). */
    val itensAtivos: Int,
    val disponiveis: Int,
    /** Os que aparecem na busca. */
    val itensPublicados: Int = minOf(itensAtivos, limite),
    val pausadosLimite: Int = (itensAtivos - limite).coerceAtLeast(0),
    val pausadosPdv: Int = 0,
    val limiteGratis: Int = 5,
    val diasTolerancia: Int = 7,
    val avisoDias: Int = 7,
    val whatsappContato: String? = null,
) {
    val noLimite: Boolean get() = disponiveis <= 0

    /** Plano pago ainda válido (ou na tolerância): para "Plano até dd/mm/aaaa". */
    val temPlanoPago: Boolean get() = plano != Plano.GRATIS && planoAte != null

    /** Dias até o fim do teste/plano que vale agora (0 = hoje); null no grátis ou já na tolerância. */
    fun diasParaFim(hoje: LocalDate): Long? =
        fim?.takeIf { !emTolerancia && !it.isBefore(hoje) }?.let { ChronoUnit.DAYS.between(hoje, it) }

    /** Avisos a mostrar hoje, na ordem. O aviso de fim aparece a partir de [avisoDias] antes (e no dia). */
    fun avisos(hoje: LocalDate): List<AvisoPlano> = buildList {
        val dias = diasParaFim(hoje)
        when {
            emTolerancia && toleranciaAte != null -> add(AvisoPlano.Tolerancia(origem, toleranciaAte, limiteGratis))
            origem == OrigemLimite.TESTE && fim != null -> {
                add(AvisoPlano.FaixaTeste(fim))
                if (dias != null && dias <= avisoDias) add(AvisoPlano.TesteTermina(dias.toInt(), limiteGratis))
            }
            origem == OrigemLimite.PAGO && fim != null && dias != null && dias <= avisoDias ->
                add(AvisoPlano.PlanoTermina(planoEfetivoNome, dias.toInt(), fim))
        }
        if (pausadosLimite > 0) add(AvisoPlano.ItensPausados(pausadosLimite))
    }
}

/**
 * Unidade do preço (produtos e serviços). [sufixo] aparece depois do preço
 * ("R$ 12,90 / kg"); "unidade" não tem sufixo.
 */
enum class Unidade(val codigo: String, val rotulo: String, val sufixo: String?) {
    UNIDADE("unidade", "Unidade", null),
    KG("kg", "Quilo (kg)", "kg"),
    LITRO("litro", "Litro", "litro"),
    METRO("metro", "Metro", "metro"),
    M2("m2", "Metro quadrado (m²)", "m²"),
    HORA("hora", "Hora", "hora"),
    VISITA("visita", "Visita", "visita"),
    DIARIA("diaria", "Diária", "diária"),
    SERVICO("servico", "Serviço", "serviço");

    companion object {
        fun deCodigo(codigo: String?): Unidade = entries.firstOrNull { it.codigo == codigo } ?: UNIDADE
    }
}

/** Como o preço aparece: "R$ 12,90 / kg", "A partir de R$ 80,00 / hora", "R$ 5,00". */
object PrecoExibido {
    const val A_PARTIR_DE = "A partir de"

    /** Só o sufixo da unidade (" / kg"), ou vazio para "unidade". */
    fun sufixo(unidade: Unidade): String = unidade.sufixo?.let { " / $it" }.orEmpty()

    fun texto(centavos: Long, unidade: Unidade = Unidade.UNIDADE, aPartirDe: Boolean = false): String =
        (if (aPartirDe) "$A_PARTIR_DE " else "") + Dinheiro.formatar(centavos) + sufixo(unidade)

    fun texto(c: Cotacao): String = texto(c.precoCentavos, c.unidade, c.aPartirDe)

    // --- Preço no app (clube de vantagens da loja) ---------------------------------------

    /** Legenda pequena abaixo do preço principal quando ele é o preço no app. */
    const val LEGENDA_APP = "no app"

    /** Só encarte/preço oficial, com o normal informado e maior que o do app. */
    fun temPrecoApp(fonte: Fonte, centavos: Long, precoApp: Boolean, normalCentavos: Long?): Boolean =
        precoApp && fonte != Fonte.USUARIO_NF && normalCentavos != null && normalCentavos > centavos

    /** Preço normal, menor e sem legenda: "R$ 6,99" (ou "R$ 57,90 / kg"). */
    fun textoNormal(normalCentavos: Long, unidade: Unidade): String = texto(normalCentavos, unidade)

    /** Leitor de tela: "R$ 5,98 no app. Preço normal R$ 6,99." (sem preço no app: o preço comum). */
    fun descricao(centavos: Long, unidade: Unidade, aPartirDe: Boolean, normalCentavos: Long?, temApp: Boolean): String {
        val principal = texto(centavos, unidade, aPartirDe)
        return if (temApp && normalCentavos != null) "$principal $LEGENDA_APP. Preço normal ${textoNormal(normalCentavos, unidade)}."
        else principal
    }

    fun descricao(c: Cotacao): String = descricao(c.precoCentavos, c.unidade, c.aPartirDe, c.precoNormalCentavos, c.temPrecoApp)

    /** No texto de compartilhar: "R$ 5,98 no app / R$ 6,99 normal". */
    fun textoCompartilhar(c: Cotacao): String {
        val normal = c.precoNormalCentavos
        return if (c.temPrecoApp && normal != null) "${texto(c)} $LEGENDA_APP / ${textoNormal(normal, c.unidade)} normal"
        else texto(c)
    }
}

/** Item da planilha que ficou fora do limite do plano. */
data class ItemFora(val linha: Int, val produto: String)

/** Prévia/resultado da importação da planilha (espelha a resposta de `pdv_salvar_precos`). */
data class ResultadoImportacao(
    val criados: Int,
    val aumentados: Int,
    val diminuidos: Int,
    val inalterados: Int,
    val foraDoLimite: Int,
    val itensFora: List<ItemFora>,
    val limite: Int,
    val planoNome: String,
) {
    /** Linhas que entram (atualizações nunca ficam de fora). */
    val importados: Int get() = criados + aumentados + diminuidos + inalterados

    /** CSV com os itens que ficaram de fora (para o PDV baixar). */
    fun csvDosQueFicaramDeFora(): String =
        "Linha;Produto\n" + itensFora.joinToString("") { "${it.linha};\"${it.produto.replace("\"", "\"\"")}\"\n" }
}
