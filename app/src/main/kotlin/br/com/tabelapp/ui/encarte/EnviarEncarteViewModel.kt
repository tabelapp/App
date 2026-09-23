package br.com.tabelapp.ui.encarte

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.RascunhoEncarte
import br.com.tabelapp.dados.EncarteRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.Imagens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Foto já reduzida para envio + miniatura para mostrar na tela. */
class FotoEncarte(val jpeg: ByteArray, val miniatura: ImageBitmap)

data class EstadoEncarte(
    val fotos: List<FotoEncarte> = emptyList(),
    val processandoFotos: Boolean = false,
    /** Texto digitado para achar o estabelecimento (ou nome livre, se não for cadastrado). */
    val busca: String = "",
    val sugestoes: List<LojaResumo> = emptyList(),
    val lojaEscolhida: LojaResumo? = null,
    val pdvEndereco: String = "",
    val validade: LocalDate? = null,
    val comentario: String = "",
    val erros: List<String> = emptyList(),
    val enviando: Boolean = false,
    val enviado: Boolean = false,
    val meusEncartes: List<EncarteEnviado> = emptyList(),
)

/**
 * Aba "Encarte" (briefing, seção 4): o usuário fotografa um encarte que viu,
 * diz de qual estabelecimento é e, se o encarte mostrar, até quando valem as
 * ofertas. O encarte vai para a fila do Admin — só vira preço depois de aprovado.
 */
class EnviarEncarteViewModel(
    private val repositorio: EncarteRepositorio,
    private val imagens: Imagens,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoEncarte())
    val estado: StateFlow<EstadoEncarte> = _estado.asStateFlow()

    private var buscaJob: Job? = null

    init {
        atualizarMeusEncartes()
    }

    fun adicionarFotos(uris: List<Uri>) {
        val vagas = RascunhoEncarte.MAX_FOTOS - _estado.value.fotos.size
        if (uris.isEmpty() || vagas <= 0) return
        _estado.update { it.copy(processandoFotos = true, erros = emptyList()) }
        viewModelScope.launch {
            val novas = withContext(Dispatchers.Default) {
                uris.take(vagas).mapNotNull { uri ->
                    val jpeg = imagens.jpegReduzido(uri) ?: return@mapNotNull null
                    val mini = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: return@mapNotNull null
                    FotoEncarte(jpeg, mini)
                }
            }
            _estado.update {
                it.copy(
                    fotos = it.fotos + novas,
                    processandoFotos = false,
                    erros = if (novas.size < uris.take(vagas).size) listOf("Não consegui abrir alguma das fotos.") else emptyList(),
                )
            }
        }
    }

    fun removerFoto(indice: Int) = _estado.update { e -> e.copy(fotos = e.fotos.filterIndexed { i, _ -> i != indice }) }

    /** Digitar o nome busca estabelecimentos cadastrados; o texto vale como nome livre se nenhum for escolhido. */
    fun alterarBusca(texto: String) {
        _estado.update { it.copy(busca = texto, lojaEscolhida = null) }
        buscaJob?.cancel()
        buscaJob = viewModelScope.launch {
            delay(350)
            val lojas = try {
                repositorio.buscarLojas(texto)
            } catch (e: ErroAmigavel) {
                emptyList()
            }
            _estado.update { it.copy(sugestoes = lojas) }
        }
    }

    fun escolherLoja(loja: LojaResumo) =
        _estado.update { it.copy(lojaEscolhida = loja, busca = loja.titulo, sugestoes = emptyList()) }

    fun alterarEndereco(texto: String) = _estado.update { it.copy(pdvEndereco = texto) }
    fun alterarValidade(data: LocalDate?) = _estado.update { it.copy(validade = data) }
    fun alterarComentario(texto: String) = _estado.update { it.copy(comentario = texto.take(500)) }

    fun enviar() {
        val e = _estado.value
        val erros = RascunhoEncarte(
            quantidadeFotos = e.fotos.size,
            lojaId = e.lojaEscolhida?.lojaId,
            pdvNome = e.busca,
            validade = e.validade,
        ).erros()
        if (erros.isNotEmpty()) {
            _estado.update { it.copy(erros = erros) }
            return
        }
        _estado.update { it.copy(enviando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                repositorio.enviar(
                    fotos = e.fotos.map { it.jpeg },
                    lojaId = e.lojaEscolhida?.lojaId,
                    pdvNome = if (e.lojaEscolhida == null) e.busca else "",
                    pdvEndereco = if (e.lojaEscolhida == null) e.pdvEndereco else "",
                    validade = e.validade,
                    comentario = e.comentario,
                )
                _estado.update { EstadoEncarte(enviado = true, meusEncartes = it.meusEncartes) }
                atualizarMeusEncartes()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(enviando = false, erros = listOfNotNull(ex.message)) }
            }
        }
    }

    fun novoEncarte() = _estado.update { EstadoEncarte(meusEncartes = it.meusEncartes) }

    fun atualizarMeusEncartes() {
        viewModelScope.launch {
            val lista = try {
                repositorio.meusEncartes()
            } catch (e: ErroAmigavel) {
                return@launch
            }
            _estado.update { it.copy(meusEncartes = lista) }
        }
    }
}
