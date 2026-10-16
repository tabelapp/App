package br.com.tabelapp.ui.lista

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.ItemLista
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.MelhorPorItem
import br.com.tabelapp.core.OpcaoPdvUnico
import br.com.tabelapp.core.RelatorioListaCompras
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.TextoLista
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.Localizacao
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EtapaLista { LISTAS, EDITANDO, RESULTADO }

data class EstadoLista(
    val etapa: EtapaLista = EtapaLista.LISTAS,
    val carregando: Boolean = true,
    val erro: String? = null,
    val listas: List<ListaResumo> = emptyList(),
    // Lista aberta
    val lista: ListaResumo? = null,
    val itens: List<ItemSalvo> = emptyList(),
    val texto: String = "",
    val sugestoes: List<SugestaoProduto> = emptyList(),
    val buscandoSugestoes: Boolean = false,
    // Resultado da pesquisa
    val processando: Boolean = false,
    val opcoesPdv: List<OpcaoPdvUnico> = emptyList(),
    val melhorPorItem: MelhorPorItem? = null,
)

/**
 * Lista de compras (briefing, seção 3): o usuário monta a lista no tempo dele
 * (cada alteração é salva na hora, com a busca inteligente sugerindo produtos
 * que têm preço) e, quando quiser, pede a pesquisa. O app busca cada item e
 * entrega os dois relatórios: tudo num lugar só e item a item.
 */
class ListaViewModel(
    private val listasRepo: ListasRepositorio,
    private val cotacoes: CotacoesRepositorio,
    private val localizacao: Localizacao,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoLista())
    val estado: StateFlow<EstadoLista> = _estado.asStateFlow()

    private var sugestoesJob: Job? = null

    init {
        carregarListas()
    }

    private fun executar(bloco: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                bloco()
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(erro = e.message, carregando = false, processando = false) }
            }
        }
    }

    fun carregarListas() = executar {
        _estado.update { it.copy(carregando = true, erro = null) }
        val listas = listasRepo.listas()
        _estado.update { it.copy(carregando = false, listas = listas) }
    }

    fun novaLista(nome: String) = executar {
        val id = listasRepo.criar(nome)
        val listas = listasRepo.listas()
        _estado.update { it.copy(listas = listas) }
        listas.firstOrNull { it.id == id }?.let { abrir(it) }
    }

    fun excluirLista(lista: ListaResumo) = executar {
        listasRepo.excluir(lista.id)
        carregarListas()
    }

    fun abrir(lista: ListaResumo) = executar {
        _estado.update { it.copy(etapa = EtapaLista.EDITANDO, lista = lista, itens = emptyList(), texto = "", sugestoes = emptyList(), erro = null) }
        val itens = listasRepo.itens(lista.id)
        _estado.update { it.copy(itens = itens) }
    }

    fun renomear(nome: String) = executar {
        val lista = _estado.value.lista ?: return@executar
        if (nome.isBlank()) return@executar
        listasRepo.renomear(lista.id, nome)
        _estado.update { it.copy(lista = lista.copy(nome = nome.trim())) }
    }

    fun voltarParaListas() {
        _estado.update { it.copy(etapa = EtapaLista.LISTAS, lista = null, itens = emptyList()) }
        carregarListas()
    }

    fun voltarParaEdicao() = _estado.update { it.copy(etapa = EtapaLista.EDITANDO) }

    /** Busca inteligente: sugere produtos com preço enquanto o usuário digita. */
    fun alterarTexto(texto: String) {
        _estado.update { it.copy(texto = texto) }
        sugestoesJob?.cancel()
        if (texto.trim().length < 2) {
            _estado.update { it.copy(sugestoes = emptyList(), buscandoSugestoes = false) }
            return
        }
        sugestoesJob = viewModelScope.launch {
            delay(300)
            _estado.update { it.copy(buscandoSugestoes = true) }
            val sugestoes = try {
                listasRepo.sugerir(texto.trim())
            } catch (e: ErroAmigavel) {
                emptyList()
            }
            _estado.update { it.copy(sugestoes = sugestoes, buscandoSugestoes = false) }
        }
    }

    fun adicionar(produto: String) = executar {
        val lista = _estado.value.lista ?: return@executar
        if (produto.isBlank()) return@executar
        sugestoesJob?.cancel()
        _estado.update { it.copy(texto = "", sugestoes = emptyList()) }
        listasRepo.adicionar(lista.id, produto.trim())
        val itens = listasRepo.itens(lista.id)
        _estado.update { it.copy(itens = itens) }
    }

    fun mudarQuantidade(item: ItemSalvo, delta: Int) = executar {
        val nova = item.quantidade + delta
        if (nova < 1) return@executar
        // Mostra na hora; grava em seguida.
        _estado.update { e -> e.copy(itens = e.itens.map { if (it.id == item.id) it.copy(quantidade = nova) else it }) }
        listasRepo.alterarQuantidade(item.id, nova)
    }

    fun remover(item: ItemSalvo) = executar {
        _estado.update { e -> e.copy(itens = e.itens.filter { it.id != item.id }) }
        listasRepo.remover(item.id)
    }

    /** "Enviar a lista": busca o preço de cada item e monta os dois relatórios. */
    fun pesquisar() = executar {
        val itens = _estado.value.itens
        if (itens.isEmpty()) return@executar
        _estado.update { it.copy(processando = true, erro = null) }
        val posicao = localizacao.ultimaConhecida()
        val itensLista = itens.map { it.paraItemLista() }
        // Busca em grupos para não disparar centenas de pedidos de uma vez.
        val resultados = mutableMapOf<ItemLista, List<Cotacao>>()
        for (grupo in itensLista.chunked(6)) {
            coroutineScope {
                grupo.map { item -> async { item to cotacoes.buscar(item.produto, posicao) } }.awaitAll()
            }.forEach { (item, lista) -> resultados[item] = lista }
        }
        val relatorio = RelatorioListaCompras(itensLista, resultados, posicao)
        _estado.update {
            it.copy(
                processando = false,
                etapa = EtapaLista.RESULTADO,
                opcoesPdv = relatorio.pdvUnico(),
                melhorPorItem = relatorio.melhorPorItem(),
            )
        }
    }

    fun textoPorPdv(): String {
        val e = _estado.value
        return TextoLista.porPdv(e.lista?.nome ?: "Minha lista", e.opcoesPdv, e.itens.size)
    }

    fun textoPorItem(): String? {
        val e = _estado.value
        return e.melhorPorItem?.let { TextoLista.porItem(e.lista?.nome ?: "Minha lista", it) }
    }
}
