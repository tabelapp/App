package br.com.tabelapp.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.tabelapp.AppContainer
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import kotlinx.coroutines.launch

/** Primeiro acesso pelo Google: falta o nome e se a pessoa é CPF (pesquisa) ou CNPJ (comércio). */
@Composable
fun TelaCompletarCadastro(container: AppContainer, usuario: Usuario) {
    val escopo = rememberCoroutineScope()
    var nome by rememberSaveable { mutableStateOf(usuario.nome.orEmpty()) }
    var tipo by rememberSaveable { mutableStateOf(TipoConta.CPF) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Falta pouco!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Conte como você vai usar o Tabelapp. Dá para mudar depois.")

            OutlinedTextField(
                value = nome, onValueChange = { nome = it },
                label = { Text("Seu nome") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SeletorTipoConta(tipo, aoEscolher = { tipo = it })
            Text(
                if (tipo == TipoConta.CPF) {
                    "Pesquise preços, monte sua lista de compras e compartilhe preços de notas fiscais e encartes."
                } else {
                    "Cadastre suas lojas, mantenha sua tabela de preços e crie promoções."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    if (nome.isBlank()) {
                        erro = "Informe seu nome."
                    } else {
                        enviando = true
                        escopo.launch {
                            try {
                                container.auth.concluirCadastro(nome, tipo)
                            } catch (e: ErroAmigavel) {
                                erro = e.message
                            } finally {
                                enviando = false
                            }
                        }
                    }
                },
                enabled = !enviando,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continuar") }

            TextButton(onClick = { escopo.launch { runCatching { container.auth.sair() } } }) {
                Text("Usar outra conta")
            }
        }
    }
}
