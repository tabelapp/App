package br.com.tabelapp

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.tabelapp.ui.RaizApp
import br.com.tabelapp.ui.tema.TabelappTema

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barra de status sobre o amarelo da marca: ícones sempre escuros.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        val container = (application as TabelappApplication).container
        setContent {
            TabelappTema {
                RaizApp(container)
            }
        }
    }
}
