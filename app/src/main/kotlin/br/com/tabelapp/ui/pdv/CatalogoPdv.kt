package br.com.tabelapp.ui.pdv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import br.com.tabelapp.R
import br.com.tabelapp.core.AvisoFundador
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.Validade
import java.io.File
import java.time.LocalDate

/** Tamanho do catálogo, plano e (no piloto) o período de parceiro fundador. */
@Composable
fun CartaoCatalogo(c: StatusCatalogo, aoVerPlanos: () -> Unit) {
    val hoje = LocalDate.now()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.catalogo_resumo, c.itensAtivos, c.limite),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.catalogo_plano, c.planoEfetivoNome), style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { (c.itensAtivos.toFloat() / c.limite.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Faixa discreta do parceiro fundador e os avisos de fim (7 dias antes e no dia).
            when (c.avisoFundador(hoje)) {
                AvisoFundador.FAIXA -> Faixa(stringResource(R.string.fundador_faixa, Validade.formatar(c.fundadorAte!!)))
                AvisoFundador.FALTAM_POUCOS_DIAS -> Faixa(
                    stringResource(
                        R.string.fundador_faltam_dias, (c.diasDeFundador(hoje) ?: 0).toInt(),
                        Validade.formatar(c.fundadorAte!!), c.limitePlano, c.planoNome,
                    )
                )
                AvisoFundador.ULTIMO_DIA -> Faixa(stringResource(R.string.fundador_ultimo_dia, c.limitePlano, c.planoNome))
                AvisoFundador.NENHUM -> if (c.itensAtivos > c.limite) {
                    Faixa(stringResource(R.string.catalogo_acima_do_limite, c.planoEfetivoNome, c.limite))
                }
            }
            Text(stringResource(R.string.catalogo_explicacao), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = aoVerPlanos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.catalogo_ver_planos))
            }
        }
    }
}

@Composable
private fun Faixa(texto: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Text(texto, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

/** Mensagem calma de limite atingido: "Ver planos" / "Agora não". */
@Composable
fun DialogoLimite(c: StatusCatalogo, aoVerPlanos: () -> Unit, aoFechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) },
        title = { Text(stringResource(R.string.catalogo_limite_titulo), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.catalogo_limite_texto, c.limite, c.planoEfetivoNome), textAlign = TextAlign.Center) },
        confirmButton = { TextButton(onClick = aoVerPlanos) { Text(stringResource(R.string.catalogo_ver_planos)) } },
        dismissButton = { TextButton(onClick = aoFechar) { Text(stringResource(R.string.catalogo_agora_nao)) } },
    )
}

/** "Ver planos": as faixas da tabela `planos`. Sem cobrança por enquanto: "Quero ser avisado". */
@Composable
fun TelaPlanos(
    estado: EstadoPlanos,
    planoAtual: String?,
    aoQuererSerAvisado: (Plano) -> Unit,
    aoFechar: () -> Unit,
) {
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.planos_titulo), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.planos_fechar))
                    }
                }
                Text(stringResource(R.string.planos_intro))
                Text(stringResource(R.string.planos_ainda_sem_cobranca), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (estado.carregando) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                estado.lista.forEach { p ->
                    CartaoPlano(
                        plano = p,
                        atual = p.codigo == planoAtual,
                        interessado = p.codigo in estado.interessados,
                        enviando = estado.enviando == p.codigo,
                        aoQuererSerAvisado = { aoQuererSerAvisado(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoPlano(plano: Plano, atual: Boolean, interessado: Boolean, enviando: Boolean, aoQuererSerAvisado: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plano.nome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (atual) Text(stringResource(R.string.plano_atual), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.plano_limite_itens, plano.limiteItens))
            Text(
                when (val preco = plano.precoReferenciaCentavos) {
                    null -> stringResource(R.string.plano_preco_em_breve)
                    0L -> stringResource(R.string.plano_gratis)
                    else -> stringResource(R.string.plano_preco_referencia, Dinheiro.formatar(preco))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // No lugar do pagamento: registrar o interesse.
            if (!atual && plano.precoReferenciaCentavos != 0L) {
                if (interessado) {
                    Text(stringResource(R.string.planos_anotado), color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = aoQuererSerAvisado, enabled = !enviando, modifier = Modifier.fillMaxWidth()) {
                        if (enviando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.plano_quero_ser_avisado))
                    }
                }
            }
        }
    }
}

/** Resumo depois de importar a planilha: quantos entraram e quantos ficaram de fora. */
@Composable
fun DialogoResultadoImportacao(r: ResultadoImportacao, aoVerPlanos: () -> Unit, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val botaoPlanos: (@Composable () -> Unit)? = if (r.foraDoLimite > 0) {
        @Composable { TextButton(onClick = aoVerPlanos) { Text(stringResource(R.string.catalogo_ver_planos)) } }
    } else null
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(stringResource(R.string.importacao_importados, r.importados)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.foraDoLimite > 0) {
                    Text(stringResource(R.string.importacao_fora, r.foraDoLimite, r.limite))
                    TextButton(onClick = { compartilharItensFora(contexto, r) }) {
                        Text(stringResource(R.string.importacao_baixar_fora))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = aoFechar) { Text(stringResource(R.string.erro_botao_ok)) } },
        dismissButton = botaoPlanos,
    )
}

/** Lista dos itens que ficaram de fora (CSV) para o PDV salvar ou enviar para onde quiser. */
fun compartilharItensFora(contexto: Context, r: ResultadoImportacao) {
    val pasta = File(contexto.cacheDir, "compartilhar").apply { mkdirs() }
    val arquivo = File(pasta, "itens-fora-do-limite.csv").apply { writeText("﻿" + r.csvDosQueFicaramDeFora()) }
    val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", arquivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        contexto.startActivity(Intent.createChooser(envio, contexto.getString(R.string.importacao_baixar_fora)))
    } catch (_: ActivityNotFoundException) {
        // Nenhum app para receber o arquivo: nada a fazer.
    }
}
