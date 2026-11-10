package br.com.tabelapp.dados

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import br.com.tabelapp.core.EstadoAtualizacao
import br.com.tabelapp.erros.Erros
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Versão nova pela Play Store (atualização dentro do app, modo FLEXÍVEL): baixa em segundo plano
 * enquanto a pessoa usa o app; ao terminar, ela escolhe quando reiniciar. Toda falha (sem Play
 * Store, sem internet, app instalado fora da Play, como o APK do CI) é silenciosa: fica em
 * NENHUMA e o app segue normal. No modo demonstração ([ativo] = false) a API nem é chamada.
 */
class AtualizacaoApp(contexto: Context, private val preferencias: Preferencias, ativo: Boolean) {
    private val gerente: AppUpdateManager? =
        if (ativo) runCatching { AppUpdateManagerFactory.create(contexto.applicationContext) }.getOrNull() else null
    private var info: AppUpdateInfo? = null
    private var ouvindo = false

    private val _estado = MutableStateFlow(EstadoAtualizacao.NENHUMA)
    val estado: StateFlow<EstadoAtualizacao> = _estado.asStateFlow()

    private val _dispensadoEm = MutableStateFlow(preferencias.atualizacaoDispensadaEm)
    val dispensadoEm: StateFlow<LocalDate?> = _dispensadoEm.asStateFlow()

    private val ouvinte = InstallStateUpdatedListener { s ->
        _estado.value = when (s.installStatus()) {
            InstallStatus.DOWNLOADED -> EstadoAtualizacao.BAIXADA
            InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING -> EstadoAtualizacao.BAIXANDO
            // Falhou ou foi cancelado: some sem barulho (volta a aparecer numa próxima abertura).
            else -> EstadoAtualizacao.NENHUMA
        }
    }

    /** Ao abrir o app: pergunta à Play se há versão nova. Nunca lança erro. */
    suspend fun verificar() {
        val g = gerente ?: return
        try {
            val i = g.requestAppUpdateInfo()
            info = i
            _estado.value = when {
                i.installStatus() == InstallStatus.DOWNLOADED -> EstadoAtualizacao.BAIXADA
                i.installStatus() == InstallStatus.DOWNLOADING || i.installStatus() == InstallStatus.PENDING -> {
                    ouvir(g)
                    EstadoAtualizacao.BAIXANDO
                }
                i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                    EstadoAtualizacao.DISPONIVEL
                else -> EstadoAtualizacao.NENHUMA
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Erros.registrar("atualizacao", "verificação indisponível: ${e.javaClass.simpleName}")
            _estado.value = EstadoAtualizacao.NENHUMA
        }
    }

    /** "Atualizar": abre a confirmação da Play e baixa em segundo plano. */
    fun atualizar(lancador: ActivityResultLauncher<IntentSenderRequest>) {
        val g = gerente ?: return
        val i = info ?: return
        try {
            ouvir(g)
            g.startUpdateFlowForResult(i, lancador, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
            _estado.value = EstadoAtualizacao.BAIXANDO
        } catch (e: Exception) {
            Erros.registrar("atualizacao", "não abriu a atualização: ${e.javaClass.simpleName}")
            _estado.value = EstadoAtualizacao.NENHUMA
        }
    }

    /** Resposta da tela da Play: recusou = mesmo que "Agora não". */
    fun resultadoDaPlay(aceitou: Boolean) {
        if (!aceitou) dispensar()
    }

    /** "Agora não": esconde e não repete hoje. */
    fun dispensar() {
        val hoje = hoje()
        preferencias.atualizacaoDispensadaEm = hoje
        _dispensadoEm.value = hoje
        if (_estado.value == EstadoAtualizacao.BAIXANDO) _estado.value = EstadoAtualizacao.NENHUMA
    }

    /** "Reiniciar para concluir": a Play instala e reabre o app. */
    fun concluir() {
        runCatching { gerente?.completeUpdate() }
    }

    fun hoje(): LocalDate = LocalDate.now(ZoneId.of("America/Sao_Paulo"))

    private fun ouvir(g: AppUpdateManager) {
        if (ouvindo) return
        runCatching { g.registerListener(ouvinte) }.onSuccess { ouvindo = true }
    }
}
