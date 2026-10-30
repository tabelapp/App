package br.com.tabelapp.ui.nf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.EstadosNfce
import br.com.tabelapp.core.IntervaloTentativas
import br.com.tabelapp.core.ItemNota
import br.com.tabelapp.core.LeitorNfce
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.NotaLida
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.erros.Erros
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EtapaNf { INICIO, LENDO_SEFAZ, CONFIRMACAO, FALHA, ENVIADO }

/** Por que a leitura não serviu (cada caso tem a sua tela calma). */
enum class FalhaNf {
    /** A Sefaz não respondeu, bloqueou o acesso ou demorou demais. */
    SEFAZ_INDISPONIVEL,
    SEM_INTERNET,
    /** A página aberta é de outra nota (regra de negócio). */
    NOTA_DIFERENTE,
    /** A página abriu, mas os produtos não foram encontrados. */
    LEITURA,
}

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
    /** Erro no início com tela própria (câmera negada, QR ilegível). */
    val erroInicioTipo: TipoErro? = null,
    /** Chave digitada na entrada manual (fica guardada; vem preenchida depois de uma falha da Sefaz). */
    val chaveManual: String = "",
    /** Há um QR Code lido guardado: dá para tentar de novo sem escanear. */
    val temQrGuardado: Boolean = false,
    /** Falha ao enviar (dados continuam aqui): tipo calmo, ou mensagem de regra de negócio. */
    val falhaEnvio: TipoErro? = null,
    val avisoEnvio: String? = null,
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
    /** Compartilhar a NF-e em PDF depois de enviar. */
    val querPdf: Boolean = false,
    /** Por que a leitura não serviu (mostrado na tela de falha). */
    val falha: FalhaNf? = null,
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
    /** Último QR Code lido (link + chave): "Tentar de novo" não precisa escanear outra vez. */
    private var qrGuardado: Pair<String, ChaveAcessoNfe>? = null
    /** Não insistir com a Sefaz: no mínimo 10 s entre uma tentativa e outra. */
    private val intervalo = IntervaloTentativas(10_000)
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
        // Estado da nota pelo código da UF na chave: só os estados que o app já sabe ler.
        val uf = chave?.let(EstadosNfce::ativoDaChave)
        val erro = when {
            texto.filter { it.isDigit() }.length != 44 -> "A chave tem 44 números. Confira o que foi digitado."
            chave == null -> "Chave inválida: algum número foi digitado errado."
            uf == null -> NOTA_DE_OUTRO_ESTADO
            else -> null
        }
        val url = uf?.urlConsultaChave
        if (erro != null || chave == null || url == null) {
            _estado.update { it.copy(erroInicio = erro, erroInicioTipo = null) }
            return
        }
        iniciarLeitura(url, chave, modoManual = true)
    }

    fun alterarChaveManual(texto: String) =
        _estado.update { it.copy(chaveManual = texto.filter { c -> c.isDigit() }.take(44), erroInicio = null) }

    /** Conteúdo lido do QR Code — ou o link do QR colado pelo usuário. */
    fun qrLido(conteudo: String) {
        val texto = conteudo.trim()
        val chave = ChaveAcessoNfe.doQrCode(texto)
        val url = texto.takeIf { it.startsWith("http", ignoreCase = true) }
        val uf = chave?.let(EstadosNfce::ativoDaChave)
        if (chave == null || (url == null && uf != null)) {
            // Ilegível, não é NFC-e, ou só a chave sem o link (a consulta precisa do link completo):
            // tela calma; se a chave veio, já fica preenchida na entrada manual.
            Erros.registrar("qr", "QR não reconhecido como NFC-e (tamanho ${texto.length})")
            _estado.update {
                it.copy(erroInicioTipo = TipoErro.QR_INVALIDO, erroInicio = null, chaveManual = chave?.digitos ?: it.chaveManual)
            }
            return
        }
        if (uf == null || url == null) {
            _estado.update { it.copy(erroInicioTipo = null, erroInicio = NOTA_DE_OUTRO_ESTADO) }
            return
        }
        qrGuardado = url to chave
        iniciarLeitura(url, chave, modoManual = false)
    }

    private fun iniciarLeitura(url: String, chave: ChaveAcessoNfe, modoManual: Boolean) {
        cnpjConsultado = null
        htmlSefaz = null
        urlPagina = null
        intervalo.registrar(System.currentTimeMillis())
        _estado.value = EstadoNf(
            etapa = EtapaNf.LENDO_SEFAZ, urlSefaz = url, chave = chave.digitos, modoManual = modoManual,
            consultandoReceita = true, chaveManual = _estado.value.chaveManual, temQrGuardado = qrGuardado != null,
        )
        consultarLojas(chave.cnpjEmitente)
        consultarReceita(chave.cnpjEmitente)
    }

    /** O leitor de QR Code do Google não abriu (câmera negada ou leitor ainda indisponível). */
    fun erroNoLeitor(erro: Exception) {
        Erros.registrar("leitor QR", null, erro)
        val camera = erro.message.orEmpty().contains("permission", ignoreCase = true) ||
            erro.message.orEmpty().contains("camera", ignoreCase = true)
        _estado.update {
            if (camera) it.copy(erroInicioTipo = TipoErro.CAMERA_NEGADA, erroInicio = null)
            else it.copy(erroInicioTipo = null, erroInicio = Erros.leitorIndisponivel())
        }
    }

    fun fecharErroInicio() = _estado.update { it.copy(erroInicioTipo = null, erroInicio = null) }

    fun notaLidaNaSefaz(nota: NotaLida) {
        // A página aberta precisa ser a da nota do QR Code / da chave digitada
        // (na entrada manual a pessoa poderia consultar outra nota no meio do caminho).
        val chavesNaPagina = htmlSefaz?.let { runCatching { LeitorNfce.chavesNaPagina(it) }.getOrNull() }.orEmpty()
        if (chavesNaPagina.isNotEmpty() && _estado.value.chave !in chavesNaPagina) {
            _estado.update { it.copy(etapa = EtapaNf.FALHA, falha = FalhaNf.NOTA_DIFERENTE) }
            return
        }
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

    /** A página abriu, mas os produtos não apareceram dentro do tempo. */
    fun leituraSefazFalhou() {
        Erros.registrar("sefaz", "produtos não encontrados na página (chave ${_estado.value.chave.take(6)}…)")
        _estado.update { it.copy(etapa = EtapaNf.FALHA, falha = FalhaNf.LEITURA) }
    }

    /**
     * A Sefaz falhou (rede, bloqueio de IP, HTTP 403/429/5xx, demora). O detalhe técnico
     * vai só para o log; o usuário vê a tela calma. Nada é repetido automaticamente.
     */
    fun sefazFalhou(tipo: TipoErro, detalheTecnico: String) {
        if (_estado.value.etapa != EtapaNf.LENDO_SEFAZ) return
        Erros.registrar("sefaz", detalheTecnico)
        _estado.update {
            it.copy(etapa = EtapaNf.FALHA,
                falha = if (tipo == TipoErro.SEM_INTERNET) FalhaNf.SEM_INTERNET else FalhaNf.SEFAZ_INDISPONIVEL)
        }
    }

    /** Quanto falta para poder tentar de novo (mínimo de 10 s entre tentativas). */
    fun esperaParaTentarMs(): Long = intervalo.esperaRestanteMs(System.currentTimeMillis())

    /** Tenta ler a mesma nota de novo — só quando o usuário toca, e respeitando o intervalo. */
    fun tentarDeNovo() {
        if (!intervalo.podeTentar(System.currentTimeMillis())) return
        intervalo.registrar(System.currentTimeMillis())
        _estado.update { it.copy(etapa = EtapaNf.LENDO_SEFAZ, temPaginaSefaz = false, falha = null) }
    }

    /** Volta para a entrada manual com a chave da nota já preenchida (o QR lido continua guardado). */
    fun preencherManualmente() = _estado.update { e ->
        EstadoNf(chaveManual = e.chave.ifBlank { e.chaveManual }, temQrGuardado = qrGuardado != null)
    }

    /** Tenta de novo pelo QR Code guardado, sem escanear outra vez. */
    fun usarQrGuardado() {
        val (url, chave) = qrGuardado ?: return
        if (!intervalo.podeTentar(System.currentTimeMillis())) {
            _estado.update { it.copy(erroInicio = null) }
            return
        }
        iniciarLeitura(url, chave, modoManual = false)
    }

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
        _estado.update { it.copy(enviando = true, erros = emptyList(), falhaEnvio = null, avisoEnvio = null) }
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
                // Nada do que foi lido se perde: a tela continua com a nota, pronta para tentar de novo.
                _estado.update {
                    when (ex.tipo) {
                        null -> it.copy(enviando = false, avisoEnvio = ex.message) // regra de negócio (ex.: nota já enviada)
                        TipoErro.SEM_INTERNET, TipoErro.SESSAO_EXPIRADA -> it.copy(enviando = false, falhaEnvio = ex.tipo)
                        else -> it.copy(enviando = false, falhaEnvio = TipoErro.FALHA_ENVIO_NF)
                    }
                }
            }
        }
    }

    fun novaNota() {
        qrGuardado = null
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

/** Nota de um estado que o app ainda não lê (só RJ por enquanto; ver EstadosNfce). */
private const val NOTA_DE_OUTRO_ESTADO = "Esta nota é de outro estado. ${EstadosNfce.MENSAGEM_OUTROS_ESTADOS}"
