package br.com.tabelapp.dados

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Login e cadastro. Google e e-mail no MVP; WhatsApp ainda sem fluxo definido
 * (briefing, seção 10) — o botão aparece como "em breve".
 */
interface AuthRepositorio {
    val estado: StateFlow<EstadoSessao>

    suspend fun entrarComEmail(email: String, senha: String)

    /** @return true se a conta já está ativa; false se o Supabase pediu confirmação por e-mail. */
    suspend fun cadastrarComEmail(nome: String, email: String, senha: String, tipo: TipoConta): Boolean

    /** Primeiro acesso pelo Google: grava nome e se a pessoa é CPF ou CNPJ. */
    suspend fun concluirCadastro(nome: String, tipo: TipoConta)

    suspend fun sair()

    /**
     * Ação de "Entrar com Google" pronta para ser chamada no clique,
     * ou null se o login Google não estiver configurado.
     */
    @Composable
    fun lembrarLoginGoogle(aoFalhar: (String) -> Unit): (() -> Unit)?
}
