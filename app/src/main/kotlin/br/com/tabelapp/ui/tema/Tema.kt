package br.com.tabelapp.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Identidade provisória: verde (economia) + amarelo (etiqueta de preço/destaque).
val VerdeTabelapp = Color(0xFF1B7F3B)
val VerdeEscuro = Color(0xFF0F5A28)
val AmareloEtiqueta = Color(0xFFF2B705)

private val claro = lightColorScheme(
    primary = VerdeTabelapp,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFD6),
    onPrimaryContainer = VerdeEscuro,
    secondary = Color(0xFF8A6A00),
    secondaryContainer = Color(0xFFFFE9A8),
    onSecondaryContainer = Color(0xFF3B2E00),
    tertiary = Color(0xFF3B6470),
)

private val escuro = darkColorScheme(
    primary = Color(0xFF8FD9A4),
    onPrimary = Color(0xFF00391A),
    primaryContainer = VerdeEscuro,
    onPrimaryContainer = Color(0xFFCDEFD6),
    secondary = AmareloEtiqueta,
    secondaryContainer = Color(0xFF5A4600),
    onSecondaryContainer = Color(0xFFFFE9A8),
    tertiary = Color(0xFFA3CDDB),
)

@Composable
fun TabelappTema(escuroAtivo: Boolean = isSystemInDarkTheme(), conteudo: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (escuroAtivo) escuro else claro, content = conteudo)
}
