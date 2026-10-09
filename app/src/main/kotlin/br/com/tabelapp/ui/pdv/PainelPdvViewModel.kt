package br.com.tabelapp.ui.pdv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LeitorPlanilha
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.SimulacaoImportacao
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.TipoOperacao
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.PdvRepositorio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    /** Nome e site exibidos (podem ter sido editados nesta tela). */
    val nomeFantasia: String = "",
    val site: String? = null,
    val promocoes: List<PromocaoPdv> = emptyList(),
    /** Pagamento recém-criado, para abrir o WhatsApp de pagamento. */
    val pagamentoCriado: PagamentoCriado? = null,
    /** Importação de planilha em andamento (prévia aberta). */
    val planilha: EstadoPlanilha? = null,
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

/** Prévia da planilha: linhas válidas, erros e quanto vai custar na cota. */
data class EstadoPlanilha(
    val nomeArquivo: String,
    val processando: Boolean = true,
    val linhas: List<Planilha.Linha> = emptyList(),
    val erros: List<Planilha.ErroLinha> = emptyList(),
    val simulacao: SimulacaoImportacao? = null,
    val erro: String? = null,
)

/** Pedido de pagamento recém-criado (promoção ou pacote), até o Pix automático. */
data class PagamentoCriado(val codigo: String, val descricao: String, val valorCentavos: Long)

/** Painel do PDV aprovado: tabela de preços oficial, saldo de operações, cadastro e promoções. */
class PainelPdvViewModel(
    private val repositorio: PdvRepositorio,
    private val pdv: MeuPdv,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoPainel(nomeFantasia = pdv.nomeFantasia, site = pdv.site))
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
                val promocoes = repositorio.promocoes(pdv.id)
                _estado.update {
                    it.copy(carregando = false, lojas = lojas, lojaId = lojaId, precos = precos, cota = cota, promocoes = promocoes)
                }
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

    /** Edição do cadastro: nome de exibição, site e dados da loja (endereço, telefone, WhatsApp). */
    fun salvarCadastro(nome: String, site: String, loja: LojaPdv, aoTerminar: () -> Unit) {
        viewModelScope.launch {
            try {
                repositorio.atualizarPdv(pdv.id, nome, site.ifBlank { null })
                repositorio.atualizarLoja(loja)
                _estado.update { it.copy(nomeFantasia = nome.trim(), site = site.trim().ifEmpty { null }, mensagem = "Dados atualizados.") }
                aoTerminar()
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(mensagem = ex.message) }
            }
        }
    }

    fun criarPromocao(
        titulo: String, descricao: String, link: String, arteJpeg: ByteArray?, palavras: List<String>, visualizacoes: Int,
        aoErro: (String) -> Unit, aoTerminar: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val arte = arteJpeg?.let { repositorio.enviarArte(pdv.id, it) }
                val codigo = repositorio.criarPromocao(
                    pdv.id, titulo, descricao.ifBlank { null }, link.ifBlank { null }, arte, palavras, visualizacoes,
                )
                val valor = when (visualizacoes) { 100 -> 1000L; 250 -> 2500L; else -> 5000L }
                _estado.update {
                    it.copy(pagamentoCriado = PagamentoCriado(codigo, "Banner \"$titulo\" — $visualizacoes visualizações", valor))
                }
                aoTerminar()
                carregar()
            } catch (ex: ErroAmigavel) {
                aoErro(ex.message.orEmpty())
            }
        }
    }

    fun excluirPromocao(p: PromocaoPdv) {
        viewModelScope.launch {
            try {
                val apagada = repositorio.excluirPromocao(p.id)
                _estado.update {
                    it.copy(mensagem = if (apagada) "Promoção excluída." else "Promoção encerrada (fica no seu histórico).")
                }
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(mensagem = ex.message) }
            }
        }
    }

    fun comprarPacote() {
        val lojaId = _estado.value.lojaId ?: return
        viewModelScope.launch {
            try {
                val codigo = repositorio.comprarPacoteOperacoes(pdv.id, lojaId)
                _estado.update { it.copy(pagamentoCriado = PagamentoCriado(codigo, "+50 operações (valem 30 dias)", 1000)) }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(mensagem = ex.message) }
            }
        }
    }

    /** Reabre o pedido de pagamento de uma promoção que ainda está aguardando. */
    fun pagarPromocao(p: PromocaoPdv) {
        val codigo = p.pagamentoPendenteId ?: return
        _estado.update {
            it.copy(pagamentoCriado = PagamentoCriado(codigo, "Banner \"${p.titulo}\" — ${p.visualizacoesPendentes} visualizações",
                p.valorPendenteCentavos ?: 0))
        }
    }

    /** Lê e confere a planilha escolhida e calcula o custo na cota (nada é gravado ainda). */
    fun abrirPlanilha(bytes: ByteArray, nomeArquivo: String) {
        val lojaId = _estado.value.lojaId ?: return
        _estado.update { it.copy(planilha = EstadoPlanilha(nomeArquivo)) }
        viewModelScope.launch {
            val resultado = withContext(Dispatchers.Default) {
                runCatching {
                    val lida = LeitorPlanilha.ler(bytes, nomeArquivo)
                    Planilha.validar(lida.celulas, LocalDate.now(), lida.primeiraLinha)
                }.getOrNull()
            }
            if (resultado == null) {
                atualizarPlanilha { it.copy(processando = false, erro = "Não consegui ler o arquivo. Use Excel (.xlsx) ou .csv no modelo do Tabelapp.") }
                return@launch
            }
            if (resultado.linhas.isEmpty()) {
                atualizarPlanilha {
                    it.copy(processando = false, erros = resultado.erros,
                        erro = "Nenhuma linha válida. Confira as colunas: Produto, Preço, Validade e OBS.")
                }
                return@launch
            }
            simular(lojaId, resultado.linhas, resultado.erros)
        }
    }

    private suspend fun simular(lojaId: String, linhas: List<Planilha.Linha>, erros: List<Planilha.ErroLinha>) {
        try {
            val simulacao = repositorio.simularPlanilha(pdv.id, lojaId, linhas)
            atualizarPlanilha { it.copy(processando = false, linhas = linhas, erros = erros, simulacao = simulacao, erro = null) }
        } catch (ex: ErroAmigavel) {
            atualizarPlanilha { it.copy(processando = false, linhas = linhas, erros = erros, erro = ex.message) }
        }
    }

    /** Recalcula a prévia (ex.: depois de comprar operações). */
    fun refazerSimulacao() {
        val p = _estado.value.planilha ?: return
        val lojaId = _estado.value.lojaId ?: return
        atualizarPlanilha { it.copy(processando = true) }
        viewModelScope.launch {
            cota(lojaId)
            simular(lojaId, p.linhas, p.erros)
        }
    }

    private suspend fun cota(lojaId: String) {
        runCatching { repositorio.cota(pdv.id, lojaId) }.getOrNull()?.let { c -> _estado.update { it.copy(cota = c) } }
    }

    fun importarPlanilha() {
        val p = _estado.value.planilha ?: return
        val lojaId = _estado.value.lojaId ?: return
        atualizarPlanilha { it.copy(processando = true, erro = null) }
        viewModelScope.launch {
            try {
                val usadas = repositorio.importarPlanilha(pdv.id, lojaId, p.linhas)
                _estado.update {
                    it.copy(planilha = null, mensagem = "Planilha importada: ${p.linhas.size} produto(s), $usadas operação(ões) usada(s).")
                }
                carregar()
            } catch (ex: ErroAmigavel) {
                atualizarPlanilha { it.copy(processando = false, erro = ex.message) }
            }
        }
    }

    fun fecharPlanilha() = _estado.update { it.copy(planilha = null) }

    private fun atualizarPlanilha(f: (EstadoPlanilha) -> EstadoPlanilha) =
        _estado.update { e -> e.copy(planilha = e.planilha?.let(f)) }

    fun fecharPagamento() = _estado.update { it.copy(pagamentoCriado = null) }

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
