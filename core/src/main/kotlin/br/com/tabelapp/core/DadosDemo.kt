package br.com.tabelapp.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Dados FICTÍCIOS de Petrópolis para o modo demonstração do app (sem Supabase
 * configurado) e para testes. Espelham o supabase/seed.sql.
 */
object DadosDemo {

    private data class Loja(
        val lojaId: String?, val pdvId: String?, val pdvNome: String, val lojaNome: String?,
        val endereco: String, val telefone: String?, val site: String?, val local: PontoGeo?,
    )

    private val serraCentro = Loja(
        "20000000-0000-4000-a000-000000000001", "10000000-0000-4000-a000-000000000001",
        "Supermercado Serra Imperial", "Loja Centro", "Rua do Imperador, 500, Centro, Petrópolis - RJ",
        "(24) 2222-0001", "https://exemplo.invalid/serra", PontoGeo(-22.5058, -43.1790),
    )
    private val serraValparaiso = Loja(
        "20000000-0000-4000-a000-000000000002", "10000000-0000-4000-a000-000000000001",
        "Supermercado Serra Imperial", "Loja Valparaíso", "Rua Coronel Veiga, 1200, Valparaíso, Petrópolis - RJ",
        "(24) 2222-0002", "https://exemplo.invalid/serra", PontoGeo(-22.5160, -43.1730),
    )
    private val quitandinha = Loja(
        "20000000-0000-4000-a000-000000000003", "10000000-0000-4000-a000-000000000002",
        "Mercado Quitandinha", null, "Avenida Joaquim Rolla, 300, Quitandinha, Petrópolis - RJ",
        "(24) 2222-0003", null, PontoGeo(-22.5275, -43.2105),
    )
    private val bingen = Loja(
        "20000000-0000-4000-a000-000000000004", "10000000-0000-4000-a000-000000000003",
        "Hortifruti Bingen", null, "Rua Bingen, 800, Bingen, Petrópolis - RJ",
        "(24) 2222-0004", null, PontoGeo(-22.5165, -43.1960),
    )
    private val itaipava = Loja(
        "20000000-0000-4000-a000-000000000005", "10000000-0000-4000-a000-000000000004",
        "Empório Itaipava", null, "Estrada União e Indústria, 11000, Itaipava, Petrópolis - RJ",
        "(24) 2222-0005", null, PontoGeo(-22.3865, -43.1335),
    )
    private val altoDaSerra = Loja(
        null, null, "Mercadinho Alto da Serra", null, "Rua Teresa, 1500 - Alto da Serra", null, null, null,
    )
    private val sacolaoCorreas = Loja(
        null, null, "Sacolão Corrêas", null, "Estrada União e Indústria, 3000 - Corrêas", null, null, null,
    )

    fun cotacoes(agora: Instant = Instant.now(), hoje: LocalDate = LocalDate.now()): List<Cotacao> {
        var seq = 0
        fun c(
            loja: Loja, produto: String, centavos: Long, validadeDias: Long, obs: String?,
            fonte: Fonte, minutosAtras: Long,
        ) = Cotacao(
            id = "demo-${++seq}",
            produto = produto,
            precoCentavos = centavos,
            validade = hoje.plusDays(validadeDias),
            obs = obs,
            fonte = fonte,
            lojaId = loja.lojaId,
            pdvId = loja.pdvId,
            pdvNome = loja.pdvNome,
            lojaNome = loja.lojaNome,
            endereco = loja.endereco,
            telefone = loja.telefone,
            site = loja.site,
            local = loja.local,
            criadoEm = agora.minus(Duration.ofMinutes(minutosAtras)),
        )

        val excel = Fonte.PDV_EXCEL
        val manual = Fonte.PDV_MANUAL
        return listOf(
            c(serraCentro, "Arroz Branco Tipo 1 5kg", 2490, 20, null, excel, 300),
            c(serraValparaiso, "Arroz Branco Tipo 1 5kg", 2490, 20, null, excel, 300),
            c(serraCentro, "Feijão Preto 1kg", 789, 20, null, excel, 300),
            c(serraValparaiso, "Feijão Preto 1kg", 789, 20, null, excel, 300),
            c(serraCentro, "Café Torrado e Moído 500g", 1899, 15, "Leve 3 pague 2", manual, 120),
            c(serraValparaiso, "Café Torrado e Moído 500g", 1899, 15, "Leve 3 pague 2", manual, 120),
            c(serraCentro, "Cerveja Pilsen Lata 350ml", 399, 7, "Cerveja gelada", manual, 45),
            c(serraValparaiso, "Cerveja Pilsen Lata 350ml", 399, 7, "Cerveja gelada", manual, 45),
            c(serraCentro, "Leite Integral 1L", 569, 10, null, manual, 200),
            c(serraValparaiso, "Leite Integral 1L", 569, 10, null, manual, 200),
            c(quitandinha, "Arroz Branco Tipo 1 5kg", 2290, 10, "Entrega grátis acima de R$100", manual, 90),
            c(quitandinha, "Feijão Preto 1kg", 849, 10, null, manual, 90),
            c(quitandinha, "Óleo de Soja 900ml", 749, 10, null, manual, 90),
            c(quitandinha, "Açúcar Refinado 1kg", 489, 10, null, manual, 90),
            c(quitandinha, "Leite Integral 1L", 599, 10, null, manual, 90),
            c(quitandinha, "Ovos Brancos Dúzia", 1190, 5, null, manual, 30),
            c(bingen, "Banana Prata kg", 699, 3, "Fresquinha da serra", manual, 20),
            c(bingen, "Tomate kg", 899, 3, null, manual, 20),
            c(bingen, "Ovos Brancos Dúzia", 1090, 5, null, manual, 20),
            c(itaipava, "Café Torrado e Moído 500g", 2150, 30, null, manual, 400),
            c(itaipava, "Cerveja Pilsen Lata 350ml", 459, 30, null, manual, 400),
            c(itaipava, "Açúcar Refinado 1kg", 529, 30, null, manual, 400),
            // NF vale 1 dia; encarte vale até a data impressa nele.
            c(altoDaSerra, "Arroz Branco Tipo 1 5kg", 2349, Validade.NF_DIAS, null, Fonte.USUARIO_NF, 120),
            c(altoDaSerra, "Feijão Preto 1kg", 759, Validade.NF_DIAS, null, Fonte.USUARIO_NF, 120),
            c(sacolaoCorreas, "Banana Prata kg", 599, 4, null, Fonte.USUARIO_ENCARTE, 300),
            c(sacolaoCorreas, "Tomate kg", 799, 4, null, Fonte.USUARIO_ENCARTE, 300),
        )
    }

    /** Mesma semântica de `buscar_cotacoes()`: sem termo = mais recentes; com termo = mais barato primeiro. */
    fun buscar(termo: String?, agora: Instant = Instant.now(), hoje: LocalDate = LocalDate.now()): List<Cotacao> {
        val todas = cotacoes(agora, hoje)
        return if (termo.isNullOrBlank()) {
            todas.sortedByDescending { it.criadoEm }
        } else {
            todas.filter { Texto.casaBusca(it.produto, termo) }.sortedBy { it.precoCentavos }
        }
    }
}
