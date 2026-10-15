package br.com.tabelapp.ui.pdv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.LeitorPlanilha
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.MapaPrecos
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoPreco
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.TipoEmpresa
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.Geocodificador
import br.com.tabelapp.dados.Localizacao
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.erros.Erros
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EstadoPainel(
    val carregando: Boolean = true,
    val erro: String? = null,
    val lojas: List<LojaPdv> = emptyList(),
    val lojaId: String? = null,
    val precos: List<PrecoPdv> = emptyList(),
    /** Tamanho do catálogo, limite do plano e período de fundador. */
    val catalogo: StatusCatalogo? = null,
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
    /** Chegou ao limite do plano ao tentar criar item: mensagem calma com "Ver planos". */
    val mostrarLimite: Boolean = false,
    /** Tela "Ver planos" aberta (null = fechada). */
    val planos: EstadoPlanos? = null,
    /** Resumo da última importação de planilha (mostrado até o PDV fechar). */
    val resultadoImportacao: ResultadoImportacao? = null,
    /** Lojas sendo localizadas no mapa agora. */
    val localizando: Set<String> = emptySet(),
    /** Lojas cujo endereço o mapa não achou (o dono pode usar "Estou na loja"). */
    val naoAchadas: Set<String> = emptySet(),
) {
    val loja: LojaPdv? get() = lojas.firstOrNull { it.id == lojaId }

    val precosFiltrados: List<PrecoPdv>
        get() = if (filtro.isBlank()) precos else precos.filter { Texto.casaBusca(it.produto, filtro) }

    /** O que está no editor é item novo (ocupa lugar no catálogo)? null = ainda sem nome. */
    val rascunhoEhNovo: Boolean?
        get() {
            val r = rascunho ?: return null
            if (r.produto.isBlank()) return null
            return precos.none { Texto.normalizar(it.produto) == Texto.normalizar(r.produto) }
        }
}

/** Tela de planos: faixas da tabela `planos` e os interesses já registrados. */
data class EstadoPlanos(
    val carregando: Boolean = true,
    val lista: List<Plano> = emptyList(),
    val interessados: Set<String> = emptySet(),
    val enviando: String? = null,
    val erro: String? = null,
)

/** Prévia da planilha: linhas válidas, erros e o que ficaria fora do limite do plano. */
data class EstadoPlanilha(
    val nomeArquivo: String,
    val processando: Boolean = true,
    val linhas: List<Planilha.Linha> = emptyList(),
    val erros: List<Planilha.ErroLinha> = emptyList(),
    val simulacao: ResultadoImportacao? = null,
    val erro: String? = null,
)

/** Pedido de pagamento recém-criado (promoção/banner), até o Pix automático. */
data class PagamentoCriado(val codigo: String, val descricao: String, val valorCentavos: Long)

/** Painel do PDV aprovado: tabela de preços oficial, limite do catálogo, cadastro e promoções. */
class PainelPdvViewModel(
    private val repositorio: PdvRepositorio,
    private val pdv: MeuPdv,
    private val geocodificador: Geocodificador? = null,
    private val localizacao: Localizacao? = null,
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
                // Anunciante não tem loja nem tabela de preços: só as promoções.
                if (pdv.tipo == TipoEmpresa.ANUNCIANTE) {
                    val promocoes = repositorio.promocoes(pdv.id)
                    _estado.update { it.copy(carregando = false, promocoes = promocoes) }
                    return@launch
                }
                val lojas = repositorio.lojas(pdv.id)
                val lojaId = _estado.value.lojaId?.takeIf { id -> lojas.any { it.id == id } } ?: lojas.firstOrNull()?.id
                if (lojaId == null) {
                    _estado.update { it.copy(carregando = false, lojas = lojas, erro = "Nenhuma loja ativa neste cadastro.") }
                    return@launch
                }
                val precos = repositorio.precos(pdv.id, lojaId)
                val catalogo = repositorio.statusCatalogo(pdv.id, lojaId)
                val promocoes = repositorio.promocoes(pdv.id)
                _estado.update {
                    it.copy(carregando = false, lojas = lojas, lojaId = lojaId, precos = precos, catalogo = catalogo,
                        promocoes = promocoes)
                }
                localizarLojasSemPino(lojas)
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

    /** Catálogo cheio: em vez do editor, a mensagem calma do limite (o banco também confere). */
    fun novoItem() = _estado.update {
        if (it.catalogo?.noLimite == true) it.copy(mostrarLimite = true, mensagem = null)
        else it.copy(rascunho = RascunhoPreco(), editandoId = null, errosEditor = emptyList(), mensagem = null)
    }

    fun fecharLimite() = _estado.update { it.copy(mostrarLimite = false) }

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
                repositorio.salvarPreco(pdv.id, lojaId, r.produto, preco, r.validade, r.obs, r.unidade, r.aPartirDe)
                _estado.update { it.copy(salvando = false, rascunho = null, editandoId = null, mensagem = "Salvo.") }
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update {
                    // Limite do plano: fecha o editor e mostra a mensagem calma com "Ver planos".
                    if (ex.codigo == "limite_catalogo") it.copy(salvando = false, rascunho = null, mostrarLimite = true)
                    else it.copy(salvando = false, errosEditor = listOfNotNull(ex.message))
                }
                if (ex.codigo == "limite_catalogo") carregar()
            }
        }
    }

    /**
     * Loja sem pino no mapa (cadastro novo, endereço editado ou loja antiga): localiza
     * o endereço no aparelho e grava a coordenada, sem incomodar o dono. Não achou?
     * O painel mostra o aviso com "Estou na loja".
     */
    private fun localizarLojasSemPino(lojas: List<LojaPdv>) {
        val g = geocodificador ?: return
        val pendentes = lojas.filter { it.local == null && it.id !in _estado.value.localizando }
        if (pendentes.isEmpty()) return
        _estado.update { it.copy(localizando = it.localizando + pendentes.map { l -> l.id }) }
        viewModelScope.launch {
            for (loja in pendentes) {
                val ponto = loja.enderecoParaMapa?.let { g.localizar(it) }?.takeIf { MapaPrecos.coordenadaValida(it) }
                try {
                    if (ponto != null) repositorio.definirCoordenadas(loja.id, ponto)
                    _estado.update { e ->
                        e.copy(
                            localizando = e.localizando - loja.id,
                            naoAchadas = if (ponto == null) e.naoAchadas + loja.id else e.naoAchadas - loja.id,
                            lojas = e.lojas.map { if (it.id == loja.id && ponto != null) it.copy(local = ponto) else it },
                        )
                    }
                } catch (e: ErroAmigavel) {
                    Erros.registrar("localizar loja", e.message, e)
                    _estado.update { it.copy(localizando = it.localizando - loja.id) }
                }
            }
        }
    }

    /** "Estou na loja": usa a localização atual do celular como pino da loja. */
    fun usarLocalizacaoDoCelular() {
        val lojaId = _estado.value.lojaId ?: return
        val ponto = localizacao?.ultimaConhecida()
        if (ponto == null || !MapaPrecos.coordenadaValida(ponto)) {
            _estado.update { it.copy(mensagem = "Não consegui sua localização. Ligue a localização do celular e tente de novo.") }
            return
        }
        viewModelScope.launch {
            try {
                repositorio.definirCoordenadas(lojaId, ponto, peloAparelho = true)
                _estado.update { e ->
                    e.copy(
                        naoAchadas = e.naoAchadas - lojaId,
                        lojas = e.lojas.map { if (it.id == lojaId) it.copy(local = ponto, localPeloAparelho = true) else it },
                        mensagem = "Pronto: a loja está no mapa no ponto onde você está.",
                    )
                }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(mensagem = e.message) }
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
            val arte = try {
                arteJpeg?.let { repositorio.enviarArte(pdv.id, it) }
            } catch (ex: ErroAmigavel) {
                // A imagem continua escolhida no formulário: é só tentar de novo.
                aoErro(if (ex.tipo == null) ex.message.orEmpty() else Erros.texto(TipoErro.FALHA_UPLOAD_FOTO))
                return@launch
            }
            try {
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
                aoErro(if (ex.tipo == null || ex.tipo == TipoErro.SESSAO_EXPIRADA) ex.message.orEmpty()
                    else Erros.texto(TipoErro.FALHA_PAGAMENTO))
            }
        }
    }

    /** Edita a promoção. Nova imagem: sobe antes; [removerArte] tira a imagem atual. */
    fun editarPromocao(
        p: PromocaoPdv, titulo: String, descricao: String, link: String, arteJpeg: ByteArray?, removerArte: Boolean,
        palavras: List<String>, aoErro: (String) -> Unit, aoTerminar: () -> Unit,
    ) {
        viewModelScope.launch {
            val novaArte = try {
                arteJpeg?.let { repositorio.enviarArte(pdv.id, it) }
            } catch (ex: ErroAmigavel) {
                aoErro(if (ex.tipo == null) ex.message.orEmpty() else Erros.texto(TipoErro.FALHA_UPLOAD_FOTO))
                return@launch
            }
            try {
                repositorio.editarPromocao(
                    p.id, titulo, descricao.ifBlank { null }, link.ifBlank { null }, palavras,
                    trocarArte = novaArte != null || removerArte, artePath = novaArte,
                )
                _estado.update { it.copy(mensagem = "Promoção atualizada.") }
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
                    it.copy(mensagem = if (apagada) "Promoção excluída." else "Promoção excluída. O pagamento fica guardado no histórico.")
                }
                carregar()
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(mensagem = ex.message) }
            }
        }
    }

    // ------------------------------------------------------------------ planos

    fun abrirPlanos() {
        _estado.update { it.copy(mostrarLimite = false, planos = EstadoPlanos()) }
        viewModelScope.launch {
            try {
                val lista = repositorio.planos()
                _estado.update { e -> e.copy(planos = e.planos?.copy(carregando = false, lista = lista)) }
            } catch (ex: ErroAmigavel) {
                _estado.update { e -> e.copy(planos = e.planos?.copy(carregando = false, erro = ex.message)) }
            }
        }
    }

    fun fecharPlanos() = _estado.update { it.copy(planos = null) }

    /** "Quero ser avisado": registra o interesse (sem cobrança por enquanto). */
    fun quererSerAvisado(plano: Plano) {
        _estado.update { e -> e.copy(planos = e.planos?.copy(enviando = plano.codigo, erro = null)) }
        viewModelScope.launch {
            try {
                repositorio.registrarInteresse(pdv.id, plano.codigo)
                _estado.update { e ->
                    e.copy(planos = e.planos?.let { it.copy(enviando = null, interessados = it.interessados + plano.codigo) })
                }
            } catch (ex: ErroAmigavel) {
                _estado.update { e -> e.copy(planos = e.planos?.copy(enviando = null, erro = ex.message)) }
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

    /** Lê e confere a planilha escolhida e mostra o que entra e o que ficaria fora do limite (nada é gravado ainda). */
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

    fun importarPlanilha() {
        val p = _estado.value.planilha ?: return
        val lojaId = _estado.value.lojaId ?: return
        atualizarPlanilha { it.copy(processando = true, erro = null) }
        viewModelScope.launch {
            try {
                val resultado = repositorio.importarPlanilha(pdv.id, lojaId, p.linhas)
                _estado.update { it.copy(planilha = null, resultadoImportacao = resultado) }
                carregar()
            } catch (ex: ErroAmigavel) {
                atualizarPlanilha { it.copy(processando = false, erro = ex.message) }
            }
        }
    }

    fun fecharPlanilha() = _estado.update { it.copy(planilha = null) }

    private fun atualizarPlanilha(f: (EstadoPlanilha) -> EstadoPlanilha) =
        _estado.update { e -> e.copy(planilha = e.planilha?.let(f)) }

    fun fecharResultadoImportacao() = _estado.update { it.copy(resultadoImportacao = null) }

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
