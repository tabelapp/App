package br.com.tabelapp.dados

import android.content.Context
import androidx.core.content.edit

/** Preferências locais do aparelho (não vão para o servidor). */
class Preferencias(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("tabelapp", Context.MODE_PRIVATE)

    /** Pop-up de responsabilidade pelos preços: mostrado na primeira abertura da tela inicial. */
    var disclaimerAceito: Boolean
        get() = prefs.getBoolean(CHAVE_DISCLAIMER, false)
        set(valor) = prefs.edit { putBoolean(CHAVE_DISCLAIMER, valor) }

    private companion object {
        const val CHAVE_DISCLAIMER = "disclaimer_aceito_v1"
    }
}
