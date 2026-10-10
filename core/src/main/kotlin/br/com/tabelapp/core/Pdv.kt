package br.com.tabelapp.core

import java.time.Instant
import java.time.LocalDate

/** Situação do cadastro do PDV (mesmos códigos do enum `status_verificacao`). */
enum class StatusPdv(val codigo: String) {
    PENDENTE("pendente"),
    APROVADO("aprovado"),
    REJEITADO("rejeitado");

    companion object {
        fun doCodigo(codigo: String?): StatusPdv = entries.firstOrNull { it.codigo == codigo } ?: PENDENTE
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
)

/** Um item da tabela de preços oficial do PDV. */
data class PrecoPdv(
    val id: String,
    val lojaId: String,
    val produto: String,
    val precoCentavos: Long,
    val validade: LocalDate,
    val obs: String?,
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
) {
    /**
     * O que falta no formulário. A situação do CNPJ na Receita (ativa, baixada...) não
     * impede o cadastro (decisão do fundador): o que importa é o Admin confirmar,
     * pelo alvará, que quem cadastra responde pela empresa.
     */
    fun erros(temAlvara: Boolean): List<String> = buildList {
        if (!Cnpj.valido(cnpj)) add("CNPJ inválido: confira os 14 números.")
        if (nomeFantasia.isBlank()) add("Informe o nome do estabelecimento.")
        if (endereco.isBlank()) add("Informe o endereço.")
        if (!temAlvara) add("Envie a foto do alvará.")
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

/** Resultado da prévia de uma importação de planilha (função pdv_salvar_precos com simular). */
data class SimulacaoImportacao(
    val criados: Int,
    val aumentados: Int,
    val diminuidos: Int,
    val inalterados: Int,
    val operacoes: Int,
    val restantes: Int,
    val cabeNaCota: Boolean,
    val pacotesNecessarios: Int,
)

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
