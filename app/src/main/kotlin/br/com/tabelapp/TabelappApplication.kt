package br.com.tabelapp

import android.app.Application
import br.com.tabelapp.erros.Erros
import br.com.tabelapp.erros.TratadorDeFalhas

class TabelappApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Erros.iniciar(this)
        TratadorDeFalhas.instalar(this)
        container = AppContainer(this)
    }
}
