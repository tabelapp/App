package br.com.tabelapp.ui.principal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.tabelapp.R
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.comum.DocumentoLegal
import br.com.tabelapp.ui.comum.TelaTextoLegal
import kotlinx.coroutines.launch

/**
 * "Minha conta" (exigência da Play Store): editar o nome, ler os Termos e a
 * Política e excluir a conta pelo app, com confirmação.
 */
@Composable
fun MinhaConta(usuario: Usuario, auth: AuthRepositorio, aoFechar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    var nome by rememberSaveable { mutableStateOf(usuario.nome.orEmpty()) }
    var salvando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var documento by remember { mutableStateOf<DocumentoLegal?>(null) }
    var confirmandoExclusao by remember { mutableStateOf(false) }
    val nomeSalvo = stringResource(R.string.conta_nome_salvo)

    documento?.let { TelaTextoLegal(it, aoFechar = { documento = null }) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.conta_titulo), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.legal_fechar)) }
                }
                usuario.email?.let { Text(stringResource(R.string.conta_email, it)) }
                Text(
                    stringResource(
                        when (usuario.tipo) {
                            TipoConta.CPF -> R.string.conta_tipo_pessoa
                            TipoConta.CNPJ -> R.string.conta_tipo_estabelecimento
                            TipoConta.ADMIN -> R.string.conta_tipo_admin
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nome, onValueChange = { nome = it.take(80); aviso = null },
                    label = { Text(stringResource(R.string.conta_nome)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        salvando = true
                        escopo.launch {
                            aviso = try {
                                auth.atualizarNome(nome)
                                nomeSalvo
                            } catch (e: ErroAmigavel) {
                                e.message
                            } finally {
                                salvando = false
                            }
                        }
                    },
                    enabled = !salvando && nome.trim() != usuario.nome.orEmpty().trim(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (salvando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.conta_salvar_nome))
                }
                aviso?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.conta_documentos), fontWeight = FontWeight.Bold)
                TextButton(onClick = { documento = DocumentoLegal.TERMOS }) { Text(stringResource(R.string.legal_termos)) }
                TextButton(onClick = { documento = DocumentoLegal.PRIVACIDADE }) { Text(stringResource(R.string.legal_privacidade)) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.conta_excluir_explicacao), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirmandoExclusao = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conta_excluir))
                }
            }
        }
    }

    if (confirmandoExclusao) {
        DialogoExcluirConta(
            estabelecimento = usuario.tipo == TipoConta.CNPJ,
            aoExcluir = { auth.excluirConta() },
            aoFechar = { confirmandoExclusao = false },
        )
    }
}

/** Confirmação calma e clara: o que sai, o que fica, e digitar EXCLUIR. */
@Composable
private fun DialogoExcluirConta(estabelecimento: Boolean, aoExcluir: suspend () -> Unit, aoFechar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    var digitado by remember { mutableStateOf("") }
    var excluindo by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val palavra = stringResource(R.string.conta_excluir_palavra)
    AlertDialog(
        onDismissRequest = { if (!excluindo) aoFechar() },
        title = { Text(stringResource(R.string.conta_excluir_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conta_excluir_texto))
                if (estabelecimento) Text(stringResource(R.string.conta_excluir_texto_pdv), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = digitado, onValueChange = { digitado = it.take(20) },
                    label = { Text(stringResource(R.string.conta_excluir_digite)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                erro?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !excluindo && digitado.trim().equals(palavra, ignoreCase = true),
                onClick = {
                    excluindo = true
                    erro = null
                    escopo.launch {
                        try {
                            aoExcluir() // a sessão termina e o app volta para a tela de entrada
                        } catch (e: ErroAmigavel) {
                            erro = e.message
                            excluindo = false
                        }
                    }
                },
            ) {
                if (excluindo) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.conta_excluir_confirmar))
            }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !excluindo) { Text(stringResource(R.string.conta_excluir_cancelar)) } },
    )
}
