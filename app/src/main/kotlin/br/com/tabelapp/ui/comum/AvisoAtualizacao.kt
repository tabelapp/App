package br.com.tabelapp.ui.comum

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.tabelapp.R
import br.com.tabelapp.core.AvisoAtualizacao
import br.com.tabelapp.core.EstadoAtualizacao
import br.com.tabelapp.dados.AtualizacaoApp
import br.com.tabelapp.ui.tema.CoresTabelapp

/**
 * Faixa discreta de versão nova (Play Store, modo flexível): "Atualizar" / "Agora não"; depois de
 * baixada, "Reiniciar para concluir". Não aparece quando [ocupado] (leitura de nota / envio de
 * preços) nem de novo no dia em que foi dispensada. Sem atualização ou com qualquer falha, não
 * aparece nada.
 */
@Composable
fun AvisoAtualizacaoApp(atualizacao: AtualizacaoApp, ocupado: Boolean, modifier: Modifier = Modifier) {
    val estado by atualizacao.estado.collectAsStateWithLifecycle()
    val dispensadoEm by atualizacao.dispensadoEm.collectAsStateWithLifecycle()
    // Ao abrir o app (uma vez por tela principal): pergunta à Play se há versão nova.
    LaunchedEffect(Unit) { atualizacao.verificar() }
    val lancador = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        atualizacao.resultadoDaPlay(r.resultCode == Activity.RESULT_OK)
    }

    when {
        AvisoAtualizacao.mostrarDisponivel(estado, dispensadoEm, atualizacao.hoje(), ocupado) -> Faixa(
            texto = stringResource(R.string.atualizacao_disponivel),
            modifier = modifier,
        ) {
            TextButton(onClick = atualizacao::dispensar) { Text(stringResource(R.string.atualizacao_agora_nao)) }
            TextButton(onClick = { atualizacao.atualizar(lancador) }) {
                Text(stringResource(R.string.atualizacao_atualizar), color = CoresTabelapp.Destaque)
            }
        }
        AvisoAtualizacao.mostrarReiniciar(estado, ocupado) -> Faixa(
            texto = stringResource(R.string.atualizacao_baixada),
            modifier = modifier,
        ) {
            TextButton(onClick = atualizacao::concluir) {
                Text(stringResource(R.string.atualizacao_reiniciar), color = CoresTabelapp.Destaque)
            }
        }
        else -> Unit
    }
}

@Composable
private fun Faixa(texto: String, modifier: Modifier, botoes: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            botoes()
        }
    }
}
