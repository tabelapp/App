package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate

/** De onde veio o preço. Mesmos códigos do enum `fonte_cotacao` no banco. */
enum class Fonte(val codigo: String) {
    PDV_MANUAL("pdv_manual"),
    PDV_EXCEL("pdv_excel"),
    USUARIO_NF("usuario_nf"),
    USUARIO_ENCARTE("usuario_encarte");

    val ehDoPdv: Boolean get() = this == PDV_MANUAL || this == PDV_EXCEL

    companion object {
        fun doCodigo(codigo: String): Fonte =
            entries.firstOrNull { it.codigo == codigo }
                ?: throw IllegalArgumentException("Fonte desconhecida: $codigo")
    }
}

data class PontoGeo(val latitude: Double, val longitude: Double)

/**
 * Um preço exibido na busca. Espelha o retorno da função `buscar_cotacoes` do banco.
 *
 * [lojaId] nulo = PDV não cadastrado (veio de NF ou encarte de usuário): aparece
 * como texto simples, sem link.
 */
data class Cotacao(
    val id: String,
    val produto: String,
    val precoCentavos: Long,
    val validade: LocalDate?,
    val obs: String?,
    val fonte: Fonte,
    val lojaId: String?,
    val pdvId: String?,
    val pdvNome: String,
    val lojaNome: String? = null,
    val endereco: String? = null,
    val telefone: String? = null,
    val site: String? = null,
    val local: PontoGeo? = null,
    val criadoEm: Instant,
    /** Data da compra (emissão da NF). Só existe em preço de Nota Fiscal. */
    val dataNf: LocalDate? = null,
    /** Unidade do preço (kg, hora...). NF e encarte usam sempre "unidade". */
    val unidade: Unidade = Unidade.UNIDADE,
    /** Preço inicial de serviço ("a partir de R$ 80,00"). */
    val aPartirDe: Boolean = false,
    /** Item vendido dentro de um combo (marcado por quem enviou a NF): nunca recebe o selo. */
    val parteDeCombo: Boolean = false,
    /** Quantas pessoas tocaram em "Esse preço não confere". */
    val denuncias: Int = 0,
    /**
     * Preço normal quando [precoCentavos] é o preço no app/clube de vantagens da loja (encarte ou
     * preço oficial). A pesquisa (ordem, menor preço) usa sempre [precoCentavos].
     */
    val precoNormalCentavos: Long? = null,
    val precoApp: Boolean = false,
) {
    val pdvCadastrado: Boolean get() = lojaId != null

    /** Mostrar "no app" e o preço normal? Nota Fiscal nunca tem. */
    val temPrecoApp: Boolean
        get() = PrecoExibido.temPrecoApp(fonte, precoCentavos, precoApp, precoNormalCentavos)

    /** Identifica o ponto físico de venda (loja cadastrada ou nome+endereço livres). */
    val chaveLocal: String
        get() = lojaId ?: ("livre:" + Texto.normalizar(pdvNome) + "|" + Texto.normalizar(endereco.orEmpty()))
}

/** Uma loja cadastrada, como aparece na hora de escolher onde foi a compra. */
data class LojaResumo(
    val lojaId: String,
    val pdvNome: String,
    val lojaNome: String?,
    val endereco: String,
    val telefone: String? = null,
    val pdvId: String? = null,
    val local: PontoGeo? = null,
) {
    val titulo: String get() = listOfNotNull(pdvNome, lojaNome).joinToString(" — ")
}
