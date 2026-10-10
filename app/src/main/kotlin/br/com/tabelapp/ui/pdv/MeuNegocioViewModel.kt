package br.com.tabelapp.ui.pdv

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.tabelapp.core.Alvara
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.Imagens
import br.com.tabelapp.dados.LeitorTexto
import br.com.tabelapp.dados.PdvRepositorio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EtapaPdv { CARREGANDO, FORMULARIO, SITUACAO }

data class EstadoPdv(
    val etapa: EtapaPdv = EtapaPdv.CARREGANDO,
    val pdvs: List<MeuPdv> = emptyList(),
    val erroCarregar: String? = null,
    // Formulário de cadastro
    val cnpj: String = "",
    val consultando: Boolean = false,
    /** Já consultou a Receita (achando ou não); libera o resto do formulário. */
    val consultado: Boolean = false,
    val receita: DadosReceita? = null,
    val avisoConsulta: String? = null,
    val nomeFantasia: String = "",
    val razaoSocial: String = "",
    val endereco: String = "",
    val bairro: String = "",
    val cidade: String = "Petrópolis",
    val uf: String = "RJ",
    val cep: String = "",
    val telefone: String = "",
    val alvara: ByteArray? = null,
    val alvaraMiniatura: ImageBitmap? = null,
    val alvaraTexto: String? = null,
    val lendoAlvara: Boolean = false,
    val avisoAlvara: String? = null,
    val erros: List<String> = emptyList(),
    val enviando: Boolean = false,
) {
    /** O CNPJ digitado aparece no texto lido da foto do alvará? null = ainda sem foto/leitura. */
    val cnpjNoAlvara: Boolean? get() = alvaraTexto?.let { Alvara.contemCnpj(it, cnpj) }
}

/**
 * Aba "Meu negócio" (conta CNPJ): cadastro do estabelecimento com comprovação
 * pelo alvará (decisão do fundador). O app consulta o CNPJ na Receita, lê a foto
 * do alvará e confere se o CNPJ aparece nela; o pedido fica pendente até o
 * Admin aprovar.
 */
class MeuNegocioViewModel(
    private val repositorio: PdvRepositorio,
    private val imagens: Imagens,
    private val leitorTexto: LeitorTexto,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoPdv())
    val estado: StateFlow<EstadoPdv> = _estado.asStateFlow()

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(erroCarregar = null) }
        viewModelScope.launch {
            try {
                val pdvs = repositorio.meusPdvs()
                _estado.update {
                    it.copy(pdvs = pdvs, etapa = if (pdvs.isEmpty()) EtapaPdv.FORMULARIO else EtapaPdv.SITUACAO)
                }
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(erroCarregar = e.message) }
            }
        }
    }

    /** Só no modo demonstração: faz o papel do Admin para dar para ver a área do PDV. */
    fun aprovarDemonstracao(pdv: MeuPdv) {
        viewModelScope.launch {
            try {
                repositorio.aprovar(pdv.id)
            } catch (e: ErroAmigavel) {
                _estado.update { it.copy(erroCarregar = e.message) }
            }
            carregar()
        }
    }

    /** Novo pedido (ou correção de um rejeitado, já com o CNPJ e o nome preenchidos). */
    fun novoCadastro(base: MeuPdv? = null) = _estado.update {
        EstadoPdv(
            etapa = EtapaPdv.FORMULARIO,
            pdvs = it.pdvs,
            cnpj = base?.cnpj?.let(Cnpj::formatar).orEmpty(),
            nomeFantasia = base?.nomeFantasia.orEmpty(),
        )
    }

    fun voltarASituacao() = _estado.update { it.copy(etapa = EtapaPdv.SITUACAO, erros = emptyList()) }

    fun alterarCnpj(texto: String) = _estado.update {
        it.copy(cnpj = texto.filter { c -> c.isDigit() || c in "./- " }.take(18), consultado = false, receita = null, avisoConsulta = null)
    }

    fun consultarCnpj() {
        val cnpj = _estado.value.cnpj
        if (!Cnpj.valido(cnpj)) {
            _estado.update { it.copy(erros = listOf("CNPJ inválido: confira os 14 números.")) }
            return
        }
        _estado.update { it.copy(consultando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                val r = repositorio.consultarCnpj(cnpj)
                _estado.update { e ->
                    e.copy(
                        consultando = false, consultado = true, receita = r, avisoConsulta = null,
                        // Preenche com o que a Receita informa (o dono pode ajustar).
                        nomeFantasia = e.nomeFantasia.ifBlank { r?.nomeFantasia ?: r?.razaoSocial.orEmpty() },
                        razaoSocial = r?.razaoSocial ?: e.razaoSocial,
                        endereco = r?.endereco ?: e.endereco,
                        bairro = r?.bairro ?: e.bairro,
                        cidade = r?.cidade ?: e.cidade,
                        uf = r?.uf ?: e.uf,
                        cep = r?.cep ?: e.cep,
                        telefone = e.telefone.ifBlank { r?.telefone.orEmpty() },
                    )
                }
            } catch (ex: ErroAmigavel) {
                // Consulta fora do ar: segue com preenchimento manual; o Admin confere.
                _estado.update {
                    it.copy(
                        consultando = false, consultado = true, receita = null,
                        avisoConsulta = "${ex.message} Você pode preencher os dados; nossa equipe confere depois.",
                    )
                }
            }
        }
    }

    fun alterarNome(t: String) = _estado.update { it.copy(nomeFantasia = t.take(120)) }
    fun alterarEndereco(t: String) = _estado.update { it.copy(endereco = t.take(200)) }
    fun alterarBairro(t: String) = _estado.update { it.copy(bairro = t.take(80)) }
    fun alterarCidade(t: String) = _estado.update { it.copy(cidade = t.take(80)) }
    fun alterarTelefone(t: String) = _estado.update { it.copy(telefone = t.take(20)) }

    /** Reduz a foto para envio e lê o texto dela para conferir o CNPJ. */
    fun alvaraEscolhido(uri: Uri) {
        _estado.update { it.copy(lendoAlvara = true, avisoAlvara = null, erros = emptyList()) }
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.Default) { imagens.jpegReduzido(uri) }
            val mini = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (jpeg == null || mini == null) {
                _estado.update { it.copy(lendoAlvara = false, avisoAlvara = "Não consegui abrir a foto. Tente outra.") }
                return@launch
            }
            val (texto, aviso) = try {
                leitorTexto.texto(uri) to null
            } catch (e: ErroAmigavel) {
                "" to e.message
            }
            _estado.update {
                it.copy(
                    alvara = jpeg, alvaraMiniatura = mini.asImageBitmap(), alvaraTexto = texto,
                    lendoAlvara = false, avisoAlvara = aviso,
                )
            }
        }
    }

    fun enviar() {
        val e = _estado.value
        val dados = CadastroPdv(
            cnpj = e.cnpj, nomeFantasia = e.nomeFantasia, razaoSocial = e.razaoSocial.ifBlank { null },
            endereco = e.endereco, bairro = e.bairro, cidade = e.cidade, uf = e.uf, cep = e.cep, telefone = e.telefone,
        )
        val erros = buildList {
            if (!e.consultado) add("Consulte o CNPJ antes de continuar.")
            addAll(dados.erros(temAlvara = e.alvara != null))
        }
        val alvara = e.alvara
        if (erros.isNotEmpty() || alvara == null) {
            _estado.update { it.copy(erros = erros) }
            return
        }
        _estado.update { it.copy(enviando = true, erros = emptyList()) }
        viewModelScope.launch {
            try {
                repositorio.cadastrar(dados, e.receita, alvara, cnpjNoAlvara = e.cnpjNoAlvara == true)
                val pdvs = repositorio.meusPdvs()
                _estado.update { EstadoPdv(etapa = EtapaPdv.SITUACAO, pdvs = pdvs) }
            } catch (ex: ErroAmigavel) {
                _estado.update { it.copy(enviando = false, erros = listOfNotNull(ex.message)) }
            }
        }
    }
}
