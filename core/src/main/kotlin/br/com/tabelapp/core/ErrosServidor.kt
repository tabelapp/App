package br.com.tabelapp.core

/**
 * Traduz os códigos de erro levantados pelas funções do banco
 * (`raise exception 'cota_excedida'` etc.) para mensagens ao usuário.
 */
object ErrosServidor {
    private val mensagens = mapOf(
        "nao_autenticado" to "Sua sessão expirou. Entre novamente.",
        "sem_permissao" to "Você não tem permissão para fazer isso.",
        "cota_excedida" to "As operações desta loja acabaram. Compre +50 operações por R\$ 10 via Pix (valem 30 dias).",
        "itens_vazios" to "Adicione pelo menos um produto.",
        "itens_demais" to "Muitos itens de uma vez. Divida em partes menores.",
        "itens_invalidos" to "Alguns itens têm dados inválidos. Confira produto, preço e validade.",
        "loja_invalida" to "Loja não encontrada ou inativa.",
        "chave_acesso_invalida" to "A chave de acesso precisa ter 44 números.",
        "nf_ja_enviada" to "Esta nota fiscal já foi enviada.",
        "nf_antiga" to "Só aceitamos notas dos últimos 7 dias.",
        "data_nf_futura" to "A data da compra não pode ser no futuro.",
        "data_nao_confere" to "A data da compra não confere com a nota (mês/ano de emissão).",
        "foto_obrigatoria" to "Tire ou escolha pelo menos uma foto do encarte.",
        "fotos_demais" to "Envie no máximo 5 fotos por encarte.",
        "foto_invalida" to "Não foi possível enviar as fotos. Tente de novo.",
        "limite_encartes" to "Você já enviou muitos encartes hoje. Tente de novo amanhã.",
        "loja_nao_confere" to "O CNPJ da nota não é o desta loja. Confira o estabelecimento.",
        "pdv_obrigatorio" to "Informe o nome do estabelecimento.",
        "item_nao_encontrado" to "Produto não encontrado.",
        "encarte_nao_encontrado" to "Encarte não encontrado.",
        "encarte_ja_revisado" to "Este encarte já foi revisado.",
        "validade_obrigatoria" to "Informe a validade impressa no encarte.",
        "validade_passada" to "A validade informada já passou.",
        "validade_longa" to "A validade pode ser de no máximo 30 dias a partir de hoje.",
    )

    const val GENERICA = "Algo deu errado. Tente de novo em instantes."

    /** Procura um código conhecido dentro da mensagem de erro bruta do servidor. */
    fun traduzir(mensagemBruta: String?): String {
        if (mensagemBruta == null) return GENERICA
        return mensagens.entries.firstOrNull { (codigo, _) -> mensagemBruta.contains(codigo) }?.value
            ?: GENERICA
    }
}
