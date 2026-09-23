package br.com.tabelapp.ui.principal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import br.com.tabelapp.AppContainer
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.busca.TelaBusca
import br.com.tabelapp.ui.nf.TelaEnviarNf

private enum class Aba(val rotulo: String, val icone: ImageVector) {
    BUSCAR("Buscar", Icons.Default.Search),
    ENVIAR_NF("Enviar NF", Icons.Default.ReceiptLong),
}

/** Abas do usuário logado. As próximas (lista de compras...) entram aqui. */
@Composable
fun TelaPrincipal(container: AppContainer, usuario: Usuario) {
    var aba by rememberSaveable { mutableStateOf(Aba.BUSCAR) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Aba.entries.forEach { a ->
                    NavigationBarItem(
                        selected = aba == a,
                        onClick = { aba = a },
                        icon = { Icon(a.icone, contentDescription = null) },
                        label = { Text(a.rotulo) },
                    )
                }
            }
        },
    ) { margens ->
        // As telas das abas têm a própria barra de topo; aqui só reservamos o espaço da barra de baixo.
        Box(Modifier.fillMaxSize().padding(bottom = margens.calculateBottomPadding())) {
            when (aba) {
                Aba.BUSCAR -> TelaBusca(container, usuario)
                Aba.ENVIAR_NF -> TelaEnviarNf(container, usuario, aoVerNaBusca = { aba = Aba.BUSCAR })
            }
        }
    }
}
