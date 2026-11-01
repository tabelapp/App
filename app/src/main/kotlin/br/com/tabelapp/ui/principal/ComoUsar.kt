package br.com.tabelapp.ui.principal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** "Como usar" (menu ⋮): o tutorial que antes ficava dentro de "Quem somos". */
@Composable
fun TelaComoUsar(aoFechar: () -> Unit) {
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Como usar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = "Fechar") }
                }
                ConteudoComoUsar()
            }
        }
    }
}

@Composable
private fun ParagrafoComoUsar(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodyLarge)
}

/** Tutorial curto: para que serve o app e como usar cada aba. */
@Composable
private fun ConteudoComoUsar() {
    Text("Para que serve", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    ParagrafoComoUsar(
        "Para descobrir onde cada produto ou serviço está mais barato antes de sair de casa, montar a lista de " +
            "compras pelo menor preço e ajudar outras pessoas a economizar compartilhando os preços que você pagou."
    )
    Text("Como usar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Passo(
        1, "Buscar",
        "digite o produto (ex.: arroz 5kg). Enquanto você digita, o app sugere os produtos que têm preço. " +
            "Em \"Filtros\" você ordena por menor preço, maior preço ou loja, e vê tudo no mapa.",
    )
    Passo(
        2, "Card de preço",
        "mostra o preço, a loja, a data e a distância. Toque no nome da loja para abrir a página dela (endereço, " +
            "contato e todos os preços), no " +
            "WhatsApp para falar com o comércio e em ⓘ para saber de onde veio o preço ou avisar que não confere.",
    )
    Passo(
        3, "Enviar preços",
        "depois de uma compra, leia o QR Code da nota fiscal (ou digite os 44 números da chave). Os preços " +
            "da nota entram no app para todo mundo — os seus dados nunca aparecem. Em \"Minhas notas\", no menu " +
            "(⋮), você revê as notas que enviou.",
    )
    Passo(
        4, "Lista",
        "monte sua lista de compras e toque em \"Pesquisar melhores preços\": o app mostra quanto custa em " +
            "cada lugar e onde sai mais barato.",
    )
    Passo(
        5, "Para empresas",
        "com uma conta de CNPJ, a aba \"Meu negócio\" permite publicar a tabela de preços da sua loja " +
            "(digitando ou por planilha) e acompanhar seus banners na busca. Produtores e marcas que não " +
            "têm loja também podem se cadastrar como anunciantes.",
    )
    ParagrafoComoUsar("Dica: confirme sempre o preço na hora da compra — os valores podem mudar sem aviso.")
}

@Composable
private fun Passo(numero: Int, titulo: String, texto: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append("$numero. $titulo: ") }
            append(texto)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
