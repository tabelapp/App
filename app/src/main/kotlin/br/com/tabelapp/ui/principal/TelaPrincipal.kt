package br.com.tabelapp.ui.principal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
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
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.admin.TelaAdmin
import br.com.tabelapp.ui.busca.TelaBusca
import br.com.tabelapp.ui.nf.TelaEnviarNf
import br.com.tabelapp.ui.pdv.TelaMeuNegocio

private enum class Aba(val rotulo: String, val icone: ImageVector) {
    BUSCAR("Buscar", Icons.Default.Search),
    ENVIAR_NF("Enviar NF", Icons.Default.ReceiptLong),
    MEU_NEGOCIO("Meu negócio", Icons.Default.Storefront),
    ADMIN("Admin", Icons.Default.AdminPanelSettings),
}

/** Abas que cada tipo de conta vê. */
private fun abasDe(tipo: TipoConta): List<Aba> = when (tipo) {
    TipoConta.CPF -> listOf(Aba.BUSCAR, Aba.ENVIAR_NF)
    TipoConta.CNPJ -> listOf(Aba.BUSCAR, Aba.ENVIAR_NF, Aba.MEU_NEGOCIO)
    TipoConta.ADMIN -> listOf(Aba.BUSCAR, Aba.ENVIAR_NF, Aba.ADMIN)
}

/** Abas do usuário logado. As próximas (lista de compras...) entram aqui. */
@Composable
fun TelaPrincipal(container: AppContainer, usuario: Usuario) {
    val abas = abasDe(usuario.tipo)
    var escolhida by rememberSaveable { mutableStateOf(Aba.BUSCAR) }
    val aba = if (escolhida in abas) escolhida else Aba.BUSCAR

    Scaffold(
        bottomBar = {
            NavigationBar {
                abas.forEach { a ->
                    NavigationBarItem(
                        selected = aba == a,
                        onClick = { escolhida = a },
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
                Aba.ENVIAR_NF -> TelaEnviarNf(container, usuario, aoVerNaBusca = { escolhida = Aba.BUSCAR })
                Aba.MEU_NEGOCIO -> TelaMeuNegocio(container, usuario)
                Aba.ADMIN -> TelaAdmin(container, usuario)
            }
        }
    }
}
