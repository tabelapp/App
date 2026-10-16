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

    /**
     * Versão dos Termos aceita no cadastro, ainda não gravada no servidor (a conta
     * acabou de nascer). É gravada assim que a pessoa entra.
     */
    var aceiteTermosPendente: String?
        get() = prefs.getString(CHAVE_ACEITE, null)
        set(valor) = prefs.edit { if (valor == null) remove(CHAVE_ACEITE) else putString(CHAVE_ACEITE, valor) }

    private companion object {
        const val CHAVE_DISCLAIMER = "disclaimer_aceito_v1"
        const val CHAVE_ACEITE = "aceite_termos_pendente"
    }
}
