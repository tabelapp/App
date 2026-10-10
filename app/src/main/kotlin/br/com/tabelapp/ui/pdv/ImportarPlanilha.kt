package br.com.tabelapp.ui.pdv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.LeitorPlanilha
import java.io.File

/** Maior planilha aceita (bem acima de milhares de produtos). */
private const val TAMANHO_MAXIMO = 5 * 1024 * 1024

/** Tipos que o seletor de arquivos oferece: Excel e CSV (alguns apps marcam CSV como texto). */
val TIPOS_PLANILHA = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/octet-stream",
)

/** Lê o arquivo escolhido; null = grande demais ou ilegível (já avisa o usuário). */
fun lerArquivoPlanilha(contexto: Context, uri: Uri): Pair<ByteArray, String>? {
    val nome = runCatching {
        contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "planilha"
    val bytes = runCatching {
        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
            val dados = entrada.readNBytesCompat(TAMANHO_MAXIMO + 1)
            dados.takeIf { it.size <= TAMANHO_MAXIMO }
        }
    }.getOrNull()
    if (bytes == null) {
        Toast.makeText(contexto, "Não consegui abrir o arquivo (máx. 5 MB).", Toast.LENGTH_LONG).show()
        return null
    }
    return bytes to nome
}

private fun java.io.InputStream.readNBytesCompat(limite: Int): ByteArray {
    val saida = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (saida.size() < limite) {
        val n = read(buffer)
        if (n < 0) break
        saida.write(buffer, 0, n)
    }
    return saida.toByteArray()
}

/**
 * Envia o modelo da planilha (CSV, abre no Excel e no Google Planilhas) para onde
 * o PDV escolher: e-mail, Drive, WhatsApp, "Salvar em arquivos"...
 */
fun compartilharModeloPlanilha(contexto: Context) {
    val pasta = File(contexto.cacheDir, "compartilhar").apply { mkdirs() }
    // O BOM faz o Excel reconhecer os acentos.
    val arquivo = File(pasta, "modelo-tabelapp.csv").apply { writeText("﻿" + LeitorPlanilha.modeloCsv) }
    val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Modelo de planilha de preços — Tabelapp")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        contexto.startActivity(Intent.createChooser(envio, "Salvar ou enviar o modelo"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(contexto, "Nenhum app para receber o arquivo.", Toast.LENGTH_SHORT).show()
    }
}

/** Prévia da importação: o que muda, quanto custa e os erros por linha. */
@Composable
fun DialogoPlanilha(
    estado: EstadoPlanilha,
    aoImportar: () -> Unit,
    aoComprarPacote: () -> Unit,
    aoConferirDeNovo: () -> Unit,
    aoFechar: () -> Unit,
) {
    val sim = estado.simulacao
    val podeImportar = !estado.processando && sim != null && sim.cabeNaCota && estado.linhas.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!estado.processando) aoFechar() },
        title = { Text("Importar planilha") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(estado.nomeArquivo, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (estado.processando) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Conferindo a planilha…")
                    }
                }
                if (sim != null) {
                    Text("${estado.linhas.size} produto(s) prontos para publicar", fontWeight = FontWeight.Bold)
                    Text("Novos: ${sim.criados} · Preço maior: ${sim.aumentados}")
                    Text("Preço menor: ${sim.diminuidos} · Sem mudança: ${sim.inalterados}")
                    Text(
                        "Vai usar ${sim.operacoes} operação(ões). Você tem ${sim.restantes}.",
                        fontWeight = FontWeight.SemiBold,
                        color = if (sim.cabeNaCota) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!sim.cabeNaCota) {
                        Text(
                            "Faltam operações: são necessários ${sim.pacotesNecessarios} pacote(s) de +50 " +
                                "(${Dinheiro.formatar(sim.pacotesNecessarios * 1000L)}). Você também pode tirar " +
                                "linhas da planilha — baixar preço e manter o mesmo preço não custam nada.",
                        )
                        OutlinedButton(onClick = aoComprarPacote, modifier = Modifier.fillMaxWidth()) {
                            Text("Comprar +50 operações (R$ 10)")
                        }
                        TextButton(onClick = aoConferirDeNovo, modifier = Modifier.fillMaxWidth()) {
                            Text("Já paguei — conferir de novo")
                        }
                    }
                }
                estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (estado.erros.isNotEmpty()) {
                    Text(
                        "${estado.erros.size} linha(s) com problema — ficam de fora:",
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    estado.erros.take(30).forEach {
                        Text("Linha ${it.numero}: ${it.mensagem}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (estado.erros.size > 30) {
                        Text("… e mais ${estado.erros.size - 30}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = aoImportar, enabled = podeImportar) {
                Text(if (sim != null) "Publicar ${estado.linhas.size}" else "Publicar")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !estado.processando) { Text("Cancelar") } },
    )
}
