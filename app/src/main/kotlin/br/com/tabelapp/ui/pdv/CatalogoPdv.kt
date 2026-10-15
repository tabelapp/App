package br.com.tabelapp.ui.pdv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import br.com.tabelapp.core.AvisoPlano
import br.com.tabelapp.core.ContatoTabelapp
import br.com.tabelapp.core.OrigemLimite
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PrecoPlano
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.Validade
import br.com.tabelapp.ui.comum.WhatsApp
import br.com.tabelapp.ui.tema.CoresTabelapp
import java.io.File
import java.time.LocalDate

/** Tamanho do catálogo, plano, teste grátis e os avisos (fim do teste/plano, tolerância, pausados). */
@Composable
fun CartaoCatalogo(c: StatusCatalogo, aoVerPlanos: () -> Unit) {
    val hoje = LocalDate.now()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.catalogo_resumo, c.itensPublicados, c.limite),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.catalogo_plano, c.planoEfetivoNome), style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { (c.itensAtivos.toFloat() / c.limite.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (c.temPlanoPago) {
                Text(stringResource(R.string.catalogo_plano_ate, Validade.formatar(c.planoAte!!)),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (c.pausadosPdv > 0) {
                Text(stringResource(R.string.catalogo_pausados_pdv, c.pausadosPdv), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            c.avisos(hoje).forEach { aviso -> Faixa(textoDoAviso(aviso)) }
            Text(stringResource(R.string.catalogo_explicacao), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = aoVerPlanos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.catalogo_ver_planos))
            }
        }
    }
}

@Composable
private fun textoDoAviso(aviso: AvisoPlano): String = when (aviso) {
    is AvisoPlano.FaixaTeste -> stringResource(R.string.teste_faixa, Validade.formatar(aviso.ate))
    is AvisoPlano.TesteTermina -> when (aviso.dias) {
        0 -> stringResource(R.string.teste_termina_hoje, aviso.limiteGratis)
        1 -> stringResource(R.string.teste_termina_amanha, aviso.limiteGratis)
        else -> stringResource(R.string.teste_termina_dias, aviso.dias, aviso.limiteGratis)
    }
    is AvisoPlano.PlanoTermina -> when (aviso.dias) {
        0 -> stringResource(R.string.plano_termina_hoje, aviso.planoNome)
        1 -> stringResource(R.string.plano_termina_amanha, aviso.planoNome)
        else -> stringResource(R.string.plano_termina_dias, aviso.planoNome, aviso.dias, Validade.formatar(aviso.ate))
    }
    is AvisoPlano.Tolerancia -> stringResource(
        if (aviso.origem == OrigemLimite.TESTE) R.string.tolerancia_teste else R.string.tolerancia_plano,
        Validade.formatar(aviso.ate), aviso.limiteGratis,
    )
    is AvisoPlano.ItensPausados -> stringResource(R.string.itens_pausados_texto)
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

/**
 * "Ver planos": faixas e preços da tabela `planos`. Sem cobrança no app: os pagos têm
 * "Assinar pelo WhatsApp" com a mensagem pronta; o Admin ativa depois do Pix.
 */
@Composable
fun TelaPlanos(
    estado: EstadoPlanos,
    catalogo: StatusCatalogo?,
    nomeNegocio: String,
    aoFechar: () -> Unit,
) {
    val contexto = LocalContext.current
    val whatsapp = catalogo?.whatsappContato?.takeIf { it.isNotBlank() } ?: ContatoTabelapp.WHATSAPP
    // "Seu plano": o contratado (o grátis, enquanto não houver plano pago valendo).
    val planoAtual = catalogo?.let { if (it.temPlanoPago) it.plano else Plano.GRATIS }
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
                catalogo?.avisos(LocalDate.now())?.forEach { Faixa(textoDoAviso(it)) }
                Text(stringResource(R.string.planos_apoio_pix), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (estado.carregando) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                estado.erro?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                estado.lista.forEach { p ->
                    val atual = p.codigo == planoAtual
                    CartaoPlano(
                        plano = p,
                        atual = atual,
                        planoAte = catalogo?.takeIf { atual && it.temPlanoPago }?.planoAte,
                        aoAssinar = {
                            WhatsApp.abrirConversa(
                                contexto, whatsapp,
                                contexto.getString(R.string.plano_mensagem_whatsapp, p.nome, nomeNegocio),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoPlano(plano: Plano, atual: Boolean, planoAte: LocalDate?, aoAssinar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plano.nome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (atual) Text(stringResource(R.string.plano_atual), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.plano_limite_itens, plano.limiteItens))
            val preco = plano.precoCentavos
            if (!plano.gratis && preco != null && preco > 0) {
                Text(PrecoPlano.porMes(preco), fontWeight = FontWeight.Bold, color = CoresTabelapp.Preco)
            }
            planoAte?.let {
                Text(stringResource(R.string.catalogo_plano_ate, Validade.formatar(it)), style = MaterialTheme.typography.bodySmall)
            }
            if (!plano.gratis) {
                Button(onClick = aoAssinar, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.plano_assinar_whatsapp))
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
