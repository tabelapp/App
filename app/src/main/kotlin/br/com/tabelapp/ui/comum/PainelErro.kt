package br.com.tabelapp.ui.comum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.tabelapp.R
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.erros.Textos

/** Um botão das telas de erro. */
data class AcaoErro(val rotulo: String, val aoTocar: () -> Unit, val habilitado: Boolean = true)

/** Ícone suave de cada tipo de erro (nada de vermelho de alarme). */
fun iconeDoErro(tipo: TipoErro): ImageVector = when (tipo) {
    TipoErro.SEFAZ_INDISPONIVEL -> Icons.Outlined.HourglassEmpty
    TipoErro.SEM_INTERNET -> Icons.Outlined.WifiOff
    TipoErro.CAMERA_NEGADA -> Icons.Outlined.PhotoCamera
    TipoErro.QR_INVALIDO -> Icons.Outlined.QrCodeScanner
    TipoErro.SERVIDOR_INSTAVEL -> Icons.Outlined.CloudOff
    TipoErro.SESSAO_EXPIRADA -> Icons.Outlined.Lock
    TipoErro.BUSCA_VAZIA -> Icons.Outlined.SearchOff
    TipoErro.FALHA_UPLOAD_FOTO -> Icons.Outlined.AddAPhoto
    TipoErro.FALHA_ENVIO_NF -> Icons.Outlined.Sync
    TipoErro.FALHA_PAGAMENTO -> Icons.Outlined.Payments
    TipoErro.INESPERADO -> Icons.Outlined.Info
}

/**
 * Componente único de erro do app: ícone suave, título, texto, dica opcional,
 * botão principal e secundário opcional. [compacto] = dentro de um cartão (no
 * meio de uma tela); senão ocupa a área como uma tela calma.
 */
@Composable
fun PainelErro(
    titulo: String,
    texto: String,
    principal: AcaoErro?,
    modifier: Modifier = Modifier,
    icone: ImageVector = Icons.Outlined.Info,
    dica: String? = null,
    secundario: AcaoErro? = null,
    observacao: String? = null,
    compacto: Boolean = false,
) {
    val conteudo = @Composable {
        Column(
            Modifier.fillMaxWidth().padding(if (compacto) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compacto) 8.dp else 14.dp),
        ) {
            Box(
                Modifier.size(if (compacto) 48.dp else 72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icone, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(if (compacto) 26.dp else 38.dp))
            }
            Text(
                titulo,
                style = if (compacto) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(texto, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            dica?.let {
                Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            principal?.let {
                Button(onClick = it.aoTocar, enabled = it.habilitado,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(it.rotulo) }
            }
            secundario?.let {
                OutlinedButton(onClick = it.aoTocar, enabled = it.habilitado,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(it.rotulo) }
            }
            observacao?.let {
                Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (compacto) {
        Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            conteudo()
        }
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { conteudo() }
    }
}

/** Atalho: textos do [tipo] vindos do strings.xml. */
@Composable
fun PainelErro(
    tipo: TipoErro,
    principal: AcaoErro?,
    modifier: Modifier = Modifier,
    secundario: AcaoErro? = null,
    observacao: String? = null,
    compacto: Boolean = false,
    texto: String? = null,
) {
    PainelErro(
        titulo = stringResource(Textos.titulo(tipo)),
        texto = texto ?: stringResource(Textos.texto(tipo)),
        principal = principal,
        modifier = modifier,
        icone = iconeDoErro(tipo),
        dica = Textos.dica(tipo)?.let { stringResource(it) },
        secundario = secundario,
        observacao = observacao,
        compacto = compacto,
    )
}

/** Versão em diálogo (ex.: erro numa ação dentro de outra tela). */
@Composable
fun DialogoErro(
    tipo: TipoErro,
    aoFechar: () -> Unit,
    principal: AcaoErro? = null,
    secundario: AcaoErro? = null,
    texto: String? = null,
) {
    val botaoSecundario: (@Composable () -> Unit)? = secundario?.let { acao ->
        @Composable { TextButton(onClick = acao.aoTocar) { Text(acao.rotulo) } }
    }
    AlertDialog(
        onDismissRequest = aoFechar,
        icon = { Icon(iconeDoErro(tipo), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) },
        title = { Text(stringResource(Textos.titulo(tipo)), textAlign = TextAlign.Center) },
        text = { Text(texto ?: stringResource(Textos.texto(tipo)), textAlign = TextAlign.Center) },
        confirmButton = {
            val acao = principal ?: AcaoErro(stringResource(R.string.erro_botao_ok), aoFechar)
            TextButton(onClick = acao.aoTocar, enabled = acao.habilitado) { Text(acao.rotulo) }
        },
        dismissButton = botaoSecundario,
    )
}
