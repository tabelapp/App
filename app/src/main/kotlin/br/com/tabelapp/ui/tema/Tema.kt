package br.com.tabelapp.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cores da logomarca: amarelo de fundo, vermelho do "Tabelapp" e do "$", preto da lupa.
val AmareloTabelapp = Color(0xFFFFE000)
val VermelhoTabelapp = Color(0xFFC00000)
val PretoLupa = Color(0xFF1A1A1A)

private val claro = lightColorScheme(
    primary = VermelhoTabelapp,
    onPrimary = Color.White,
    // "Mais barato" na busca: cartão amarelo claro com borda vermelha.
    primaryContainer = Color(0xFFFFF4B3),
    onPrimaryContainer = Color(0xFF5C0000),
    secondary = AmareloTabelapp,
    onSecondary = PretoLupa,
    secondaryContainer = Color(0xFFFFF4B3),
    onSecondaryContainer = Color(0xFF3B2F00),
    tertiary = PretoLupa,
    onTertiary = Color.White,
)

private val escuro = darkColorScheme(
    primary = Color(0xFFFFB4A8),
    onPrimary = Color(0xFF690000),
    primaryContainer = Color(0xFF4A3F00),
    onPrimaryContainer = Color(0xFFFFEE80),
    secondary = AmareloTabelapp,
    onSecondary = PretoLupa,
    secondaryContainer = Color(0xFF4A3F00),
    onSecondaryContainer = Color(0xFFFFEE80),
    tertiary = Color(0xFFE6E1D9),
    onTertiary = PretoLupa,
)

@Composable
fun TabelappTema(escuroAtivo: Boolean = isSystemInDarkTheme(), conteudo: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (escuroAtivo) escuro else claro, content = conteudo)
}

/** Barra de topo como a logomarca: fundo amarelo, título vermelho, ícones pretos (claro e escuro). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun coresBarraTopo(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = AmareloTabelapp,
    titleContentColor = VermelhoTabelapp,
    actionIconContentColor = PretoLupa,
    navigationIconContentColor = PretoLupa,
)
