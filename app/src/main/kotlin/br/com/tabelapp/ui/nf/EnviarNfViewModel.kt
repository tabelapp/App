package br.com.tabelapp.ui.nf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.ChaveAcessoNfe
import br.com.tabelapp.core.Dinheiro
import br.com.tabelapp.core.ItemNota
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

enum class EtapaNf { INICIO, LENDO_SEFAZ, FORMULARIO, CONFIRMACAO, ENVIADO }

/** Linha editável do formulário (preço como o usuário digitou). */
data class ItemForm(val produto: String = "", val preco: String = "")

data class EstadoNf(
    val etapa: EtapaNf = EtapaNf.INICIO,
    /** URL do QR Code, aberta no próprio celular para ler os produtos na Sefaz. */
    val urlSefaz: String? = null,
    val chave: String = "",
    /** Lojas cadastradas do CNPJ da nota. Vazio = estabelecimento não cadastrado. */
    val lojas: List<LojaResumo> = emptyList(),
    val lojaId: String? = null,
    val pdvNome: String = "",
    val pdvEndereco: String = "",
    val itens: List<ItemForm> = listOf(ItemForm()),
    /** Data da compra (emissão da NF). Vem da Sefaz quando a leitura funciona; padrão: hoje. */
    val dataNf: LocalDate = LocalDate.now(),
    /** Mensagem informativa no topo do formulário (ex.: "lemos 12 produtos na Sefaz"). */
    val aviso: String? = null,
    val erros: List<String> = emptyList(),
    val erroInicio: String? = null,
    val enviando: Boolean = false,
    val itensEnviados: Int = 0,
    /** Já temos a página da Sefaz carregada (dá para mandar para análise se a leitura falhar). */
    val temPaginaSefaz: Boolean = false,
    val leituraFalhou: Boolean = false,
) {
    fun rascunho() = RascunhoNf(
        chaveAcesso = chave,
        lojaId = lojaId,
        pdvNome = pdvNome,
        pdvEndereco = pdvEndereco,
        dataNf = dataNf,
        itens = itens
            .filter { it.produto.isNotBlank() || it.preco.isNotBlank() }
            .map { ItemNota(it.produto.trim(), Dinheiro.parse(it.preco) ?: 0) },
    )
}

/**
 * Fluxo "Enviar NF" (briefing, seção 4):
 *  1. Lê o QR Code do cupom (ou o usuário digita a chave / preenche tudo à mão).
 *  2. Abre a consulta da Sefaz no celular e lê os produtos automaticamente.
 *  3. O usuário confere/corrige o formulário.
 *  4. Uma única tela de confirmação com todos os produtos, e envia de uma vez.
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
    fun paginaParaAnalise(): String? = htmlSefaz?.let { runCatching { br.com.tabelapp.core.LeitorNfce.anonimizar(it) }.getOrNull() }

    /** Conteúdo lido do QR Code — ou chave/link digitado ou colado pelo usuário. */
    fun qrLido(conteudo: String) {
        val texto = conteudo.trim()
        val chave = ChaveAcessoNfe.doQrCode(texto)
        if (chave == null) {
            _estado.update {
                it.copy(erroInicio = "Não reconheci uma nota fiscal eletrônica (NFC-e) nesse QR Code.")
            }
            return
        }
        if (!chave.ehDoRioDeJaneiro) {
            _estado.update {
                it.copy(erroInicio = "Esta nota não é do Rio de Janeiro. Por enquanto o Tabelapp funciona só em Petrópolis/RJ.")
            }
            return
        }
        val url = texto.takeIf { it.startsWith("http", ignoreCase = true) }
        cnpjConsultado = null
        htmlSefaz = null
        _estado.value = EstadoNf(
            etapa = if (url != null) EtapaNf.LENDO_SEFAZ else EtapaNf.FORMULARIO,
            urlSefaz = url,
            chave = chave.digitos,
        )
        consultarLojas(chave.cnpjEmitente)
    }

    fun erroNoLeitor(mensagem: String) = _estado.update { it.copy(erroInicio = mensagem) }

    fun preencherManualmente() {
        _estado.update {
            it.copy(etapa = EtapaNf.FORMULARIO, erroInicio = null, aviso = if (it.urlSefaz != null) {
                "Preencha os produtos da nota abaixo."
            } else null)
        }
    }

    fun notaLidaNaSefaz(nota: NotaLida) {
        _estado.update { e ->
            e.copy(
                etapa = EtapaNf.FORMULARIO,
                itens = nota.itens.map { ItemForm(it.produto, Dinheiro.formatar(it.precoCentavos).removePrefix("R$ ")) },
                pdvNome = e.pdvNome.ifBlank { nota.emitenteNome.orEmpty() },
                pdvEndereco = e.pdvEndereco.ifBlank { nota.emitenteEndereco.orEmpty() },
                dataNf = nota.dataEmissao ?: e.dataNf,
                aviso = "Lemos ${nota.itens.size} produto(s) na Sefaz. Confira antes de enviar.",
            )
        }
    }

    fun leituraSefazFalhou() {
        _estado.update {
            it.copy(
                etapa = EtapaNf.FORMULARIO,
                leituraFalhou = true,
                aviso = "Não consegui ler os produtos na Sefaz. A chave já está preenchida — digite os produtos abaixo.",
            )
        }
    }

    fun alterarChave(chave: String) {
        _estado.update { it.copy(chave = chave) }
        ChaveAcessoNfe.deTexto(chave)?.let { consultarLojas(it.cnpjEmitente) }
    }

    fun alterarDataNf(data: LocalDate) = _estado.update { it.copy(dataNf = data) }

    fun escolherLoja(lojaId: String) = _estado.update { it.copy(lojaId = lojaId) }
    fun alterarPdvNome(nome: String) = _estado.update { it.copy(pdvNome = nome) }
    fun alterarPdvEndereco(endereco: String) = _estado.update { it.copy(pdvEndereco = endereco) }

    fun alterarItem(indice: Int, item: ItemForm) =
        _estado.update { e -> e.copy(itens = e.itens.mapIndexed { i, atual -> if (i == indice) item else atual }) }

    fun adicionarItem() = _estado.update { it.copy(itens = it.itens + ItemForm()) }

    fun removerItem(indice: Int) = _estado.update { e ->
        e.copy(itens = e.itens.filterIndexed { i, _ -> i != indice }.ifEmpty { listOf(ItemForm()) })
    }

    fun revisar() {
        val e = _estado.value
        val erros = buildList {
            // Nota de loja cadastrada não precisa de nome digitado — a loja é escolhida na lista.
            val rascunho = e.rascunho().let { if (e.lojas.isNotEmpty()) it.copy(pdvNome = "-") else it }
            addAll(rascunho.erros())
            if (e.lojas.size > 1 && e.lojaId == null) add("Escolha em qual loja foi a compra.")
            if (e.itens.any { it.preco.isNotBlank() && (Dinheiro.parse(it.preco) ?: 0L) <= 0L }) {
                add("Confira os preços: use o formato 12,90.")
            }
        }.distinct()
        _estado.update { it.copy(erros = erros, etapa = if (erros.isEmpty()) EtapaNf.CONFIRMACAO else it.etapa) }
    }

    fun voltarParaEdicao() = _estado.update { it.copy(etapa = EtapaNf.FORMULARIO) }

    fun enviar() {
        val rascunho = _estado.value.rascunho()
        _estado.update { it.copy(enviando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                val n = repositorio.enviar(rascunho)
                _estado.update { it.copy(enviando = false, etapa = EtapaNf.ENVIADO, itensEnviados = n) }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(enviando = false, erros = listOfNotNull(e.message)) }
            }
        }
    }

    fun novaNota() {
        cnpjConsultado = null
        htmlSefaz = null
        _estado.value = EstadoNf()
    }

    /** Se o CNPJ da nota for de um PDV cadastrado, a nota fica ligada à loja. */
    private fun consultarLojas(cnpj: String) {
        if (cnpj == cnpjConsultado) return
        cnpjConsultado = cnpj
        viewModelScope.launch {
            val lojas = try {
                repositorio.lojasDoCnpj(cnpj)
            } catch (e: ErroAmigavel) {
                emptyList() // sem rede: segue como estabelecimento não cadastrado
            }
            _estado.update {
                it.copy(lojas = lojas, lojaId = lojas.singleOrNull()?.lojaId)
            }
        }
    }
}
