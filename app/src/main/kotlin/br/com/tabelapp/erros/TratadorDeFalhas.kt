package br.com.tabelapp.erros

import android.app.Application
import android.content.Intent
import android.os.Process
import br.com.tabelapp.MainActivity
import kotlin.system.exitProcess

/**
 * Falha não tratada em qualquer tela: em vez de "o app parou", registra o erro
 * no log interno e reabre o app mostrando a mensagem calma do fallback geral.
 * Se a falha se repetir logo em seguida (laço), deixa o Android tratar.
 */
object TratadorDeFalhas {
    const val EXTRA_FALHOU = "tabelapp.falhou"
    private const val INTERVALO_LACO_MS = 15_000L

    fun instalar(app: Application) {
        val padrao = Thread.getDefaultUncaughtExceptionHandler()
        val prefs = app.getSharedPreferences("falhas", Application.MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            Erros.registrar("falha não tratada", "thread=${thread.name}", erro)
            val agora = System.currentTimeMillis()
            val ultima = prefs.getLong("ultima", 0)
            if (agora - ultima < INTERVALO_LACO_MS) {
                padrao?.uncaughtException(thread, erro)
                return@setDefaultUncaughtExceptionHandler
            }
            prefs.edit().putLong("ultima", agora).commit()
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .putExtra(EXTRA_FALHOU, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
