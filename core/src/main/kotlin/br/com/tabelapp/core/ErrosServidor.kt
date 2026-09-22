package br.com.tabelapp.core

/**
 * Traduz os códigos de erro levantados pelas funções do banco
 * (`raise exception 'cota_excedida'` etc.) para mensagens ao usuário.
 */
object ErrosServidor {
    private val mensagens = mapOf(
        "nao_autenticado" to "Sua sessão expirou. Entre novamente.",
        "sem_permissao" to "Você não tem permissão para fazer isso.",
        "cota_excedida" to "Suas operações grátis deste mês acabaram. Compre +50 operações por R\$ 10 via Pix.",
        "itens_vazios" to "Adicione pelo menos um produto.",
        "itens_demais" to "Muitos itens de uma vez. Divida em partes menores.",
        "itens_invalidos" to "Alguns itens têm dados inválidos. Confira produto, preço e validade.",
        "loja_invalida" to "Loja não encontrada ou inativa.",
        "chave_acesso_invalida" to "A chave de acesso precisa ter 44 números.",
        "nf_ja_enviada" to "Esta nota fiscal já foi enviada.",
        "pdv_obrigatorio" to "Informe o nome do estabelecimento.",
        "item_nao_encontrado" to "Produto não encontrado.",
        "encarte_nao_encontrado" to "Encarte não encontrado.",
        "encarte_ja_revisado" to "Este encarte já foi revisado.",
    )

    const val GENERICA = "Algo deu errado. Tente de novo em instantes."

    /** Procura um código conhecido dentro da mensagem de erro bruta do servidor. */
    fun traduzir(mensagemBruta: String?): String {
        if (mensagemBruta == null) return GENERICA
        return mensagens.entries.firstOrNull { (codigo, _) -> mensagemBruta.contains(codigo) }?.value
            ?: GENERICA
    }
}
