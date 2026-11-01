package br.com.tabelapp.core

/**
 * Estado (UF) cuja NFC-e o app sabe consultar e ler. O estado vem dos 2 primeiros números da
 * chave de acesso ([ChaveAcessoNfe.codigoUf]).
 *
 * Ligar um estado novo exige, com material REAL desse estado (nunca layout inventado):
 *  - o endereço da consulta pública pela chave ([urlConsultaChave]) e o domínio da Sefaz ([dominioSefaz]);
 *  - uma chave real de exemplo (para os testes);
 *  - o HTML salvo da página de consulta com os produtos, para escrever e testar o [leitor];
 *  - incluir o código no `nfce_ufs_aceitas` do banco (configuracao_app).
 */
data class EstadoNfce(
    val codigoUf: String,
    val sigla: String,
    val nome: String,
    /** Consulta pública da NFC-e pela chave (entrada manual dos 44 números). */
    val urlConsultaChave: String?,
    /** Domínio da Sefaz (o leitor só troca http por https dentro dele). */
    val dominioSefaz: String?,
    /** Lê o HTML da página de consulta; null = ainda não implementado (estado desligado). */
    val leitor: ((String) -> NotaLida?)?,
) {
    val ativo: Boolean get() = urlConsultaChave != null && dominioSefaz != null && leitor != null
}

object EstadosNfce {
    val RJ = EstadoNfce(
        codigoUf = ChaveAcessoNfe.UF_RJ, sigla = "RJ", nome = "Rio de Janeiro",
        urlConsultaChave = "https://www.fazenda.rj.gov.br/nfce/consulta",
        dominioSefaz = "fazenda.rj.gov.br",
        leitor = LeitorNfce::ler,
    )

    /** São Paulo: desligado até chegar o HTML real da consulta (ver o comentário de [EstadoNfce]). */
    val SP = EstadoNfce("35", "SP", "São Paulo", urlConsultaChave = null, dominioSefaz = null, leitor = null)

    /** Minas Gerais: desligado até chegar o HTML real da consulta. */
    val MG = EstadoNfce("31", "MG", "Minas Gerais", urlConsultaChave = null, dominioSefaz = null, leitor = null)

    val todos: List<EstadoNfce> = listOf(RJ, SP, MG)

    const val MENSAGEM_OUTROS_ESTADOS =
        "Por enquanto o Tabelapp lê notas do RJ. Outros estados chegam em breve."

    /** Estado da chave, só se o app já sabe ler notas dele; null = estado desligado ou desconhecido. */
    fun ativoDaChave(chave: ChaveAcessoNfe): EstadoNfce? = todos.firstOrNull { it.codigoUf == chave.codigoUf && it.ativo }

    /** Domínios das Sefaz ligadas (para o leitor no celular). */
    val dominiosAtivos: List<String> get() = todos.filter { it.ativo }.mapNotNull { it.dominioSefaz }
}
