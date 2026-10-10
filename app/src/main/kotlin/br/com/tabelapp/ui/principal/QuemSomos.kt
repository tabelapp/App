package br.com.tabelapp.ui.principal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.tabelapp.R
import br.com.tabelapp.core.ContatoTabelapp
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.VermelhoTabelapp

/** "Quem somos": o manifesto do Tabelapp e o contato pelo WhatsApp. */
@Composable
fun QuemSomos(aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quem somos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = "Fechar") }
                }
                Image(
                    painter = painterResource(R.drawable.logo_lupa), contentDescription = null,
                    modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
                )
                Text(
                    "Quem pesquisa economiza.",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = VermelhoTabelapp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Paragrafo(
                    "O Tabelapp nasceu em Petrópolis de uma ideia simples: o preço justo não deveria ser segredo. " +
                        "Todo dia, milhares de pessoas pagam valores muito diferentes pelo mesmo produto — só porque " +
                        "não tinham como comparar."
                )
                Paragrafo(
                    "Nós acreditamos na força de quem compra. Cada nota fiscal que você compartilha vira informação " +
                        "para a cidade inteira. Um gesto de segundos que ajuda o vizinho, a família e você mesmo a " +
                        "fazer o dinheiro render mais."
                )
                Paragrafo(
                    "Acreditamos também no comércio local que pratica bons preços. Aqui, quem é honesto com o " +
                        "cliente aparece — e é encontrado por quem está procurando."
                )
                HorizontalDivider()
                Text("Nosso compromisso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Item("Transparência", "os preços vêm da nota fiscal, lida direto na Sefaz, ou do próprio comércio.")
                Item("Privacidade", "guardamos só o vendedor, os produtos e os preços. Os dados de quem comprou nunca são armazenados.")
                Item("Colaboração", "o Tabelapp é feito por todos: quanto mais gente participa, mais todo mundo economiza.")
                Item("Respeito ao comércio", "cada estabelecimento é verificado antes de publicar seus preços.")
                HorizontalDivider()
                Paragrafo(
                    "Economizar não é sorte: é informação. E informação, quando compartilhada, vira poder para todos. " +
                        "Junte-se a nós — pesquise, compartilhe e ajude Petrópolis a economizar."
                )
                Text(
                    "Equipe Tabelapp",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("Fale conosco", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Dúvidas, sugestões, parcerias ou quer anunciar? Chame a gente no WhatsApp.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { WhatsApp.abrirConversa(contexto, ContatoTabelapp.WHATSAPP, Mensagens.FALE_CONOSCO) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Falar no WhatsApp: (24) 98802-9067")
                }
            }
        }
    }
}

@Composable
private fun Paragrafo(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Item(titulo: String, texto: String) {
    Text(buildString { append("• "); append(titulo); append(": "); append(texto) }, style = MaterialTheme.typography.bodyMedium)
}
