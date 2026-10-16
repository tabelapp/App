package br.com.tabelapp.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.tabelapp.ui.tema.CoresTabelapp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // A abertura fica até a animação terminar (uma vez por abertura do app), mesmo se a sessão carregar antes.
    var aberturaVista by rememberSaveable { mutableStateOf(false) }
    if (!aberturaVista || estado == EstadoSessao.Carregando) {
        TelaAbertura(aoTerminar = { aberturaVista = true })
        return
    }

    when (val e = estado) {
        EstadoSessao.Carregando -> Unit
        EstadoSessao.Deslogado -> TelaLogin(container)
        is EstadoSessao.Logado ->
            if (!e.usuario.cadastroCompleto) {
                TelaCompletarCadastro(container, e.usuario)
            } else {
                TelaPrincipal(container, e.usuario, abaSalva)
            }
    }
}

/** Duração da entrada da abertura (zoom + fade), dentro dos 600 ms pedidos. */
private const val ENTRADA_MS = 500

/**
 * Abertura: tela toda no amarelo da marca, a lupa no centro e, abaixo, "Tabelapp" em vermelho escuro
 * e "Quem pesquisa economiza". A lupa fica no mesmo lugar e tamanho do splash do Android 12+
 * (288 dp, values-v31), para a troca não piscar: lá ela já está na tela, então só o texto entra.
 * No Android 8 a 11 a janela é só amarela (fundo_abertura) e a lupa entra com zoom e fade.
 */
@Composable
private fun TelaAbertura(aoTerminar: () -> Unit) {
    val lupaJaNaTela = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lupa = remember { Animatable(if (lupaJaNaTela) 1f else 0f) }
    val texto = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { lupa.animateTo(1f, tween(ENTRADA_MS, easing = FastOutSlowInEasing)) }
        texto.animateTo(1f, tween(ENTRADA_MS, delayMillis = 100, easing = FastOutSlowInEasing))
        delay(250)
        aoTerminar()
    }
    Box(Modifier.fillMaxSize().background(CoresTabelapp.AmareloMarca), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.lupa_marca), contentDescription = null,
            modifier = Modifier.size(288.dp).graphicsLayer {
                alpha = lupa.value
                val escala = 0.85f + 0.15f * lupa.value
                scaleX = escala
                scaleY = escala
            },
        )
        Column(
            Modifier.offset(y = 130.dp).graphicsLayer {
                alpha = texto.value
                translationY = (1f - texto.value) * 12.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Tabelapp", color = CoresTabelapp.VermelhoMarca, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("Quem pesquisa economiza", color = CoresTabelapp.SobreDestaque, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        CircularProgressIndicator(
            color = CoresTabelapp.SobreDestaque,
            strokeWidth = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).size(24.dp),
        )
    }
}
