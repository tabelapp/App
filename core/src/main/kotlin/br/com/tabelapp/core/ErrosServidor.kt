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
        "conta_nao_cnpj" to "Só contas de estabelecimento (CNPJ) cadastram um PDV.",
        "cnpj_invalido" to "CNPJ inválido: confira os 14 números.",
        "nome_obrigatorio" to "Informe o nome do estabelecimento.",
        "endereco_obrigatorio" to "Informe o endereço.",
        "alvara_obrigatorio" to "Envie a foto do alvará.",
        "cnpj_ja_cadastrado" to "Este CNPJ já está cadastrado no Tabelapp. Se a empresa é sua, fale com o suporte.",
        "cnpj_em_analise" to "Já existe um cadastro deste CNPJ em análise. Se a empresa é sua, fale com o suporte.",
        "pdv_nao_verificado" to "Seu cadastro ainda não foi aprovado.",
        "pdv_nao_encontrado" to "Cadastro não encontrado (talvez já analisado).",
        "motivo_obrigatorio" to "Informe o motivo.",
        "coordenada_invalida" to "Não consegui marcar a loja no mapa: a localização parece errada. Tente de novo.",
        "loja_nao_encontrada" to "Loja não encontrada.",
        "chave_obrigatoria" to "Leia o QR Code ou digite os 44 números da nota.",
        "nf_fora_do_rj" to "Esta nota não é do Rio de Janeiro. Por enquanto o Tabelapp funciona só em Petrópolis/RJ.",
        "limite_notas" to "Você já enviou muitas notas hoje. Tente de novo amanhã.",
        "lista_nao_encontrada" to "Lista não encontrada.",
        "listas_demais" to "Você já tem listas demais. Exclua alguma para criar outra.",
        "produto_vazio" to "Digite o nome do produto.",
        "quantidade_invalida" to "Quantidade inválida.",
        "pacote_invalido" to "Escolha um pacote de visualizações (100, 250 ou 500).",
        "titulo_obrigatorio" to "Dê um título para a promoção.",
        "arte_invalida" to "Não foi possível usar essa imagem. Tente outra.",
        "promocoes_pendentes_demais" to "Você já tem promoções aguardando pagamento. Conclua ou exclua uma delas.",
        "validade_longa" to "A validade pode ser de no máximo 30 dias a partir de hoje.",
        "limite_catalogo" to "Você chegou ao limite de itens do seu plano. Seus itens continuam publicados e você pode atualizar os preços quando quiser.",
        "unidade_invalida" to "Escolha uma unidade da lista.",
        "nome_invalido" to "O nome precisa ter entre 2 e 80 letras.",
        // Mensagens do serviço de login (Supabase Auth).
        "already registered" to "Este e-mail já tem cadastro. Entre com ele ou use \"Esqueci a senha\".",
        "Password should be" to "A senha precisa ter pelo menos 6 caracteres.",
        "Email not confirmed" to "Confirme seu e-mail pelo link que enviamos e depois entre.",
    )

    const val GENERICA = "Algo não saiu como esperado, mas já estamos cientes. Tente de novo em instantes."

    /** O código de regra de negócio que aparece na mensagem bruta (ex.: "limite_catalogo"). */
    fun codigo(mensagemBruta: String?): String? =
        mensagemBruta?.let { m -> mensagens.keys.firstOrNull { m.contains(it) } }

    /** A mensagem traz um código de regra de negócio conhecido (ex.: "nf_ja_enviada")? */
    fun conhece(mensagemBruta: String?): Boolean =
        mensagemBruta != null && mensagens.keys.any { mensagemBruta.contains(it) }

    /** Procura um código conhecido dentro da mensagem de erro bruta do servidor. */
    fun traduzir(mensagemBruta: String?): String {
        if (mensagemBruta == null) return GENERICA
        return mensagens.entries.firstOrNull { (codigo, _) -> mensagemBruta.contains(codigo) }?.value
            ?: GENERICA
    }
}
