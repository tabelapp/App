package br.com.tabelapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.tabelapp.AppContainer
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.ui.busca.TelaBusca
import br.com.tabelapp.ui.login.TelaCompletarCadastro
import br.com.tabelapp.ui.login.TelaLogin

/**
 * Navegação de alto nível, guiada pelo estado da sessão:
 * carregando -> login -> (completar cadastro) -> busca de preços.
 */
@Composable
fun RaizApp(container: AppContainer) {
    val estado by container.auth.estado.collectAsStateWithLifecycle()

    when (val e = estado) {
        EstadoSessao.Carregando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        EstadoSessao.Deslogado -> TelaLogin(container)
        is EstadoSessao.Logado ->
            if (!e.usuario.cadastroCompleto) {
                TelaCompletarCadastro(container, e.usuario)
            } else {
                TelaBusca(container, e.usuario)
            }
    }
}
