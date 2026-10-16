package br.com.tabelapp.ui.comum

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.tabelapp.R
import br.com.tabelapp.core.TextoLegal

/** Os dois textos legais do app (arquivos .md em res/raw, os mesmos das páginas web). */
enum class DocumentoLegal(@RawRes val arquivo: Int) {
    PRIVACIDADE(R.raw.politica_privacidade),
    TERMOS(R.raw.termos_uso),
}

/** Mostra a Política de Privacidade ou os Termos de Uso em tela cheia. */
@Composable
fun TelaTextoLegal(documento: DocumentoLegal, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val blocos = remember(documento) {
        TextoLegal.blocos(contexto.resources.openRawResource(documento.arquivo).bufferedReader().use { it.readText() })
    }
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = aoFechar) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.legal_fechar))
                    }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    blocos.forEach { b ->
                        when (b) {
                            is TextoLegal.Bloco.Titulo ->
                                Text(b.texto, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            is TextoLegal.Bloco.Subtitulo -> Text(b.texto, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            is TextoLegal.Bloco.Item -> Text(comNegrito("• " + b.texto), style = MaterialTheme.typography.bodyMedium)
                            is TextoLegal.Bloco.Paragrafo -> Text(comNegrito(b.texto), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun comNegrito(texto: String) = buildAnnotatedString {
    TextoLegal.trechos(texto).forEach { (trecho, negrito) ->
        if (negrito) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(trecho) } else append(trecho)
    }
}

/** Caixa "Li e aceito os Termos de uso e a Política de privacidade", com os dois links. */
@Composable
fun AceiteTermos(aceito: Boolean, aoMudar: (Boolean) -> Unit) {
    var aberto by remember { mutableStateOf<DocumentoLegal?>(null) }
    aberto?.let { TelaTextoLegal(it, aoFechar = { aberto = null }) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { aoMudar(!aceito) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = aceito, onCheckedChange = aoMudar)
            Text(stringResource(R.string.legal_aceite), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(start = 40.dp)) {
            TextButton(onClick = { aberto = DocumentoLegal.TERMOS }) { Text(stringResource(R.string.legal_termos)) }
            TextButton(onClick = { aberto = DocumentoLegal.PRIVACIDADE }) { Text(stringResource(R.string.legal_privacidade)) }
        }
    }
}
