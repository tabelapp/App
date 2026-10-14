package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate

/** Situação do cadastro do PDV (mesmos códigos do enum `status_verificacao`). */
enum class StatusPdv(val codigo: String) {
    PENDENTE("pendente"),
    APROVADO("aprovado"),
    REJEITADO("rejeitado"),
    /** Aprovado e depois suspenso pelo Admin: preços oficiais fora da busca, dados guardados. */
    SUSPENSO("suspenso");

    companion object {
        fun doCodigo(codigo: String?): StatusPdv = entries.firstOrNull { it.codigo == codigo } ?: PENDENTE
    }
}

/** Tipo de empresa: loja (ponto de venda com tabela de preços) ou só anunciante (produtor, marca). */
enum class TipoEmpresa(val codigo: String) {
    LOJA("loja"),
    /** Não vende direto ao público: não tem loja nem tabela de preços, só anuncia no banner. */
    ANUNCIANTE("anunciante");

    companion object {
        fun doCodigo(codigo: String?): TipoEmpresa = entries.firstOrNull { it.codigo == codigo } ?: LOJA
    }
}

/** Um PDV do usuário logado (conta CNPJ). */
data class MeuPdv(
    val id: String,
    val cnpj: String,
    val razaoSocial: String?,
    val nomeFantasia: String,
    val status: StatusPdv,
    val motivoRejeicao: String?,
    val cnpjConferidoNoAlvara: Boolean,
    val enviadoEm: Instant,
    /** true = "modo rede": a tabela de preços vale para todas as lojas. */
    val modoRede: Boolean = true,
    val site: String? = null,
    /** Motivo da suspensão (só quando [status] é SUSPENSO); o dono vê em "Meu negócio". */
    val motivoSuspensao: String? = null,
    val tipo: TipoEmpresa = TipoEmpresa.LOJA,
)

/** Uma loja do PDV. [endereco] é o completo (para exibir); os outros campos são para editar. */
data class LojaPdv(
    val id: String,
    val nome: String?,
    val endereco: String,
    val telefone: String?,
    val logradouro: String = "",
    val bairro: String? = null,
    val cidade: String? = null,
    val uf: String? = null,
    val whatsapp: String? = null,
    /** Coordenada gravada (pino no mapa); null = ainda não localizada. */
    val local: PontoGeo? = null,
    /** true = o dono usou "Estou na loja" (localização do celular), não o endereço. */
    val localPeloAparelho: Boolean = false,
) {
    /** Endereço completo para o Geocoder. */
    val enderecoParaMapa: String? get() = MapaPrecos.enderecoParaGeocoder(endereco)
}

/** Loja ainda sem coordenada, para o Admin localizar de uma vez. */
data class LojaSemLocal(val id: String, val nome: String?, val endereco: String)

/** Um item da tabela de preços oficial do PDV. */
data class PrecoPdv(
    val id: String,
    val lojaId: String,
    val produto: String,
    val precoCentavos: Long,
    val validade: LocalDate,
    val obs: String?,
    val unidade: Unidade = Unidade.UNIDADE,
    val aPartirDe: Boolean = false,
)

/**
 * Item que o PDV está incluindo ou editando (briefing, seção 5).
 * Validade: de hoje até 30 dias (se não informar, 30 dias). OBS: até 140 letras.
 */
data class RascunhoPreco(
    val produto: String = "",
    val preco: String = "",
    val validade: LocalDate? = null,
    val obs: String = "",
    /** Unidade do preço (kg, hora, visita...); serviços podem ser "a partir de". */
    val unidade: Unidade = Unidade.UNIDADE,
    val aPartirDe: Boolean = false,
) {
    val precoCentavos: Long? get() = Dinheiro.parse(preco)?.takeIf { it > 0 }

    fun erros(hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        if (produto.isBlank()) add("Informe o produto.")
        if (produto.trim().length > 200) add("Nome do produto muito longo.")
        if (precoCentavos == null) add("Informe um preço válido (ex.: 12,90).")
        if (validade != null) Validade.validar(validade, hoje)?.let { add(it.mensagem) }
        if (obs.trim().length > 140) add("A OBS pode ter no máximo 140 letras.")
    }

    companion object {
        fun de(item: PrecoPdv) = RascunhoPreco(
            produto = item.produto,
            preco = Dinheiro.formatar(item.precoCentavos).removePrefix("R$ "),
            validade = item.validade,
            obs = item.obs.orEmpty(),
            unidade = item.unidade,
            aPartirDe = item.aPartirDe,
        )
    }
}

/** O que a Receita Federal informa sobre o CNPJ (consulta pública). */
data class DadosReceita(
    val cnpj: String,
    val razaoSocial: String?,
    val nomeFantasia: String?,
    val situacao: String?,
    val endereco: String?,
    val bairro: String?,
    val cidade: String?,
    val uf: String?,
    val cep: String?,
    val telefone: String?,
    val atividade: String?,
) {
    val ativa: Boolean get() = situacao.equals("ATIVA", ignoreCase = true)
}

/** Dados do formulário de cadastro do PDV. */
data class CadastroPdv(
    val cnpj: String,
    val nomeFantasia: String,
    val razaoSocial: String?,
    val endereco: String,
    val bairro: String,
    val cidade: String,
    val uf: String,
    val cep: String,
    val telefone: String,
    val tipo: TipoEmpresa = TipoEmpresa.LOJA,
    val site: String = "",
) {
    /**
     * O que falta no formulário. A situação do CNPJ na Receita (ativa, baixada...) não
     * impede o cadastro (decisão do fundador): o que importa é o Admin confirmar,
     * pelo alvará, que quem cadastra responde pela empresa.
     */
    fun erros(temAlvara: Boolean): List<String> = buildList {
        if (!Cnpj.valido(cnpj)) add("CNPJ inválido: confira os 14 números.")
        if (nomeFantasia.isBlank()) add(if (tipo == TipoEmpresa.LOJA) "Informe o nome do estabelecimento." else "Informe o nome da empresa.")
        when (tipo) {
            TipoEmpresa.LOJA -> {
                if (endereco.isBlank()) add("Informe o endereço.")
                if (cidade.isBlank()) add("Informe a cidade.")
                if (!temAlvara) add("Envie a foto do alvará.")
            }
            // Anunciante: sem loja; o documento é opcional, o contato é obrigatório.
            TipoEmpresa.ANUNCIANTE -> {
                if (cidade.isBlank()) add("Informe a cidade.")
                if (telefone.count { it.isDigit() } < 10) add("Informe um telefone ou WhatsApp com DDD.")
            }
        }
    }
}

/** Cadastro esperando análise do Admin. */
data class PdvPendente(
    val id: String,
    val cnpj: String,
    val razaoSocial: String?,
    val nomeFantasia: String,
    val endereco: String?,
    val telefone: String?,
    val alvaraPath: String?,
    val cnpjConferidoNoAlvara: Boolean,
    val situacaoReceita: String?,
    val razaoSocialReceita: String?,
    val enderecoReceita: String?,
    val donoNome: String?,
    val donoEmail: String?,
    val enviadoEm: Instant,
    val tipo: TipoEmpresa = TipoEmpresa.LOJA,
)

/** Nome de estabelecimento sugerido por quem enviou NF, esperando confirmação do Admin. */
data class NomeSugerido(
    val cnpj: String,
    val nome: String,
    /** Quantas pessoas sugeriram esse mesmo nome. */
    val sugestoes: Int,
    val razaoSocial: String?,
    val endereco: String?,
)

/** Preço denunciado ("Esse preço não confere"), na fila de moderação do Admin. */
data class PrecoDenunciado(
    val cotacaoId: String,
    val produto: String,
    val precoCentavos: Long,
    val pdvNome: String?,
    val oficial: Boolean,
    val parteDeCombo: Boolean,
    val denuncias: Int,
    val comboOuPromocao: Int,
    val precoMudou: Int,
    val produtoErrado: Int,
    val outros: Int,
)

/** Estabelecimento aprovado ou suspenso, na lista de moderação do Admin. */
data class PdvAdmin(
    val id: String,
    val nomeFantasia: String,
    val cnpj: String,
    val status: StatusPdv,
    val motivoSuspensao: String?,
    val cidade: String?,
    val donoNome: String?,
    val donoEmail: String?,
    val itensPublicados: Int,
    val tipo: TipoEmpresa = TipoEmpresa.LOJA,
)

/** Resultado da prévia de uma importação de planilha (função pdv_salvar_precos com simular). */
/** Promoção (banner pago) do PDV. */
data class PromocaoPdv(
    val id: String,
    val titulo: String,
    val descricao: String?,
    val link: String?,
    val artePath: String?,
    val palavrasChave: List<String>,
    /** aguardando_pagamento | ativa | pausada | esgotada */
    val status: String,
    val visualizacoesContratadas: Int,
    val visualizacoesExibidas: Int,
    val pagamentoPendenteId: String?,
    val valorPendenteCentavos: Long?,
    val visualizacoesPendentes: Int?,
) {
    val rotuloStatus: String
        get() = when (status) {
            "aguardando_pagamento" -> "Aguardando pagamento"
            "ativa" -> "No ar"
            "pausada" -> "Pausada"
            "esgotada" -> "Visualizações esgotadas"
            else -> status
        }
}

/** Pagamento esperando confirmação do Admin (até o Pix automático). */
data class PagamentoPendente(
    val id: String,
    val tipo: String,
    val pdvNome: String,
    val descricao: String,
    val quantidade: Int,
    val valorCentavos: Long,
    val donoNome: String?,
    val donoEmail: String?,
    val criadoEm: Instant,
)

/** Contato oficial do Tabelapp (WhatsApp). */
object ContatoTabelapp {
    const val WHATSAPP = "5524988029067"
}
