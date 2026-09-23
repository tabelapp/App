package br.com.tabelapp.core

import java.time.Instant

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
)

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
