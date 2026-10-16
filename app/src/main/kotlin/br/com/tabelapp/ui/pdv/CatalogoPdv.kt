package br.com.tabelapp.ui.pdv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.FileProvider
import br.com.tabelapp.R
import br.com.tabelapp.core.AvisoPlano
import br.com.tabelapp.core.OrigemLimite
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.Validade
import java.io.File
import java.time.LocalDate

/**
 * Situação do catálogo, só o ESTADO (sem preço, sem botão de compra — decisão do fundador:
 * planos são contratados fora do app): plano, teste grátis, itens ativos e pausados, avisos.
 */
@Composable
fun CartaoCatalogo(c: StatusCatalogo) {
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
                Text(stringResource(R.string.catalogo_plano, c.planoNome), style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { (c.itensAtivos.toFloat() / c.limite.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (c.temPlanoPago) {
                Text(stringResource(R.string.catalogo_plano_ate, Validade.formatar(c.planoAte!!)),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            val pausados = c.pausadosLimite + c.pausadosPdv
            if (pausados > 0) {
                Text(stringResource(R.string.catalogo_itens_pausados, pausados), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            c.avisos(hoje).forEach { aviso -> Faixa(textoDoAviso(aviso)) }
            if (c.pausadosLimite == 0 && c.noLimite) Faixa(stringResource(R.string.catalogo_limite_atingido))
            Text(stringResource(R.string.catalogo_explicacao), style = MaterialTheme.typography.bodySmall)
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

/** Limite atingido ao criar ou reativar item: mensagem neutra, só "Entendi". */
@Composable
fun DialogoLimite(c: StatusCatalogo, aoFechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) },
        title = { Text(stringResource(R.string.catalogo_limite_titulo), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.catalogo_limite_texto, c.limite, c.planoEfetivoNome), textAlign = TextAlign.Center) },
        confirmButton = { TextButton(onClick = aoFechar) { Text(stringResource(R.string.erro_botao_ok)) } },
    )
}

/** Resumo depois de importar a planilha: quantos entraram e quantos ficaram de fora. */
@Composable
fun DialogoResultadoImportacao(r: ResultadoImportacao, aoFechar: () -> Unit) {
    val contexto = LocalContext.current
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
