package br.com.tabelapp.dados.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.DadosDemo
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.NotaFiscalRepositorio
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
        _estado.value = EstadoSessao.Logado(
            Usuario("demo", email.substringBefore('@'), email, TipoConta.CPF, cadastroCompleto = true)
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
}

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
