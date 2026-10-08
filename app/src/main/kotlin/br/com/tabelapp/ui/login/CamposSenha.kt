package br.com.tabelapp.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import kotlinx.coroutines.launch

/** Campo de senha com o "olho" para mostrar/esconder o que foi digitado. */
@Composable
fun CampoSenha(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String = "Senha",
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var visivel by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = valor, onValueChange = aoMudar,
        label = { Text(rotulo) }, singleLine = true,
        visualTransformation = if (visivel) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { visivel = !visivel }) {
                Icon(
                    if (visivel) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visivel) "Esconder senha" else "Mostrar senha",
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * "Esqueci a senha": 1) manda um código de 6 números para o e-mail cadastrado;
 * 2) a pessoa digita o código e a nova senha — e já entra no app.
 */
@Composable
fun DialogoEsqueciSenha(auth: AuthRepositorio, emailInicial: String, aoFechar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(emailInicial.trim()) }
    var codigoEnviado by rememberSaveable { mutableStateOf(false) }
    var codigo by rememberSaveable { mutableStateOf("") }
    var novaSenha by rememberSaveable { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    fun executar(acao: suspend () -> Unit) {
        erro = null
        enviando = true
        escopo.launch {
            try {
                acao()
            } catch (e: ErroAmigavel) {
                erro = e.message
            } finally {
                enviando = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!enviando) aoFechar() },
        title = { Text("Esqueci a senha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!codigoEnviado) {
                    Text("Informe o e-mail da sua conta. Vamos mandar um código de 6 números para ele.")
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("E-mail") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Enviamos um código para $email. Veja também a caixa de spam.")
                    OutlinedTextField(
                        value = codigo, onValueChange = { codigo = it.filter(Char::isDigit).take(6) },
                        label = { Text("Código de 6 números") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CampoSenha(novaSenha, { novaSenha = it }, rotulo = "Nova senha")
                    TextButton(onClick = { executar { auth.enviarCodigoSenha(email) } }, enabled = !enviando) {
                        Text("Mandar outro código")
                    }
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !enviando,
                onClick = {
                    if (!codigoEnviado) {
                        if (!email.contains('@')) {
                            erro = "Informe um e-mail válido."
                        } else {
                            executar {
                                auth.enviarCodigoSenha(email)
                                codigoEnviado = true
                            }
                        }
                    } else when {
                        codigo.length != 6 -> erro = "O código tem 6 números."
                        novaSenha.length < 6 -> erro = "A nova senha precisa ter pelo menos 6 caracteres."
                        else -> executar {
                            auth.redefinirSenha(email, codigo, novaSenha)
                            aoFechar()
                        }
                    }
                },
            ) {
                if (enviando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (!codigoEnviado) "Enviar código" else "Salvar nova senha")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !enviando) { Text("Cancelar") } },
    )
}
