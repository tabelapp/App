package br.com.tabelapp.ui.tema

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Paleta do Tabelapp (tema escuro único, pedido do fundador em 14/10/2026).
 * TODAS as cores do app saem daqui: para ajustar, mude só este objeto.
 * Contraste conferido (WCAG): textos e preços com pelo menos 4,5:1 contra o fundo onde aparecem.
 */
object CoresTabelapp {
    /** Barra superior (cabeçalho) e barra de status. */
    val Topo = Color(0xFF283847)
    /** Fundo das telas. */
    val Fundo = Color(0xFF16171C)
    /** Superfície dos cards de preço (e o tom alternado, um pouco mais claro). */
    val Cartao = Color(0xFF26282B)
    val CartaoAlternado = Color(0xFF2C2F33)
    /** Barra de abas de baixo. */
    val BarraInferior = Color(0xFF232428)
    /** Diálogos, folhas de baixo e cards genéricos. */
    val Superficie = Color(0xFF2A2C30)
    val SuperficieAlta = Color(0xFF2E3134)
    /** Chips e etiquetas ("NF", "Preço oficial"...). */
    val Chip = Color(0xFF3A3C3F)
    val TextoPrincipal = Color(0xFFECEDEF)
    val TextoSecundario = Color(0xFFA9ACB1)
    /** Contornos neutros (campo de busca, botões e chips contornados): 1 dp. */
    val Contorno = Color(0xFFC9CCD1)
    /** Divisórias e contornos discretos. */
    val ContornoSuave = Color(0xFF3F4246)
    /** Destaque/seleção: aba ativa, links, ícones selecionados, botões principais. */
    val Destaque = Color(0xFFF08A45)
    /** Fundo do "pill" selecionado (aba ativa) e de containers de destaque. */
    val DestaqueFundo = Color(0xFF522E1B)
    /** Texto sobre o laranja (botões cheios). */
    val SobreDestaque = Color(0xFF1A0E06)
    val TextoSobreDestaqueFundo = Color(0xFFFFDCC6)
    val IconeNeutro = Color(0xFFB8BBC0)
    /** Preços: sempre brancos e em negrito. */
    val Preco = Color(0xFFFFFFFF)
    /** Amarelo da marca: SÓ nos contornos dos banners, do card mais barato e no título do cabeçalho. */
    val AmareloMarca = Color(0xFFFFE000)
    val WhatsApp = Color(0xFF1FA855)
    val Erro = Color(0xFFFFB4AB)
    val SobreErro = Color(0xFF690005)
    val ErroFundo = Color(0xFF93000A)
    val SobreErroFundo = Color(0xFFFFDAD6)
    /** Faixa "Patrocinado" sobre a arte do banner. */
    val FaixaSobreImagem = Color(0x8C000000)
    val TextoSobreImagem = Color(0xFFFFFFFF)
}

/** Contorno amarelo de 2 dp: banners pagos (e o "Anuncie aqui") e o card mais barato. Nenhum outro lugar. */
val ContornoMarca = BorderStroke(2.dp, CoresTabelapp.AmareloMarca)

/** Contorno neutro de 1 dp (campo de busca, chips e botões contornados). */
val ContornoNeutro = BorderStroke(1.dp, CoresTabelapp.Contorno)

private val esquema = with(CoresTabelapp) {
    darkColorScheme(
        primary = Destaque,
        onPrimary = SobreDestaque,
        primaryContainer = DestaqueFundo,
        onPrimaryContainer = TextoSobreDestaqueFundo,
        inversePrimary = Destaque,
        // NavigationBar: o "pill" da aba ativa usa secondaryContainer e o ícone onSecondaryContainer.
        secondary = IconeNeutro,
        onSecondary = Fundo,
        secondaryContainer = DestaqueFundo,
        onSecondaryContainer = Destaque,
        tertiary = IconeNeutro,
        onTertiary = Fundo,
        tertiaryContainer = Chip,
        onTertiaryContainer = TextoPrincipal,
        background = Fundo,
        onBackground = TextoPrincipal,
        surface = Fundo,
        onSurface = TextoPrincipal,
        surfaceVariant = Chip,
        onSurfaceVariant = TextoSecundario,
        surfaceTint = Color.Transparent,
        inverseSurface = TextoPrincipal,
        inverseOnSurface = Fundo,
        surfaceBright = SuperficieAlta,
        surfaceDim = Fundo,
        surfaceContainerLowest = Fundo,
        surfaceContainerLow = Cartao,
        surfaceContainer = BarraInferior,
        surfaceContainerHigh = Superficie,
        surfaceContainerHighest = SuperficieAlta,
        outline = Contorno,
        outlineVariant = ContornoSuave,
        error = Erro,
        onError = SobreErro,
        errorContainer = ErroFundo,
        onErrorContainer = SobreErroFundo,
        scrim = Color.Black,
    )
}

/**
 * Tema único e escuro: não segue o tema do sistema nem usa as cores dinâmicas do Android 12+
 * (o papel de parede do celular não muda a paleta).
 */
@Composable
fun TabelappTema(conteudo: @Composable () -> Unit) {
    MaterialTheme(colorScheme = esquema, content = conteudo)
}

/** Barra de topo: azul-acinzentado, título e ícones claros (o "Tabelapp" da busca vai em amarelo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun coresBarraTopo(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = CoresTabelapp.Topo,
    scrolledContainerColor = CoresTabelapp.Topo,
    titleContentColor = CoresTabelapp.TextoPrincipal,
    actionIconContentColor = CoresTabelapp.TextoPrincipal,
    navigationIconContentColor = CoresTabelapp.TextoPrincipal,
)
