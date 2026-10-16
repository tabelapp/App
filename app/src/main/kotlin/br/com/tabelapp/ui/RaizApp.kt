package br.com.tabelapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.tabelapp.AppContainer
import br.com.tabelapp.R
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.ui.login.TelaCompletarCadastro
import br.com.tabelapp.ui.login.TelaLogin
import br.com.tabelapp.ui.principal.TelaPrincipal

/**
 * Navegação de alto nível, guiada pelo estado da sessão:
 * carregando -> login -> (completar cadastro) -> abas (busca, enviar NF).
 */
@Composable
fun RaizApp(container: AppContainer) {
    val estado by container.auth.estado.collectAsStateWithLifecycle()
    // Aba aberta: guardada aqui (fora das telas que somem durante o "carregando").
    val abaSalva = rememberSaveable { mutableStateOf("BUSCAR") }

    // Conta recém-criada: grava a versão dos Termos aceita no cadastro.
    val logado = (estado as? EstadoSessao.Logado)?.usuario
    LaunchedEffect(logado?.id, logado?.cadastroCompleto) {
        val versao = container.preferencias.aceiteTermosPendente
        if (logado != null && logado.cadastroCompleto && versao != null) {
            runCatching { container.auth.registrarAceiteTermos(versao) }
                .onSuccess { container.preferencias.aceiteTermosPendente = null }
        }
    }

    when (val e = estado) {
        EstadoSessao.Carregando -> TelaAbertura()
        EstadoSessao.Deslogado -> TelaLogin(container)
        is EstadoSessao.Logado ->
            if (!e.usuario.cadastroCompleto) {
                TelaCompletarCadastro(container, e.usuario)
            } else {
                TelaPrincipal(container, e.usuario, abaSalva)
            }
    }
}

/**
 * Enquanto a sessão carrega: a mesma lupa da abertura do Android, no mesmo lugar e tamanho
 * (fundo_abertura / values-v31), para a troca não piscar. O indicador fica logo abaixo.
 */
@Composable
private fun TelaAbertura() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.logo_abertura), contentDescription = "Tabelapp", modifier = Modifier.size(192.dp))
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.padding(top = 240.dp).size(24.dp),
        )
    }
}
