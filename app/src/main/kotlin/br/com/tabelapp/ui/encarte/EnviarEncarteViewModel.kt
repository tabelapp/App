package br.com.tabelapp.ui.encarte

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.ItemEncarte
import br.com.tabelapp.core.LeitorEncarte
import br.com.tabelapp.core.LinhaOcr
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.RascunhoEncarte
import br.com.tabelapp.dados.EncarteRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.Imagens
import br.com.tabelapp.dados.LeitorTexto
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

/** Foto já reduzida para envio + miniatura + o texto lido nela. */
class FotoEncarte(val id: Int, val jpeg: ByteArray, val miniatura: ImageBitmap, val linhas: List<LinhaOcr>)

enum class EtapaEncarte { FOTOS, CONFIRMACAO, PUBLICADO }

data class EstadoEncarte(
    val etapa: EtapaEncarte = EtapaEncarte.FOTOS,
    val fotos: List<FotoEncarte> = emptyList(),
    /** Abrindo e lendo o texto das fotos recém-adicionadas. */
    val lendoFotos: Boolean = false,
    /** Produtos e preços lidos das fotos; o usuário só pode desmarcar. */
    val itens: List<ItemEncarte> = emptyList(),
    val desmarcados: Set<Int> = emptySet(),
    /** Texto digitado para achar o estabelecimento (ou nome livre, se não for cadastrado). */
    val busca: String = "",
    val sugestoes: List<LojaResumo> = emptyList(),
    val lojaEscolhida: LojaResumo? = null,
    val pdvEndereco: String = "",
    val validade: LocalDate? = null,
    /** A validade foi encontrada no próprio encarte (e não escolhida pelo usuário). */
    val validadeLida: Boolean = false,
    val erros: List<String> = emptyList(),
    val enviando: Boolean = false,
    val publicados: Int = 0,
    val meusEncartes: List<EncarteEnviado> = emptyList(),
) {
    val selecionados: List<ItemEncarte> get() = itens.filterIndexed { i, _ -> i !in desmarcados }
}

/**
 * Aba "Encarte" (briefing, seção 4 — fluxo definido pelo fundador):
 * o usuário fotografa o encarte, o app lê as fotos (OCR no celular) e mostra os
 * produtos e preços encontrados; com a confirmação do usuário, os preços vão
 * direto para a busca. Não passa pelo Admin, e o usuário não digita produto nem
 * preço — só desmarca o que foi lido errado.
 */
class EnviarEncarteViewModel(
    private val repositorio: EncarteRepositorio,
    private val imagens: Imagens,
    private val leitorTexto: LeitorTexto,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoEncarte())
    val estado: StateFlow<EstadoEncarte> = _estado.asStateFlow()

    private var buscaJob: Job? = null
    private var proximaFoto = 0

    init {
        atualizarMeusEncartes()
    }

    /** Reduz cada foto para envio e já lê o texto dela. */
    fun adicionarFotos(uris: List<Uri>) {
        val vagas = RascunhoEncarte.MAX_FOTOS - _estado.value.fotos.size
        if (uris.isEmpty() || vagas <= 0) return
        _estado.update { it.copy(lendoFotos = true, erros = emptyList()) }
        viewModelScope.launch {
            val falhas = mutableListOf<String>()
            val novas = uris.take(vagas).mapNotNull { uri ->
                val id = proximaFoto++
                val jpeg = withContext(Dispatchers.Default) { imagens.jpegReduzido(uri) }
                val mini = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                if (jpeg == null || mini == null) {
                    falhas += "Não consegui abrir alguma das fotos."
                    return@mapNotNull null
                }
                val linhas = try {
                    leitorTexto.linhas(uri, id)
                } catch (e: ErroAmigavel) {
                    falhas += e.message.orEmpty()
                    emptyList()
                }
                FotoEncarte(id, jpeg, mini.asImageBitmap(), linhas)
            }
            _estado.update { it.copy(fotos = it.fotos + novas, lendoFotos = false, erros = falhas.distinct()) }
        }
    }

    fun removerFoto(indice: Int) = _estado.update { e -> e.copy(fotos = e.fotos.filterIndexed { i, _ -> i != indice }) }

    /** Junta o texto de todas as fotos e monta a lista de produtos e preços para o usuário conferir. */
    fun lerEncarte(hoje: LocalDate = LocalDate.now()) {
        val e = _estado.value
        if (e.fotos.isEmpty() || e.lendoFotos) return
        val lido = LeitorEncarte.ler(e.fotos.flatMap { it.linhas }, hoje)
        _estado.update {
            it.copy(
                etapa = EtapaEncarte.CONFIRMACAO,
                itens = lido.itens,
                desmarcados = emptySet(),
                // Se o usuário já tinha escolhido uma data, ela vale; senão, a lida do encarte.
                validade = if (it.validade != null && !it.validadeLida) it.validade else lido.validade,
                validadeLida = if (it.validade != null && !it.validadeLida) false else lido.validade != null,
                erros = emptyList(),
            )
        }
    }

    fun voltarAsFotos() = _estado.update { it.copy(etapa = EtapaEncarte.FOTOS, erros = emptyList()) }

    fun alternarItem(indice: Int) = _estado.update {
        it.copy(desmarcados = if (indice in it.desmarcados) it.desmarcados - indice else it.desmarcados + indice)
    }

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
    fun alterarValidade(data: LocalDate) = _estado.update { it.copy(validade = data, validadeLida = false) }

    fun publicar() {
        val e = _estado.value
        val erros = RascunhoEncarte(
            quantidadeFotos = e.fotos.size,
            lojaId = e.lojaEscolhida?.lojaId,
            pdvNome = e.busca,
            validade = e.validade,
            itens = e.selecionados,
        ).erros()
        if (erros.isNotEmpty() || e.validade == null) {
            _estado.update { it.copy(erros = erros) }
            return
        }
        _estado.update { it.copy(enviando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                val n = repositorio.publicar(
                    fotos = e.fotos.map { it.jpeg },
                    lojaId = e.lojaEscolhida?.lojaId,
                    pdvNome = if (e.lojaEscolhida == null) e.busca else "",
                    pdvEndereco = if (e.lojaEscolhida == null) e.pdvEndereco else "",
                    validade = e.validade,
                    itens = e.selecionados,
                )
                _estado.update { EstadoEncarte(etapa = EtapaEncarte.PUBLICADO, publicados = n, meusEncartes = it.meusEncartes) }
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
