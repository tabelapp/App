package br.com.tabelapp.ui.comum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Mensagem curta em folha que sobe da parte de baixo da tela (ex.: "Sobre este preço").
 * Mesmo tom das mensagens amigáveis do app: título, texto, botão principal e um link secundário.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolhaMensagem(
    titulo: String,
    texto: String?,
    principal: AcaoErro,
    aoFechar: () -> Unit,
    secundario: AcaoErro? = null,
    conteudo: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = aoFechar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            texto?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            conteudo()
            Button(onClick = principal.aoTocar, enabled = principal.habilitado,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(principal.rotulo) }
            secundario?.let {
                TextButton(onClick = it.aoTocar, enabled = it.habilitado, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(it.rotulo)
                }
            }
        }
    }
}
