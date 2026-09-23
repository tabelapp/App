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
)

/** Uma loja do PDV. */
data class LojaPdv(val id: String, val nome: String?, val endereco: String, val telefone: String?)

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
