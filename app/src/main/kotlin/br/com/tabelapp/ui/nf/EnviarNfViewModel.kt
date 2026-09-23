package br.com.tabelapp.ui.nf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.ItemNota
import br.com.tabelapp.core.LeitorNfce
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.NotaLida
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.NotaFiscalRepositorio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class EtapaNf { INICIO, LENDO_SEFAZ, CONFIRMACAO, FALHA, ENVIADO }

data class EstadoNf(
    val etapa: EtapaNf = EtapaNf.INICIO,
    /** URL do QR Code, aberta no próprio celular para ler os produtos na Sefaz. */
    val urlSefaz: String? = null,
    val chave: String = "",
    /** Lojas cadastradas do CNPJ da nota. Vazio = estabelecimento não cadastrado. */
    val lojas: List<LojaResumo> = emptyList(),
    val lojaId: String? = null,
    /** Dados lidos da Sefaz — o usuário não edita nada, só confere e confirma. */
    val pdvNome: String = "",
    val pdvEndereco: String = "",
    val itens: List<ItemNota> = emptyList(),
    val dataNf: LocalDate = LocalDate.now(),
    val erros: List<String> = emptyList(),
    val erroInicio: String? = null,
    val enviando: Boolean = false,
    val itensEnviados: Int = 0,
    /** Já temos a página da Sefaz carregada (dá para mandar para análise se a leitura falhar). */
    val temPaginaSefaz: Boolean = false,
) {
    fun rascunho() = RascunhoNf(
        chaveAcesso = chave,
        lojaId = lojaId,
        pdvNome = pdvNome,
        pdvEndereco = pdvEndereco,
        dataNf = dataNf,
        itens = itens,
    )
}

/**
 * Fluxo "Enviar NF" (briefing, seção 4):
 *  1. Lê o QR Code do cupom (ou o link do QR colado).
 *  2. Abre a consulta da Sefaz no celular e lê produtos, preços, estabelecimento e data.
 *  3. Uma única tela de resumo; o usuário só confirma o envio.
 *
 * Não há digitação de produto nem de preço: o que vai para a busca é exatamente
 * o que está na nota na Sefaz. Se a leitura falhar, a nota não é enviada.
 */
class EnviarNfViewModel(private val repositorio: NotaFiscalRepositorio) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoNf())
    val estado: StateFlow<EstadoNf> = _estado.asStateFlow()

    private var cnpjConsultado: String? = null
    private var htmlSefaz: String? = null

    fun htmlCapturado(html: String) {
        htmlSefaz = html
        if (!_estado.value.temPaginaSefaz) _estado.update { it.copy(temPaginaSefaz = true) }
    }

    /** Página da Sefaz sem scripts e com CPF mascarado, para o usuário mandar para análise. */
    fun paginaParaAnalise(): String? = htmlSefaz?.let { runCatching { LeitorNfce.anonimizar(it) }.getOrNull() }

    /** Conteúdo lido do QR Code — ou o link do QR colado pelo usuário. */
    fun qrLido(conteudo: String) {
        val texto = conteudo.trim()
        val chave = ChaveAcessoNfe.doQrCode(texto)
        val url = texto.takeIf { it.startsWith("http", ignoreCase = true) }
        val erro = when {
            chave == null -> "Não reconheci uma nota fiscal eletrônica (NFC-e) nesse QR Code."
            !chave.ehDoRioDeJaneiro ->
                "Esta nota não é do Rio de Janeiro. Por enquanto o Tabelapp funciona só em Petrópolis/RJ."
            // Só a chave não basta: a consulta na Sefaz precisa do link completo do QR Code.
            url == null -> "Use o QR Code do cupom (ou cole o link completo dele)."
            else -> null
        }
        if (erro != null || chave == null || url == null) {
            _estado.update { it.copy(erroInicio = erro) }
            return
        }
        cnpjConsultado = null
        htmlSefaz = null
        _estado.value = EstadoNf(etapa = EtapaNf.LENDO_SEFAZ, urlSefaz = url, chave = chave.digitos)
        consultarLojas(chave.cnpjEmitente)
    }

    fun erroNoLeitor(mensagem: String) = _estado.update { it.copy(erroInicio = mensagem) }

    fun notaLidaNaSefaz(nota: NotaLida) {
        _estado.update { e ->
            val base = e.copy(
                itens = nota.itens,
                pdvNome = nota.emitenteNome.orEmpty(),
                pdvEndereco = nota.emitenteEndereco.orEmpty(),
                dataNf = nota.dataEmissao ?: e.dataNf,
            )
            base.copy(etapa = EtapaNf.CONFIRMACAO, erros = errosDe(base))
        }
    }

    fun leituraSefazFalhou() = _estado.update { it.copy(etapa = EtapaNf.FALHA) }

    /** Tenta ler a mesma nota de novo (ex.: a Sefaz estava fora do ar). */
    fun tentarDeNovo() = _estado.update { it.copy(etapa = EtapaNf.LENDO_SEFAZ, temPaginaSefaz = false) }

    /** Rede com várias lojas: o usuário escolhe em qual foi a compra (a única escolha permitida). */
    fun escolherLoja(lojaId: String) = _estado.update { e ->
        e.copy(lojaId = lojaId).let { it.copy(erros = errosDe(it)) }
    }

    fun enviar() {
        val e = _estado.value
        val erros = errosDe(e)
        if (erros.isNotEmpty()) {
            _estado.update { it.copy(erros = erros) }
            return
        }
        _estado.update { it.copy(enviando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                val n = repositorio.enviar(e.rascunho())
                _estado.update { it.copy(enviando = false, etapa = EtapaNf.ENVIADO, itensEnviados = n) }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(enviando = false, erros = listOfNotNull(ex.message)) }
            }
        }
    }

    fun novaNota() {
        cnpjConsultado = null
        htmlSefaz = null
        _estado.value = EstadoNf()
    }

    private fun errosDe(e: EstadoNf): List<String> = buildList {
        // Nota de loja cadastrada não depende do nome lido — a loja vem do cadastro.
        val rascunho = e.rascunho().let { if (e.lojas.isNotEmpty()) it.copy(pdvNome = "-") else it }
        addAll(rascunho.erros())
        if (e.lojas.size > 1 && e.lojaId == null) add("Escolha em qual loja foi a compra.")
    }.distinct()

    /** Se o CNPJ da nota for de um PDV cadastrado, a nota fica ligada à loja. */
    private fun consultarLojas(cnpj: String) {
        if (cnpj == cnpjConsultado) return
        cnpjConsultado = cnpj
        viewModelScope.launch {
            val lojas = try {
                repositorio.lojasDoCnpj(cnpj)
            } catch (ex: ErroAmigavel) {
                emptyList() // sem rede: segue como estabelecimento não cadastrado
            }
            _estado.update { e ->
                e.copy(lojas = lojas, lojaId = lojas.singleOrNull()?.lojaId).let {
                    if (it.etapa == EtapaNf.CONFIRMACAO) it.copy(erros = errosDe(it)) else it
                }
            }
        }
    }
}
