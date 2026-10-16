package br.com.tabelapp.core

/**
 * Tipos de erro que o usuário pode ver. Cada tipo tem textos calmos e curtos em
 * `app/src/main/res/values/strings.xml` (erro_<chave>_titulo e erro_<chave>_texto);
 * o detalhe técnico vai só para o log interno.
 */
enum class TipoErro {
    SEFAZ_INDISPONIVEL,
    SEM_INTERNET,
    CAMERA_NEGADA,
    QR_INVALIDO,
    SERVIDOR_INSTAVEL,
    SESSAO_EXPIRADA,
    BUSCA_VAZIA,
    FALHA_UPLOAD_FOTO,
    FALHA_ENVIO_NF,
    FALHA_PAGAMENTO,
    INESPERADO;

    /** Prefixo dos textos no strings.xml. */
    val chave: String get() = name.lowercase()
}

/** Decide qual mensagem amigável corresponde a uma falha técnica. */
object ClassificadorErros {

    /**
     * Falha ao carregar a página da Sefaz no WebView (erro de rede do navegador).
     * Sem internet é culpa da conexão; qualquer outro caso (conexão cortada,
     * tempo esgotado, endereço não encontrado) tratamos como Sefaz indisponível.
     */
    fun deFalhaDePagina(temInternet: Boolean): TipoErro =
        if (temInternet) TipoErro.SEFAZ_INDISPONIVEL else TipoErro.SEM_INTERNET

    /** Resposta HTTP da Sefaz que significa "não atendeu agora" (bloqueio, excesso de acessos, falha deles). */
    fun deStatusHttpSefaz(status: Int): TipoErro? =
        if (status == 403 || status == 429 || status >= 500) TipoErro.SEFAZ_INDISPONIVEL else null

    private val classesDeRede = listOf(
        "UnknownHost", "SocketTimeout", "ConnectException", "SocketException", "SSLException", "IOException",
        "HttpRequestException", "HttpRequestTimeout", "TimeoutCancellation", "ConnectTimeout", "NoRouteToHost",
    )
    private val sinaisDeSessao = listOf(
        "nao_autenticado", "jwt expired", "invalid jwt", "refresh_token", "session_not_found", "session expired",
        "not authenticated", "bad_jwt",
    )

    /**
     * Falha numa chamada ao servidor do Tabelapp.
     *
     * @return null quando a falha é uma regra de negócio conhecida (ex.: "nota já enviada"):
     *   aí vale a mensagem própria de [ErrosServidor].
     */
    fun deServidor(nomeClasse: String, mensagem: String?, statusHttp: Int?, temInternet: Boolean): TipoErro? {
        val msg = mensagem.orEmpty().lowercase()
        return when {
            classesDeRede.any { nomeClasse.contains(it) } && !temInternet -> TipoErro.SEM_INTERNET
            !temInternet && statusHttp == null -> TipoErro.SEM_INTERNET
            statusHttp == 401 || sinaisDeSessao.any { msg.contains(it) } -> TipoErro.SESSAO_EXPIRADA
            ErrosServidor.conhece(mensagem) -> null
            classesDeRede.any { nomeClasse.contains(it) } -> TipoErro.SERVIDOR_INSTAVEL
            statusHttp != null && (statusHttp == 429 || statusHttp >= 500) -> TipoErro.SERVIDOR_INSTAVEL
            else -> TipoErro.INESPERADO
        }
    }
}

/** Reconhece, pelo conteúdo, páginas da Sefaz que não são a nota (bloqueio de acesso, erro do navegador). */
object PaginaSefaz {
    private val tags = Regex("<[^>]*>")

    private fun texto(html: String) = Texto.normalizar(tags.replace(html, " "))

    /** A Sefaz-RJ bloqueou o acesso ("...bloqueia acessos provenientes desses endereços IP"). */
    fun ehBloqueio(html: String): Boolean {
        val t = texto(html)
        return t.contains("bloqueia acessos provenientes") ||
            (t.contains("secretaria de estado de fazenda") && t.contains("bloqueia"))
    }

    /** Página de erro do próprio navegador ("Página da Web não disponível", net::ERR_...). */
    fun ehErroDoNavegador(html: String): Boolean {
        val t = texto(html)
        return t.contains("pagina da web nao disponivel") || t.contains("webpage not available") ||
            Regex("net::err_[a-z_]+").containsMatchIn(t)
    }
}

/**
 * Intervalo mínimo entre tentativas manuais de abrir a Sefaz: insistir em
 * sequência piora o bloqueio. Conta a partir do início da última tentativa.
 */
class IntervaloTentativas(private val minimoMs: Long = 10_000) {
    private var ultima: Long? = null

    fun registrar(agoraMs: Long) {
        ultima = agoraMs
    }

    fun esperaRestanteMs(agoraMs: Long): Long =
        ultima?.let { (it + minimoMs - agoraMs).coerceAtLeast(0) } ?: 0

    fun podeTentar(agoraMs: Long): Boolean = esperaRestanteMs(agoraMs) == 0L
}

/** Limpa o texto antes de ir para o log interno: nunca guarda CPF. */
object LogSeguro {
    private val cpfFormatado = Regex("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
    private val onzeDigitos = Regex("(?<!\\d)\\d{11}(?!\\d)")

    fun limpar(texto: String, max: Int = 2_000): String =
        texto.replace(cpfFormatado, "***.***.***-**").replace(onzeDigitos, "***********").take(max)
}
