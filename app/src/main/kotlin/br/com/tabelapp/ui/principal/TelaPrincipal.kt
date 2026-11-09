package br.com.tabelapp.ui.principal

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.tabelapp.AppContainer
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.ui.admin.TelaAdmin
import br.com.tabelapp.ui.busca.TelaBusca
import br.com.tabelapp.ui.lista.TelaLista
import br.com.tabelapp.ui.nf.TelaEnviarNf
import br.com.tabelapp.ui.pdv.TelaMeuNegocio
import br.com.tabelapp.ui.tema.CoresTabelapp

private enum class Aba(val rotulo: String, val icone: ImageVector) {
    BUSCAR("Buscar", Icons.Default.Search),
    LISTA("Lista", Icons.Default.ShoppingCart),
    ENVIAR_NF("Ler nota", Icons.Default.QrCode2),
    MEU_NEGOCIO("Meu negócio", Icons.Default.Storefront),
    ADMIN("Admin", Icons.Default.AdminPanelSettings),
}

/** Abas que cada tipo de conta vê. */
private fun abasDe(tipo: TipoConta): List<Aba> = when (tipo) {
    TipoConta.CPF -> listOf(Aba.BUSCAR, Aba.LISTA, Aba.ENVIAR_NF)
    TipoConta.CNPJ -> listOf(Aba.BUSCAR, Aba.LISTA, Aba.ENVIAR_NF, Aba.MEU_NEGOCIO)
    TipoConta.ADMIN -> listOf(Aba.BUSCAR, Aba.LISTA, Aba.ENVIAR_NF, Aba.ADMIN)
}

/** Abas do usuário logado. */
/** Altura da barra de abas (sem a área dos botões do sistema). */
private val ALTURA_BARRA_ABAS = 64.dp

@Composable
fun TelaPrincipal(container: AppContainer, usuario: Usuario, abaSalva: MutableState<String>) {
    val abas = abasDe(usuario.tipo)
    // A aba escolhida fica guardada fora desta tela (em RaizApp): se a sessão for
    // recarregada no meio de uma operação, o usuário continua na mesma aba.
    var escolhidaNome by abaSalva
    val escolhida = Aba.entries.firstOrNull { it.name == escolhidaNome } ?: Aba.BUSCAR
    val aba = if (escolhida in abas) escolhida else Aba.BUSCAR
    // Cada toque na aba "Ler nota" gera um pedido novo de câmera. Não é salvo: depois de girar a tela
    // (ou recriar a Activity) volta a 0 e a câmera não abre sozinha.
    var pedidoCamera by remember { mutableLongStateOf(0L) }

    Scaffold(
        bottomBar = {
            // Barra de abas com 64 dp (mais a área dos botões do sistema, quando houver).
            val sistema = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            NavigationBar(Modifier.height(ALTURA_BARRA_ABAS + sistema), containerColor = CoresTabelapp.BarraInferior) {
                abas.forEach { a ->
                    if (a == Aba.ENVIAR_NF) {
                        AbaLerNota(selecionada = aba == a) {
                            escolhidaNome = a.name
                            pedidoCamera = SystemClock.elapsedRealtimeNanos()
                        }
                        return@forEach
                    }
                    NavigationBarItem(
                        selected = aba == a,
                        onClick = { escolhidaNome = a.name },
                        icon = { Icon(a.icone, contentDescription = null) },
                        label = { Text(a.rotulo) },
                        // Aba ativa: ícone e texto no amarelo da marca, "pill" em amarelo-escuro.
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CoresTabelapp.Destaque,
                            selectedTextColor = CoresTabelapp.Destaque,
                            indicatorColor = CoresTabelapp.DestaqueFundo,
                            unselectedIconColor = CoresTabelapp.IconeNeutro,
                            unselectedTextColor = CoresTabelapp.IconeNeutro,
                        ),
                    )
                }
            }
        },
    ) { margens ->
        // As telas das abas têm a própria barra de topo; aqui só reservamos o espaço da barra de baixo.
        Box(Modifier.fillMaxSize().padding(bottom = margens.calculateBottomPadding())) {
            when (aba) {
                Aba.BUSCAR -> TelaBusca(container, usuario, aoEnviarPrecos = { escolhidaNome = Aba.ENVIAR_NF.name })
                Aba.ENVIAR_NF -> TelaEnviarNf(
                    container, usuario, aoVerNaBusca = { escolhidaNome = Aba.BUSCAR.name }, pedidoCamera = pedidoCamera,
                )
                Aba.LISTA -> TelaLista(container, usuario)
                Aba.MEU_NEGOCIO -> TelaMeuNegocio(container, usuario)
                Aba.ADMIN -> TelaAdmin(container, usuario)
            }
        }
    }
}

/**
 * Aba de destaque "Ler nota": ícone de QR Code escuro dentro de uma pílula amarela da marca,
 * sempre amarela (selecionada ou não) e um pouco maior que os outros ícones. Área de toque da
 * célula inteira (bem mais de 48 dp); o leitor de tela diz "Ler nota fiscal".
 */
@Composable
private fun RowScope.AbaLerNota(selecionada: Boolean, aoTocar: () -> Unit) {
    NavigationBarItem(
        selected = selecionada,
        onClick = aoTocar,
        icon = {
            Box(
                Modifier.size(width = 60.dp, height = 36.dp).background(CoresTabelapp.Destaque, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = "Ler nota fiscal", tint = CoresTabelapp.SobreDestaque,
                    modifier = Modifier.size(28.dp))
            }
        },
        label = {
            Text(Aba.ENVIAR_NF.rotulo, fontWeight = if (selecionada) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.clearAndSetSemantics {})
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = CoresTabelapp.SobreDestaque,
            unselectedIconColor = CoresTabelapp.SobreDestaque,
            selectedTextColor = CoresTabelapp.Destaque,
            unselectedTextColor = CoresTabelapp.Destaque,
            // A pílula amarela já é o destaque: sem o indicador padrão por trás.
            indicatorColor = Color.Transparent,
        ),
    )
}

