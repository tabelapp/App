package br.com.tabelapp.ui.busca

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.Busca
import br.com.tabelapp.core.ConfigConfianca
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.Destaque
import br.com.tabelapp.core.Geo
import br.com.tabelapp.core.Ordenacao
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.SeloPreco
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.Localizacao
import br.com.tabelapp.dados.Preferencias
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoBusca(
    val texto: String = "",
    /** null = mostrando os últimos preços lançados (antes de qualquer busca). */
    val termoBuscado: String? = null,
    val carregando: Boolean = true,
    val erro: String? = null,
    /** Tipo do erro (sem internet, instabilidade...) para a tela calma; null = mensagem própria. */
    val erroTipo: TipoErro? = null,
    val resultados: List<Cotacao> = emptyList(),
    /** Destaque de cada card: selo "Menor preço lançado", "Parte de combo" ou "Confira na loja". */
    val destaques: Map<String, Destaque> = emptyMap(),
    /** null = ordem padrão (recentes na tela inicial, menor preço na busca). */
    val ordenacao: Ordenacao? = null,
    /** Posição real do usuário, se ele deu permissão. */
    val posicao: PontoGeo? = null,
    val mostrarDisclaimer: Boolean = false,
    /** Banners patrocinados para a busca atual (vazio = espaço "Anuncie aqui"). */
    val banners: List<Banner> = emptyList(),
    /** Autocompletar: produtos com preço que combinam com o que está sendo digitado. */
    val sugestoes: List<SugestaoProduto> = emptyList(),
    val buscandoSugestoes: Boolean = false,
) {
    /** Banners do topo e do lugar do 5º card: intercalados, para os dois espaços mostrarem anúncios diferentes. */
    val bannersTopo: List<Banner> get() = banners.filterIndexed { i, _ -> i % 2 == 0 }
    val bannersMeio: List<Banner> get() = banners.filterIndexed { i, _ -> i % 2 == 1 }
}

class BuscaViewModel(
    private val cotacoes: CotacoesRepositorio,
    private val preferencias: Preferencias,
    private val localizacao: Localizacao,
    private val listas: ListasRepositorio? = null,
) : ViewModel() {

    private val _estado = MutableStateFlow(
        EstadoBusca(
            mostrarDisclaimer = !preferencias.disclaimerAceito,
            posicao = localizacao.ultimaConhecida(),
        )
    )
    val estado: StateFlow<EstadoBusca> = _estado.asStateFlow()

    /** Resultado como veio do servidor, antes da ordenação escolhida na tela. */
    private var brutos: List<Cotacao> = emptyList()
    private var carga: Job? = null
    /** Parâmetros do selo (configuracao_app); padrão até carregar. */
    private var config = ConfigConfianca()
    private val bannersVistos = mutableSetOf<String>()
    private var sugestoesJob: Job? = null

    init {
        viewModelScope.launch {
            config = cotacoes.configConfianca()
            reaplicarOrdenacao()
        }
        carregar(null)
    }

    /** Autocompletar (o mesmo da lista de compras): sugere produtos com preço enquanto digita. */
    fun aoDigitar(texto: String) {
        _estado.update { it.copy(texto = texto) }
        sugestoesJob?.cancel()
        val repo = listas
        if (repo == null || texto.trim().length < 2) {
            _estado.update { it.copy(sugestoes = emptyList(), buscandoSugestoes = false) }
            return
        }
        sugestoesJob = viewModelScope.launch {
            delay(300)
            _estado.update { it.copy(buscandoSugestoes = true) }
            val sugestoes = try {
                repo.sugerir(texto.trim())
            } catch (e: ErroAmigavel) {
                emptyList()
            }
            _estado.update { it.copy(sugestoes = sugestoes, buscandoSugestoes = false) }
        }
    }

    fun buscar() {
        fecharSugestoes()
        carregar(_estado.value.texto.trim().ifEmpty { null })
    }

    /** Tocou numa sugestão: busca exatamente aquele produto. */
    fun escolherSugestao(produto: String) {
        _estado.update { it.copy(texto = produto) }
        buscar()
    }

    fun fecharSugestoes() {
        sugestoesJob?.cancel()
        _estado.update { it.copy(sugestoes = emptyList(), buscandoSugestoes = false) }
    }

    fun limpar() {
        fecharSugestoes()
        _estado.update { it.copy(texto = "", ordenacao = null) }
        carregar(null)
    }

    fun tentarDeNovo() = carregar(_estado.value.termoBuscado)

    /** Tocar de novo na ordenação ativa volta para a ordem padrão. */
    fun escolherOrdenacao(ordenacao: Ordenacao) {
        _estado.update { it.copy(ordenacao = if (it.ordenacao == ordenacao) null else ordenacao) }
        reaplicarOrdenacao()
    }

    /** Chamado depois que o usuário responde ao pedido de permissão de localização. */
    fun atualizarPosicao() {
        _estado.update { it.copy(posicao = localizacao.ultimaConhecida()) }
        reaplicarOrdenacao()
    }

    /** Conta a visualização uma vez por banner nesta abertura da tela. */
    fun bannerExibido(banner: Banner) {
        if (!bannersVistos.add(banner.id)) return
        viewModelScope.launch { cotacoes.registrarVisualizacao(banner.id) }
    }

    /** "Esse preço não confere": devolve se registrou (false = a pessoa já tinha avisado). */
    suspend fun denunciar(cotacao: Cotacao, motivo: String?): Boolean = cotacoes.denunciar(cotacao.id, motivo)

    /** Admin removeu o preço: some da lista na hora, sem buscar de novo. */
    fun tirarDosResultados(cotacaoId: String) {
        brutos = brutos.filter { it.id != cotacaoId }
        reaplicarOrdenacao()
    }

    fun aceitarDisclaimer() {
        preferencias.disclaimerAceito = true
        _estado.update { it.copy(mostrarDisclaimer = false) }
    }

    private fun carregar(termo: String?) {
        carga?.cancel()
        _estado.update { it.copy(carregando = true, erro = null, erroTipo = null, termoBuscado = termo) }
        carga = viewModelScope.launch {
            try {
                brutos = cotacoes.buscar(termo, _estado.value.posicao)
                reaplicarOrdenacao()
                _estado.update { it.copy(carregando = false) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message, erroTipo = e.tipo) }
            }
        }
        viewModelScope.launch {
            val banners = try {
                cotacoes.banners(termo, _estado.value.posicao)
            } catch (e: ErroAmigavel) {
                emptyList()
            }
            _estado.update { it.copy(banners = banners) }
        }
    }

    private fun reaplicarOrdenacao() {
        _estado.update { e ->
            val ordenados = e.ordenacao
                ?.let { Busca.ordenar(brutos, it, e.posicao ?: Geo.PETROPOLIS_CENTRO) }
                ?: brutos
            e.copy(
                resultados = ordenados,
                destaques = SeloPreco.destaques(brutos, comBusca = e.termoBuscado != null, config = config),
            )
        }
    }
}
