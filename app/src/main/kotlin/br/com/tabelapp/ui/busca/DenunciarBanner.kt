package br.com.tabelapp.ui.busca

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.tabelapp.R
import br.com.tabelapp.core.Banner
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.ui.comum.AcaoErro
import br.com.tabelapp.ui.comum.FolhaMensagem
import kotlinx.coroutines.launch

/** Motivos da denúncia de banner: código gravado no banco + texto do chip. */
private val motivosBanner = listOf(
    "enganoso" to R.string.banner_motivo_enganoso,
    "ofensivo" to R.string.banner_motivo_ofensivo,
    "golpe" to R.string.banner_motivo_golpe,
    "outro" to R.string.banner_motivo_outro,
)

/**
 * "Denunciar este banner" (bandeirinha no canto do banner). Motivo opcional; a denúncia vai
 * para a fila do Admin, que pode tirar o banner do ar. Repetir não duplica.
 */
@Composable
fun FolhaDenunciarBanner(
    banner: Banner,
    aoDenunciar: suspend (motivo: String?) -> Boolean,
    aoFechar: () -> Unit,
) {
    var enviado by remember { mutableStateOf(false) }
    var motivo by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    if (enviado) {
        // Já tinha denunciado antes? O agradecimento é o mesmo.
        FolhaMensagem(
            titulo = stringResource(R.string.banner_denuncia_titulo),
            texto = stringResource(R.string.banner_denuncia_obrigado),
            principal = AcaoErro(stringResource(R.string.sobre_preco_entendi), aoFechar),
            aoFechar = aoFechar,
        )
        return
    }

    FolhaMensagem(
        titulo = stringResource(R.string.banner_denuncia_titulo),
        texto = stringResource(R.string.banner_denuncia_texto),
        principal = AcaoErro(stringResource(R.string.denuncia_enviar), {
            enviando = true
            erro = null
            escopo.launch {
                try {
                    aoDenunciar(motivo)
                    enviado = true
                } catch (e: ErroAmigavel) {
                    erro = e.message
                } finally {
                    enviando = false
                }
            }
        }, habilitado = !enviando),
        secundario = AcaoErro(stringResource(R.string.denuncia_cancelar), aoFechar),
        aoFechar = aoFechar,
        cabecalho = {
            Text("${banner.pdvNome}: ${banner.titulo}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            motivosBanner.forEach { (codigo, texto) ->
                FilterChip(
                    selected = motivo == codigo,
                    onClick = { motivo = if (motivo == codigo) null else codigo },
                    label = { Text(stringResource(texto)) },
                )
            }
        }
        erro?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
