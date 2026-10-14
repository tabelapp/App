package br.com.tabelapp

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.erros.TratadorDeFalhas
import br.com.tabelapp.ui.RaizApp
import br.com.tabelapp.ui.comum.DialogoErro
import br.com.tabelapp.ui.tema.CoresTabelapp
import br.com.tabelapp.ui.tema.TabelappTema

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tema escuro único: ícones claros na barra de status (sobre o azul do topo) e na de navegação.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(CoresTabelapp.BarraInferior.toArgb()),
        )
        val container = (application as TabelappApplication).container
        // Reaberto depois de uma falha inesperada: avisa com calma (o detalhe foi para o log interno).
        val voltouDeFalha = intent.getBooleanExtra(TratadorDeFalhas.EXTRA_FALHOU, false)
        setContent {
            TabelappTema {
                RaizApp(container)
                var avisar by rememberSaveable { mutableStateOf(voltouDeFalha) }
                if (avisar) DialogoErro(TipoErro.INESPERADO, aoFechar = { avisar = false })
            }
        }
    }
}
