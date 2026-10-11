package br.com.tabelapp.dados

enum class TipoConta(val codigo: String) {
    CPF("cpf"),
    CNPJ("cnpj"),
    ADMIN("admin");

    companion object {
        fun doCodigo(codigo: String?): TipoConta = entries.firstOrNull { it.codigo == codigo } ?: CPF
    }
}

data class Usuario(
    val id: String,
    val nome: String?,
    val email: String?,
    val tipo: TipoConta,
    /** false = ainda precisa escolher CPF/CNPJ (ex.: primeiro login pelo Google). */
    val cadastroCompleto: Boolean,
)

sealed interface EstadoSessao {
    data object Carregando : EstadoSessao
    data object Deslogado : EstadoSessao
    data class Logado(val usuario: Usuario) : EstadoSessao
}

/**
 * Erro já traduzido para o usuário (ver [br.com.tabelapp.core.ErrosServidor]).
 * [tipo] != null: falha genérica (rede, instabilidade, sessão...) com texto do strings.xml.
 */
class ErroAmigavel(
    mensagem: String,
    causa: Throwable? = null,
    val tipo: br.com.tabelapp.core.TipoErro? = null,
) : Exception(mensagem, causa)
