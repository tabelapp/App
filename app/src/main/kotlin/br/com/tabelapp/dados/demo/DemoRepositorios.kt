package br.com.tabelapp.dados.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.DadosDemo
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Modo demonstração: roda sem Supabase configurado, com os dados fictícios
 * de Petrópolis. Qualquer e-mail/senha entra. Nada é salvo.
 */
class DemoAuthRepositorio : AuthRepositorio {
    private val _estado = MutableStateFlow<EstadoSessao>(EstadoSessao.Deslogado)
    override val estado: StateFlow<EstadoSessao> = _estado.asStateFlow()

    override suspend fun entrarComEmail(email: String, senha: String) {
        delay(300)
        if (!email.contains('@') || senha.isEmpty()) throw ErroAmigavel("Informe e-mail e senha.")
        // Na demonstração, e-mail começando com "admin" entra como Admin (para ver a fila de cadastros).
        val tipo = if (email.trim().lowercase().startsWith("admin")) TipoConta.ADMIN else TipoConta.CPF
        _estado.value = EstadoSessao.Logado(
            Usuario("demo", email.substringBefore('@'), email, tipo, cadastroCompleto = true)
        )
    }

    override suspend fun cadastrarComEmail(nome: String, email: String, senha: String, tipo: TipoConta): Boolean {
        delay(300)
        _estado.value = EstadoSessao.Logado(Usuario("demo", nome, email, tipo, cadastroCompleto = true))
        return true
    }

    override suspend fun concluirCadastro(nome: String, tipo: TipoConta) {
        val atual = (_estado.value as? EstadoSessao.Logado)?.usuario ?: return
        _estado.value = EstadoSessao.Logado(atual.copy(nome = nome, tipo = tipo, cadastroCompleto = true))
    }

    override suspend fun sair() {
        _estado.value = EstadoSessao.Deslogado
    }

    /** Simula o primeiro login pelo Google: cai na tela de escolher CPF/CNPJ. */
    @Composable
    override fun lembrarLoginGoogle(aoFalhar: (String) -> Unit): (() -> Unit)? {
        val escopo = rememberCoroutineScope()
        return {
            escopo.launch {
                delay(300)
                _estado.value = EstadoSessao.Logado(
                    Usuario("demo", "Visitante Google", "visitante@gmail.com", TipoConta.CPF, cadastroCompleto = false)
                )
            }
        }
    }
}

class DemoCotacoesRepositorio(private val banco: DemoBanco) : CotacoesRepositorio {
    override suspend fun buscar(termo: String?, posicao: PontoGeo?): List<Cotacao> {
        delay(250)
        return DadosDemo.buscar(termo, extras = banco.enviados)
    }
}

/** Guarda em memória o que foi enviado na demonstração (some ao fechar o app). */
class DemoBanco {
    val enviados = mutableListOf<Cotacao>()
    val chavesEnviadas = mutableSetOf<String>()
    val pdvs = mutableListOf<DemoPdv>()
}

/**
 * Demonstração do cadastro de PDV: a consulta da Receita devolve dados fictícios
 * para qualquer CNPJ válido, e os pedidos ficam em memória. O Admin de
 * demonstração (e-mail começando com "admin") aprova ou rejeita.
 */
class DemoPdvRepositorio(private val banco: DemoBanco) : PdvRepositorio {
    override suspend fun consultarCnpj(cnpj: String): DadosReceita? {
        delay(500)
        val digitos = cnpj.filter { it.isDigit() }
        if (!Cnpj.valido(digitos)) return null
        return DadosReceita(
            cnpj = digitos, razaoSocial = "EMPRESA DEMONSTRAÇÃO LTDA", nomeFantasia = "Mercadinho Demonstração",
            situacao = "ATIVA", endereco = "Rua do Imperador, 100", bairro = "Centro", cidade = "Petrópolis",
            uf = "RJ", cep = "25620000", telefone = "2422220000", atividade = "Comércio varejista de mercadorias em geral",
        )
    }

    override suspend fun meusPdvs(): List<MeuPdv> = banco.pdvs.map { it.meu }

    override suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray, cnpjNoAlvara: Boolean) {
        delay(600)
        val cnpj = dados.cnpj.filter { it.isDigit() }
        banco.pdvs.removeAll { it.meu.cnpj == cnpj }
        val id = "pdv-" + UUID.randomUUID()
        banco.pdvs += DemoPdv(
            meu = MeuPdv(id, cnpj, dados.razaoSocial, dados.nomeFantasia.trim(), StatusPdv.PENDENTE, null, cnpjNoAlvara, Instant.now()),
            pendente = PdvPendente(
                id = id, cnpj = cnpj, razaoSocial = dados.razaoSocial, nomeFantasia = dados.nomeFantasia.trim(),
                endereco = listOf(dados.endereco, dados.bairro, dados.cidade).filter { it.isNotBlank() }.joinToString(", "),
                telefone = dados.telefone.ifBlank { null }, alvaraPath = id, cnpjConferidoNoAlvara = cnpjNoAlvara,
                situacaoReceita = receita?.situacao, razaoSocialReceita = receita?.razaoSocial,
                enderecoReceita = receita?.let { listOfNotNull(it.endereco, it.bairro, it.cidade, it.uf).joinToString(", ") },
                donoNome = "Você (demonstração)", donoEmail = null, enviadoEm = Instant.now(),
            ),
            alvara = alvaraJpeg,
        )
    }

    override suspend fun pendentes(): List<PdvPendente> =
        banco.pdvs.filter { it.meu.status == StatusPdv.PENDENTE }.map { it.pendente }

    override suspend fun fotoAlvara(caminho: String): ByteArray =
        banco.pdvs.firstOrNull { it.pendente.alvaraPath == caminho }?.alvara ?: throw ErroAmigavel("Foto não encontrada.")

    override suspend fun aprovar(pdvId: String) = mudarStatus(pdvId, StatusPdv.APROVADO, null)

    override suspend fun rejeitar(pdvId: String, motivo: String) {
        if (motivo.isBlank()) throw ErroAmigavel("Informe o motivo.")
        mudarStatus(pdvId, StatusPdv.REJEITADO, motivo.trim())
    }

    private fun mudarStatus(pdvId: String, status: StatusPdv, motivo: String?) {
        val i = banco.pdvs.indexOfFirst { it.meu.id == pdvId }
        if (i < 0) throw ErroAmigavel("Cadastro não encontrado.")
        banco.pdvs[i] = banco.pdvs[i].let { it.copy(meu = it.meu.copy(status = status, motivoRejeicao = motivo)) }
    }
}

data class DemoPdv(val meu: MeuPdv, val pendente: PdvPendente, val alvara: ByteArray)

class DemoNotaFiscalRepositorio(private val banco: DemoBanco) : NotaFiscalRepositorio {
    override suspend fun lojasDoCnpj(cnpj: String): List<LojaResumo> = DadosDemo.lojasDoCnpj(cnpj)

    override suspend fun enviar(rascunho: RascunhoNf): Int {
        delay(400)
        val chave = rascunho.chaveAcesso.filter { it.isDigit() }.ifEmpty { null }
        if (chave != null && !banco.chavesEnviadas.add(chave)) throw ErroAmigavel("Esta nota fiscal já foi enviada.")
        val loja = rascunho.lojaId?.let { id ->
            chave?.let { DadosDemo.lojasDoCnpj(it.substring(6, 20)) }.orEmpty().firstOrNull { it.lojaId == id }
        }
        val agora = Instant.now()
        val itens = rascunho.itensParaEnvio()
        banco.enviados += itens.map { item ->
            Cotacao(
                id = "nf-" + UUID.randomUUID(),
                produto = item.produto,
                precoCentavos = item.precoCentavos,
                validade = Validade.daNotaFiscal(rascunho.dataNf),
                dataNf = rascunho.dataNf,
                obs = null,
                fonte = Fonte.USUARIO_NF,
                lojaId = loja?.lojaId,
                pdvId = loja?.pdvId,
                pdvNome = loja?.pdvNome ?: rascunho.pdvNome.trim(),
                lojaNome = loja?.lojaNome,
                endereco = loja?.endereco ?: rascunho.pdvEndereco.trim().ifEmpty { null },
                telefone = loja?.telefone,
                local = loja?.local,
                criadoEm = agora,
            )
        }
        return itens.size
    }
}
