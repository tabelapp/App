package br.com.tabelapp.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.tabelapp.AppContainer
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.TipoConta
import kotlinx.coroutines.launch

@Composable
fun TelaLogin(container: AppContainer) {
    val escopo = rememberCoroutineScope()
    var criandoConta by rememberSaveable { mutableStateOf(false) }
    var nome by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var senha by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf(TipoConta.CPF) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val entrarComGoogle = container.auth.lembrarLoginGoogle(aoFalhar = { erro = it })

    fun enviar() {
        erro = null
        aviso = null
        if (email.isBlank() || senha.isBlank() || (criandoConta && nome.isBlank())) {
            erro = "Preencha todos os campos."
            return
        }
        if (criandoConta && senha.length < 6) {
            erro = "A senha precisa ter pelo menos 6 caracteres."
            return
        }
        enviando = true
        escopo.launch {
            try {
                if (criandoConta) {
                    val ativa = container.auth.cadastrarComEmail(nome, email, senha, tipo)
                    if (!ativa) aviso = "Enviamos um link de confirmação para $email. Confirme e depois entre."
                } else {
                    container.auth.entrarComEmail(email, senha)
                }
            } catch (e: ErroAmigavel) {
                erro = e.message
            } finally {
                enviando = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tabelapp",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Quem pesquisa economiza", style = MaterialTheme.typography.titleMedium)
            if (container.modoDemo) {
                Text(
                    "Modo demonstração: dados fictícios de Petrópolis. Qualquer e-mail e senha funcionam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))

            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !criandoConta,
                        onClick = { criandoConta = false; erro = null },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Entrar") }
                    SegmentedButton(
                        selected = criandoConta,
                        onClick = { criandoConta = true; erro = null },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Criar conta") }
                }

                if (criandoConta) {
                    OutlinedTextField(
                        value = nome, onValueChange = { nome = it },
                        label = { Text("Seu nome") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = senha, onValueChange = { senha = it },
                    label = { Text("Senha") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (criandoConta) {
                    Text("Como você vai usar o Tabelapp?", style = MaterialTheme.typography.labelLarge)
                    SeletorTipoConta(tipo, aoEscolher = { tipo = it })
                }

                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                aviso?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

                Button(onClick = ::enviar, enabled = !enviando, modifier = Modifier.fillMaxWidth()) {
                    if (enviando) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (criandoConta) "Criar conta" else "Entrar")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("  ou  ", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.weight(1f))
                }

                OutlinedButton(
                    onClick = { erro = null; entrarComGoogle?.invoke() },
                    enabled = entrarComGoogle != null && !enviando,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (entrarComGoogle != null) "Entrar com Google" else "Entrar com Google (não configurado)")
                }
                // Fluxo técnico do login por WhatsApp ainda não decidido (briefing, seção 10).
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Entrar com WhatsApp (em breve)")
                }
            }
        }
    }
}

@Composable
fun SeletorTipoConta(tipo: TipoConta, aoEscolher: (TipoConta) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = tipo == TipoConta.CPF,
            onClick = { aoEscolher(TipoConta.CPF) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Pesquisar preços") }
        SegmentedButton(
            selected = tipo == TipoConta.CNPJ,
            onClick = { aoEscolher(TipoConta.CNPJ) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("Tenho um comércio") }
    }
}
