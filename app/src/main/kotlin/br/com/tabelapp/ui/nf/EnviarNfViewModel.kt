package br.com.tabelapp.ui.nf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.DadosReceita
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
    /** Entrada manual: chave digitada, consulta pela página de consulta da Sefaz. */
    val modoManual: Boolean = false,
    /** Razão social e endereço como vieram da nota. */
    val razaoSocialNota: String = "",
    val enderecoNota: String = "",
    /** Dados públicos do CNPJ do vendedor (nome fantasia, endereço). */
    val receita: DadosReceita? = null,
    val consultandoReceita: Boolean = false,
    /** Como o lugar é conhecido, quando a Receita não traz nome fantasia (só vale confirmado). */
    val nomeSugerido: String = "",
    val resultadoSugestao: String? = null,
    /** Receber cópia em PDF da nota no e-mail depois de enviar. */
    val querPdf: Boolean = false,
) {
    /** A Receita não informou nome fantasia: o usuário pode sugerir um. */
    val podeSugerirNome: Boolean
        get() = lojas.isEmpty() && !consultandoReceita && receita?.nomeFantasia.isNullOrBlank()

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
 * Fluxo "Enviar preços" pela nota fiscal (briefing, seção 4):
 *  1. Lê o QR Code do cupom (ou a chave de 44 números digitada).
 *  2. Abre a consulta da Sefaz no celular e lê produtos, preços, estabelecimento e data.
 *  3. Uma única tela de resumo; o usuário só confirma o envio.
 *
 * Não há digitação de produto nem de preço: o que vai para a busca é exatamente
 * o que está na nota na Sefaz. Se a leitura falhar, a nota não é enviada.
 */
class EnviarNfViewModel(
    private val repositorio: NotaFiscalRepositorio,
    /** Consulta pública do CNPJ (nome fantasia e endereço do vendedor). */
    private val consultarCnpj: suspend (String) -> DadosReceita?,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoNf())
    val estado: StateFlow<EstadoNf> = _estado.asStateFlow()

    private var cnpjConsultado: String? = null
    private var htmlSefaz: String? = null
    private var urlPagina: String? = null

    fun htmlCapturado(html: String, url: String?) {
        htmlSefaz = html
        urlPagina = url
        if (!_estado.value.temPaginaSefaz) _estado.update { it.copy(temPaginaSefaz = true) }
    }

    /** Página da Sefaz sem scripts e com CPF mascarado, para o usuário mandar para análise. */
    fun paginaParaAnalise(): String? = htmlSefaz?.let { runCatching { LeitorNfce.anonimizar(it) }.getOrNull() }

    /** Página da Sefaz já lida (HTML + endereço), para gerar a cópia em PDF. */
    fun paginaDaNota(): Pair<String, String?>? = htmlSefaz?.let { it to urlPagina }

    /**
     * Entrada manual: os 44 números da chave (impressos no cupom). Abre a consulta
     * da Sefaz pela chave; o app preenche o número e o usuário resolve a verificação.
     */
    fun chaveDigitada(texto: String) {
        val chave = ChaveAcessoNfe.deTexto(texto)
        val erro = when {
            texto.filter { it.isDigit() }.length != 44 -> "A chave tem 44 números. Confira o que foi digitado."
            chave == null -> "Chave inválida: algum número foi digitado errado."
            !chave.ehDoRioDeJaneiro ->
                "Esta nota não é do Rio de Janeiro. Por enquanto o Tabelapp funciona só em Petrópolis/RJ."
            else -> null
        }
        if (erro != null || chave == null) {
            _estado.update { it.copy(erroInicio = erro) }
            return
        }
        iniciarLeitura(URL_CONSULTA_CHAVE_RJ, chave, modoManual = true)
    }

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
        iniciarLeitura(url, chave, modoManual = false)
    }

    private fun iniciarLeitura(url: String, chave: ChaveAcessoNfe, modoManual: Boolean) {
        cnpjConsultado = null
        htmlSefaz = null
        urlPagina = null
        _estado.value = EstadoNf(
            etapa = EtapaNf.LENDO_SEFAZ, urlSefaz = url, chave = chave.digitos, modoManual = modoManual,
            consultandoReceita = true,
        )
        consultarLojas(chave.cnpjEmitente)
        consultarReceita(chave.cnpjEmitente)
    }

    fun erroNoLeitor(mensagem: String) = _estado.update { it.copy(erroInicio = mensagem) }

    fun notaLidaNaSefaz(nota: NotaLida) {
        _estado.update { e ->
            val base = e.copy(
                itens = nota.itens,
                razaoSocialNota = nota.emitenteNome.orEmpty(),
                enderecoNota = nota.emitenteEndereco.orEmpty(),
                dataNf = nota.dataEmissao ?: e.dataNf,
            ).comNomeEEndereco()
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

    fun alterarNomeSugerido(texto: String) = _estado.update { it.copy(nomeSugerido = texto.take(120)) }

    fun alternarPdf(quer: Boolean) = _estado.update { it.copy(querPdf = quer) }

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
                // A sugestão de nome vai depois da nota (o banco exige que a pessoa tenha comprado lá).
                val sugestao = e.nomeSugerido.trim().takeIf { it.length >= 2 && e.podeSugerirNome }?.let { nome ->
                    try {
                        val cnpj = ChaveAcessoNfe.deTexto(e.chave)?.cnpjEmitente
                        if (cnpj != null && repositorio.sugerirNomePdv(cnpj, nome)) {
                            "Nome \"$nome\" confirmado: já aparece na busca."
                        } else {
                            "Obrigado pela sugestão! O nome \"$nome\" aparece na busca depois de confirmado " +
                                "por outra pessoa que comprou lá ou pela nossa equipe."
                        }
                    } catch (ex: ErroAmigavel) {
                        null
                    }
                }
                _estado.update {
                    it.copy(enviando = false, etapa = EtapaNf.ENVIADO, itensEnviados = n, resultadoSugestao = sugestao)
                }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(enviando = false, erros = listOfNotNull(ex.message)) }
            }
        }
    }

    fun novaNota() {
        cnpjConsultado = null
        htmlSefaz = null
        urlPagina = null
        _estado.value = EstadoNf()
    }

    private fun errosDe(e: EstadoNf): List<String> = buildList {
        // Nota de loja cadastrada não depende do nome lido — a loja vem do cadastro.
        val rascunho = e.rascunho().let { if (e.lojas.isNotEmpty()) it.copy(pdvNome = "-") else it }
        addAll(rascunho.erros())
        if (e.lojas.size > 1 && e.lojaId == null) add("Escolha em qual loja foi a compra.")
    }.distinct()

    /**
     * Nome e endereço do vendedor: o nome fantasia da Receita (como o lugar é
     * conhecido) quando houver; senão a razão social da nota. Endereço: o da nota
     * ou, se a página não mostrar, o da Receita.
     */
    private fun EstadoNf.comNomeEEndereco(): EstadoNf = copy(
        pdvNome = receita?.nomeFantasia?.takeIf { it.isNotBlank() } ?: razaoSocialNota,
        pdvEndereco = enderecoNota.ifBlank { receita?.enderecoCompleto().orEmpty() },
    )

    private fun DadosReceita.enderecoCompleto(): String? =
        listOfNotNull(endereco, bairro, listOfNotNull(cidade, uf).joinToString(" - ").ifBlank { null })
            .joinToString(", ").ifBlank { null }

    private fun consultarReceita(cnpj: String) {
        viewModelScope.launch {
            val dados = try {
                consultarCnpj(cnpj)
            } catch (ex: ErroAmigavel) {
                null // sem consulta: fica com o que veio da nota
            }
            _estado.update { e ->
                e.copy(receita = dados, consultandoReceita = false).comNomeEEndereco().let {
                    if (it.etapa == EtapaNf.CONFIRMACAO) it.copy(erros = errosDe(it)) else it
                }
            }
        }
    }

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
