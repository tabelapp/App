package br.com.tabelapp.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.IgnoradoEncarte
import br.com.tabelapp.core.ItemLidoEncarte
import br.com.tabelapp.core.LeitorJsonEncarte
import br.com.tabelapp.core.RegrasEncarte
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.EncartesAdminRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.LojaEncarte
import br.com.tabelapp.dados.LoteLancado
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Uma imagem (JPEG já reduzida) ou PDF escolhido para leitura. A imagem não é guardada em lugar nenhum. */
class ArquivoEncarte(val nome: String, val bytes: ByteArray, val tipo: String)

data class EstadoEncartes(
    val arquivos: List<ArquivoEncarte> = emptyList(),
    val loja: String = "",
    val endereco: String = "",
    /** Usado quando o endereço fica em branco ("Petrópolis - RJ"). */
    val cidadeUf: String = "",
    /** Validade para os itens que vierem sem data (dd/mm/aaaa). */
    val validadePadrao: String = "",
    val lojasConhecidas: List<LojaEncarte> = emptyList(),
    val lendo: Boolean = false,
    val progresso: String? = null,
    val erro: String? = null,
    val itens: List<ItemLidoEncarte> = emptyList(),
    val ignorados: List<IgnoradoEncarte> = emptyList(),
    val lancando: Boolean = false,
    val lote: LoteLancado? = null,
    val aviso: String? = null,
) {
    val enderecoFinal: String get() = endereco.trim().ifBlank { cidadeUf.trim() }
    val validadePadraoData: LocalDate? get() = LeitorJsonEncarte.data(validadePadrao)

    /** Itens marcados, já com a validade padrão onde faltar (é o que vai para o banco). */
    fun paraLancar(): List<ItemLidoEncarte> = itens.filter { it.marcado }
        .map { if (it.validade == null) it.copy(validade = validadePadraoData) else it }

    fun problemas(hoje: LocalDate = LocalDate.now()): List<String> = buildList {
        if (loja.isBlank()) add("Informe o nome da loja.")
        if (enderecoFinal.isBlank()) add("Informe o endereço ou a cidade - UF.")
        val marcados = paraLancar()
        if (marcados.isEmpty()) add("Marque pelo menos um item.")
        if (marcados.size > RegrasEncarte.MAX_ITENS) add("No máximo ${RegrasEncarte.MAX_ITENS} itens por lote.")
        val comErro = marcados.count { RegrasEncarte.erros(it, hoje).isNotEmpty() }
        if (comErro > 0) add("$comErro item(ns) marcado(s) com problema (veja em vermelho).")
    }
}

/**
 * Leitor de encartes da aba Admin: escolhe as imagens, lê uma por uma no servidor, une os
 * repetidos (mesmo produto + mesmo preço), o Admin confere/edita e só então lança.
 */
class EncartesAdminViewModel(private val repositorio: EncartesAdminRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoEncartes())
    val estado: StateFlow<EstadoEncartes> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repositorio.lojasConhecidas() }.onSuccess { l -> _estado.update { it.copy(lojasConhecidas = l) } }
        }
    }

    fun adicionarArquivos(novos: List<ArquivoEncarte>) = _estado.update { it.copy(arquivos = it.arquivos + novos, erro = null) }
    fun removerArquivo(indice: Int) = _estado.update { it.copy(arquivos = it.arquivos.filterIndexed { i, _ -> i != indice }) }
    fun avisarErro(texto: String) = _estado.update { it.copy(erro = texto) }
    fun alterarLoja(texto: String) = _estado.update { it.copy(loja = texto.take(120)) }
    fun alterarEndereco(texto: String) = _estado.update { it.copy(endereco = texto.take(200)) }
    fun alterarCidadeUf(texto: String) = _estado.update { it.copy(cidadeUf = texto.take(60)) }
    fun alterarValidadePadrao(texto: String) = _estado.update { it.copy(validadePadrao = texto.take(10)) }
    fun escolherLoja(l: LojaEncarte) = _estado.update { it.copy(loja = l.nome, endereco = l.endereco.orEmpty()) }

    fun lerEncartes() {
        val e = _estado.value
        if (e.arquivos.isEmpty() || e.lendo) return
        _estado.update { it.copy(lendo = true, erro = null, aviso = null, lote = null) }
        viewModelScope.launch {
            val listas = mutableListOf<List<ItemLidoEncarte>>()
            val ignorados = mutableListOf<IgnoradoEncarte>()
            var lojaLida: String? = null
            var erro: String? = null
            for ((i, arquivo) in e.arquivos.withIndex()) {
                _estado.update { it.copy(progresso = "Lendo imagem ${i + 1} de ${e.arquivos.size}…") }
                try {
                    val leitura = repositorio.ler(arquivo.bytes, arquivo.tipo, e.loja)
                    listas += leitura.itens
                    ignorados += leitura.ignorados
                    lojaLida = lojaLida ?: leitura.lojaNome
                } catch (ex: ErroAmigavel) {
                    erro = "Imagem ${i + 1}: ${ex.message}"
                    // Sem configuração, sem permissão ou sem limite: não adianta tentar as outras.
                    if (ex.codigo in setOf("nao_configurado", "sem_permissao", "limite_diario", "nao_autenticado")) break
                }
            }
            val itens = RegrasEncarte.unir(listas)
            // A validade mais comum vira a padrão (para os itens que vierem sem data).
            val validadeComum = itens.mapNotNull { it.validade }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            _estado.update {
                it.copy(
                    lendo = false, progresso = null, erro = erro, itens = itens, ignorados = ignorados,
                    loja = it.loja.ifBlank { lojaLida.orEmpty() },
                    validadePadrao = it.validadePadrao.ifBlank { validadeComum?.let(Validade::formatar).orEmpty() },
                    aviso = if (itens.isEmpty() && erro == null) "Nenhum produto com preço foi encontrado." else null,
                )
            }
        }
    }

    fun alternar(indice: Int) = editar(indice) { it.copy(marcado = !it.marcado) }

    fun editar(indice: Int, mudanca: (ItemLidoEncarte) -> ItemLidoEncarte) = _estado.update { e ->
        e.copy(itens = e.itens.mapIndexed { i, item -> if (i == indice) mudanca(item) else item })
    }

    fun marcarTodos(marcar: Boolean) = _estado.update { e -> e.copy(itens = e.itens.map { it.copy(marcado = marcar) }) }

    fun lancar() {
        val e = _estado.value
        if (e.lancando || e.problemas().isNotEmpty()) return
        _estado.update { it.copy(lancando = true, erro = null) }
        viewModelScope.launch {
            try {
                val lote = repositorio.lancar(e.loja.trim(), e.enderecoFinal, e.paraLancar())
                _estado.update { it.copy(lancando = false, lote = lote) }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(lancando = false, erro = ex.message) }
            }
        }
    }

    fun desfazerLote() {
        val lote = _estado.value.lote ?: return
        _estado.update { it.copy(lancando = true, erro = null) }
        viewModelScope.launch {
            try {
                val n = repositorio.desfazer(lote.loteId)
                _estado.update { it.copy(lancando = false, lote = null, aviso = "Lote desfeito: $n preço(s) apagado(s).") }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(lancando = false, erro = ex.message) }
            }
        }
    }

    /** Começa outro encarte (mantém as lojas conhecidas). */
    fun novo() = _estado.update { EstadoEncartes(lojasConhecidas = it.lojasConhecidas) }
}
