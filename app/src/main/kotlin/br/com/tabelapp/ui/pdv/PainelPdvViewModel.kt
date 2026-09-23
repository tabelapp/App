package br.com.tabelapp.ui.pdv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.TipoOperacao
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.PdvRepositorio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoPainel(
    val carregando: Boolean = true,
    val erro: String? = null,
    val lojas: List<LojaPdv> = emptyList(),
    val lojaId: String? = null,
    val precos: List<PrecoPdv> = emptyList(),
    val cota: SaldoCota? = null,
    val filtro: String = "",
    /** Item aberto no editor; [editandoId] null = produto novo. */
    val rascunho: RascunhoPreco? = null,
    val editandoId: String? = null,
    val errosEditor: List<String> = emptyList(),
    val salvando: Boolean = false,
    val mensagem: String? = null,
) {
    val loja: LojaPdv? get() = lojas.firstOrNull { it.id == lojaId }

    val precosFiltrados: List<PrecoPdv>
        get() = if (filtro.isBlank()) precos else precos.filter { Texto.casaBusca(it.produto, filtro) }

    /** Quanto o que está no editor vai custar na cota (null = ainda sem preço válido). */
    val custoDoRascunho: TipoOperacao?
        get() {
            val r = rascunho ?: return null
            val novo = r.precoCentavos ?: return null
            val atual = precos.firstOrNull { Texto.normalizar(it.produto) == Texto.normalizar(r.produto) }
            return TipoOperacao.classificar(atual?.precoCentavos, novo)
        }
}

/** Painel do PDV aprovado: tabela de preços oficial e saldo de operações. */
class PainelPdvViewModel(
    private val repositorio: PdvRepositorio,
    private val pdv: MeuPdv,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoPainel())
    val estado: StateFlow<EstadoPainel> = _estado.asStateFlow()

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            try {
                val lojas = repositorio.lojas(pdv.id)
                val lojaId = _estado.value.lojaId?.takeIf { id -> lojas.any { it.id == id } } ?: lojas.firstOrNull()?.id
                if (lojaId == null) {
                    _estado.update { it.copy(carregando = false, lojas = lojas, erro = "Nenhuma loja ativa neste cadastro.") }
                    return@launch
                }
                val precos = repositorio.precos(pdv.id, lojaId)
                val cota = repositorio.cota(pdv.id, lojaId)
                _estado.update { it.copy(carregando = false, lojas = lojas, lojaId = lojaId, precos = precos, cota = cota) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(carregando = false, erro = e.message) }
            }
        }
    }

    fun escolherLoja(id: String) {
        _estado.update { it.copy(lojaId = id) }
        carregar()
    }

    fun alterarFiltro(t: String) = _estado.update { it.copy(filtro = t) }

    fun novoItem() = _estado.update { it.copy(rascunho = RascunhoPreco(), editandoId = null, errosEditor = emptyList(), mensagem = null) }

    fun editar(item: PrecoPdv) =
        _estado.update { it.copy(rascunho = RascunhoPreco.de(item), editandoId = item.id, errosEditor = emptyList(), mensagem = null) }

    fun alterarRascunho(novo: RascunhoPreco) = _estado.update { it.copy(rascunho = novo, errosEditor = emptyList()) }

    fun fecharEditor() = _estado.update { it.copy(rascunho = null, editandoId = null, errosEditor = emptyList()) }

    fun salvar() {
        val e = _estado.value
        val r = e.rascunho ?: return
        val lojaId = e.lojaId ?: return
        val erros = r.erros()
        val preco = r.precoCentavos
        if (erros.isNotEmpty() || preco == null) {
            _estado.update { it.copy(errosEditor = erros) }
            return
        }
        _estado.update { it.copy(salvando = true, errosEditor = emptyList()) }
        viewModelScope.launch {
            try {
                val usadas = repositorio.salvarPreco(pdv.id, lojaId, r.produto, preco, r.validade, r.obs)
                _estado.update {
                    it.copy(
                        salvando = false, rascunho = null, editandoId = null,
                        mensagem = if (usadas > 0) "Salvo. Usou 1 operação." else "Salvo (sem custo de operação).",
                    )
                }
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(salvando = false, errosEditor = listOfNotNull(ex.message)) }
            }
        }
    }

    fun excluir(item: PrecoPdv) {
        viewModelScope.launch {
            try {
                repositorio.excluirPreco(item.id)
                _estado.update { it.copy(mensagem = "\"${item.produto}\" excluído.") }
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(mensagem = ex.message) }
            }
        }
    }
}
