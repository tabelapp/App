package br.com.tabelapp.ui.pdv

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.tabelapp.R
import br.com.tabelapp.core.ContatoTabelapp
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.Mensagens
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.dados.Imagens
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.CoresTabelapp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edição do cadastro: nome de exibição, site e dados da loja. */
@Composable
fun DialogoCadastro(
    nomeAtual: String,
    siteAtual: String?,
    loja: LojaPdv,
    aoSalvar: (nome: String, site: String, loja: LojaPdv) -> Unit,
    aoFechar: () -> Unit,
) {
    var nome by rememberSaveable { mutableStateOf(nomeAtual) }
    var site by rememberSaveable { mutableStateOf(siteAtual.orEmpty()) }
    var nomeLoja by rememberSaveable { mutableStateOf(loja.nome.orEmpty()) }
    var endereco by rememberSaveable { mutableStateOf(loja.logradouro) }
    var bairro by rememberSaveable { mutableStateOf(loja.bairro.orEmpty()) }
    var cidade by rememberSaveable { mutableStateOf(loja.cidade.orEmpty()) }
    var telefone by rememberSaveable { mutableStateOf(loja.telefone.orEmpty()) }
    var whatsapp by rememberSaveable { mutableStateOf(loja.whatsapp.orEmpty()) }

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Dados do estabelecimento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Campo("Nome do estabelecimento", nome) { nome = it.take(120) }
                Campo("Site ou Instagram (opcional)", site) { site = it.take(200) }
                Campo("Nome da loja (opcional, ex.: Loja Centro)", nomeLoja) { nomeLoja = it.take(80) }
                Campo("Endereço (rua e número)", endereco) { endereco = it.take(200) }
                Campo("Bairro", bairro) { bairro = it.take(80) }
                Campo("Cidade", cidade) { cidade = it.take(80) }
                Campo("Telefone", telefone, KeyboardType.Phone) { telefone = it.take(20) }
                Campo("WhatsApp", whatsapp, KeyboardType.Phone) { whatsapp = it.take(20) }
                Text(
                    "O WhatsApp aparece no card de preço: o cliente fala direto com você, já com a oferta na mensagem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                aoSalvar(
                    nome, site,
                    loja.copy(
                        nome = nomeLoja.ifBlank { null }, logradouro = endereco, bairro = bairro.ifBlank { null },
                        cidade = cidade.ifBlank { null }, telefone = telefone.ifBlank { null }, whatsapp = whatsapp.ifBlank { null },
                    ),
                )
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun Campo(rotulo: String, valor: String, teclado: KeyboardType = KeyboardType.Text, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = aoMudar, label = { Text(rotulo) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = teclado), modifier = Modifier.fillMaxWidth(),
    )
}

/** Pacotes de visualização do banner (briefing): 100/250/500 por R$ 10/25/50. */
private val pacotes = listOf(100 to 1000L, 250 to 2500L, 500 to 5000L)

/**
 * Nova promoção (banner pago): aparece na busca para quem pesquisa as palavras
 * escolhidas (ou para todos), até acabarem as visualizações compradas.
 * Com [existente], edita a promoção (texto, link, arte e palavras; o pacote não muda)
 * e salva por [aoEditar].
 */
@Composable
fun DialogoPromocao(
    imagens: Imagens,
    aoCriar: (titulo: String, descricao: String, link: String, arte: ByteArray?, palavras: List<String>, visualizacoes: Int,
              aoErro: (String) -> Unit, aoTerminar: () -> Unit) -> Unit,
    aoFechar: () -> Unit,
    existente: PromocaoPdv? = null,
    aoEditar: (titulo: String, descricao: String, link: String, arte: ByteArray?, removerArte: Boolean, palavras: List<String>,
               aoErro: (String) -> Unit, aoTerminar: () -> Unit) -> Unit = { _, _, _, _, _, _, _, _ -> },
) {
    val escopo = rememberCoroutineScope()
    val contexto = LocalContext.current
    val editando = existente != null
    var titulo by rememberSaveable { mutableStateOf(existente?.titulo.orEmpty()) }
    var descricao by rememberSaveable { mutableStateOf(existente?.descricao.orEmpty()) }
    var link by rememberSaveable { mutableStateOf(existente?.link.orEmpty()) }
    var palavras by rememberSaveable { mutableStateOf(existente?.palavrasChave?.joinToString(", ").orEmpty()) }
    /** Edição: tirar a imagem atual (sem escolher outra). */
    var removerArte by rememberSaveable { mutableStateOf(false) }
    var pacote by rememberSaveable { mutableStateOf(100) }
    var arte by remember { mutableStateOf<ByteArray?>(null) }
    var miniatura by remember { mutableStateOf<ImageBitmap?>(null) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) escopo.launch {
            val jpeg = withContext(Dispatchers.Default) { imagens.jpegReduzido(uri, ladoMaximo = 1200) }
            arte = jpeg
            removerArte = false
            miniatura = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }?.asImageBitmap()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!enviando) aoFechar() },
        title = { Text(if (editando) "Editar promoção" else "Nova promoção (banner)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Seu banner aparece na tela de busca (no topo e no lugar do 5º card), do mesmo tamanho de um card de preço.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Campo("Título (ex.: Semana do hortifrúti)", titulo) { titulo = it.take(80) }
                Campo("Texto (opcional)", descricao) { descricao = it.take(500) }
                Campo("Link ao tocar (site, Instagram… opcional)", link, KeyboardType.Uri) { link = it.take(300) }
                OutlinedButton(onClick = {
                    galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            arte != null -> "Trocar imagem"
                            editando && existente?.artePath != null && !removerArte -> "Trocar a imagem atual"
                            else -> "Imagem do banner (opcional)"
                        }
                    )
                }
                // Edição: a imagem atual continua, a menos que troque ou tire.
                if (editando && existente?.artePath != null && arte == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (removerArte) "Sem imagem (o banner mostra o título e o texto)." else "Imagem atual mantida.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { removerArte = !removerArte }) {
                            Text(if (removerArte) "Manter imagem" else "Tirar imagem")
                        }
                    }
                }
                miniatura?.let {
                    // Prévia na proporção do banner (3 x 1), com o mesmo corte da busca.
                    Image(it, contentDescription = "Imagem do banner", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(8.dp)))
                }
                Text(stringResource(R.string.promocao_arte_tamanho), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.promocao_arte_dica), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Campo("Palavras-chave (separe por vírgula)", palavras) { palavras = it.take(300) }
                Text("Ex.: cerveja, carne. O banner aparece quando a busca tiver uma delas. Em branco: aparece para todos.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (existente != null) {
                    // O pacote já comprado não muda na edição.
                    if (existente.visualizacoesContratadas > 0) {
                        Text("Visualizações: ${existente.visualizacoesExibidas} de ${existente.visualizacoesContratadas} (não mudam)",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Pacote de visualizações", fontWeight = FontWeight.SemiBold)
                    pacotes.forEach { (vis, valor) ->
                        Row(Modifier.fillMaxWidth().clickable { pacote = vis }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pacote == vis, onClick = { pacote = vis })
                            Text("$vis visualizações — ${Dinheiro.formatar(valor)}")
                        }
                    }
                }
                erro?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        WhatsApp.abrirConversa(contexto, ContatoTabelapp.WHATSAPP, Mensagens.FALE_CONOSCO)
                    }) { Text(stringResource(R.string.erro_botao_fale_conosco)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !enviando, onClick = {
                if (titulo.isBlank()) {
                    erro = "Dê um título para a promoção."
                    return@TextButton
                }
                if (link.isNotBlank() && !link.trim().startsWith("http")) link = "https://" + link.trim()
                enviando = true
                val lista = palavras.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (editando) {
                    aoEditar(titulo, descricao, link, arte, removerArte, lista, { e -> erro = e; enviando = false }, { enviando = false })
                } else {
                    aoCriar(titulo, descricao, link, arte, lista, pacote, { e -> erro = e; enviando = false }, { enviando = false })
                }
            }) {
                if (enviando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (editando) "Salvar" else "Criar e pagar")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !enviando) { Text("Cancelar") } },
    )
}

/**
 * Pedido de pagamento criado. Até o Pix automático, o pagamento é combinado pelo
 * WhatsApp do Tabelapp e confirmado pelo Admin (o banner entra no ar / as
 * operações são liberadas na hora da confirmação).
 */
@Composable
fun DialogoPagamento(pdvNome: String, pagamento: PagamentoCriado, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Falta só o pagamento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pagamento.descricao, fontWeight = FontWeight.SemiBold)
                Text("Valor: ${Dinheiro.formatar(pagamento.valorCentavos)}", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
                Text(
                    "Por enquanto o pagamento (Pix) é combinado pelo WhatsApp do Tabelapp. Assim que confirmarmos, " +
                        "seu pedido é ativado automaticamente. Código do pedido: ${pagamento.codigo.take(8).uppercase()}.",
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                WhatsApp.abrirConversa(
                    contexto, ContatoTabelapp.WHATSAPP,
                    Mensagens.pagamento(pdvNome, pagamento.descricao, pagamento.valorCentavos, pagamento.codigo),
                )
                aoFechar()
            }) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Pagar pelo WhatsApp")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Depois") } },
        modifier = Modifier.padding(8.dp),
    )
}
