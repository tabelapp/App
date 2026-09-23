package br.com.tabelapp.dados.supabase

import androidx.compose.runtime.Composable
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.ErrosServidor
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import io.github.jan.supabase.compose.auth.composable.rememberSignInWithGoogle
import io.github.jan.supabase.compose.auth.composeAuth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Converte qualquer falha de rede/servidor numa mensagem para o usuário. */
internal suspend fun <T> traduzindoErros(bloco: suspend () -> T): T =
    try {
        bloco()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        throw ErroAmigavel(ErrosServidor.traduzir(e.message), e)
    } catch (e: IOException) {
        throw ErroAmigavel("Sem conexão com a internet. Tente de novo.", e)
    } catch (e: ErroAmigavel) {
        throw e
    } catch (e: Exception) {
        throw ErroAmigavel(ErrosServidor.traduzir(e.message), e)
    }

@Serializable
private data class UsuarioDto(
    val id: String,
    val tipo: String,
    val nome: String? = null,
    val email: String? = null,
    @SerialName("cadastro_completo") val cadastroCompleto: Boolean = false,
)

class SupabaseAuthRepositorio(
    private val supabase: SupabaseClient,
    private val escopo: CoroutineScope,
    private val googleConfigurado: Boolean,
) : AuthRepositorio {

    private val _estado = MutableStateFlow<EstadoSessao>(EstadoSessao.Carregando)
    override val estado: StateFlow<EstadoSessao> = _estado.asStateFlow()

    init {
        escopo.launch {
            supabase.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> carregarPerfil()
                    is SessionStatus.NotAuthenticated -> _estado.value = EstadoSessao.Deslogado
                    SessionStatus.Initializing -> _estado.value = EstadoSessao.Carregando
                    // Sessão salva, mas não deu para renovar (ex.: sem internet): mantém quem já estava logado.
                    is SessionStatus.RefreshFailure -> Unit
                }
            }
        }
    }

    private suspend fun carregarPerfil() {
        val user = supabase.auth.currentUserOrNull() ?: return
        val perfil = try {
            supabase.from("usuarios")
                .select { filter { eq("id", user.id) } }
                .decodeSingleOrNull<UsuarioDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // sem rede: segue com os dados do login e tenta de novo na próxima sessão
        }
        _estado.value = EstadoSessao.Logado(
            Usuario(
                id = user.id,
                nome = perfil?.nome,
                email = perfil?.email ?: user.email,
                tipo = TipoConta.doCodigo(perfil?.tipo),
                cadastroCompleto = perfil?.cadastroCompleto ?: true,
            )
        )
    }

    override suspend fun entrarComEmail(email: String, senha: String) = traduzindoErros {
        try {
            supabase.auth.signInWith(Email) {
                this.email = email.trim()
                password = senha
            }
        } catch (e: RestException) {
            if (e.message.orEmpty().contains("invalid", ignoreCase = true)) {
                throw ErroAmigavel("E-mail ou senha incorretos.", e)
            }
            throw e
        }
    }

    override suspend fun cadastrarComEmail(
        nome: String, email: String, senha: String, tipo: TipoConta,
    ): Boolean = traduzindoErros {
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            password = senha
            // Lido pelo trigger criar_perfil_usuario() no banco.
            data = buildJsonObject {
                put("nome", nome.trim())
                put("tipo", tipo.codigo)
            }
        }
        // Se o projeto exige confirmação de e-mail, não há sessão ainda.
        supabase.auth.currentSessionOrNull() != null
    }

    override suspend fun concluirCadastro(nome: String, tipo: TipoConta) = traduzindoErros {
        val id = supabase.auth.currentUserOrNull()?.id ?: throw ErroAmigavel(ErrosServidor.traduzir("nao_autenticado"))
        supabase.from("usuarios").update(
            buildJsonObject {
                put("nome", nome.trim())
                put("tipo", tipo.codigo)
                put("cadastro_completo", true)
            }
        ) { filter { eq("id", id) } }
        carregarPerfil()
    }

    override suspend fun sair() = traduzindoErros {
        supabase.auth.signOut()
    }

    @Composable
    override fun lembrarLoginGoogle(aoFalhar: (String) -> Unit): (() -> Unit)? {
        if (!googleConfigurado) return null
        val estadoGoogle = supabase.composeAuth.rememberSignInWithGoogle(
            onResult = { resultado ->
                when (resultado) {
                    is NativeSignInResult.Success, NativeSignInResult.ClosedByUser -> Unit
                    is NativeSignInResult.NetworkError -> aoFalhar("Sem conexão com a internet. Tente de novo.")
                    is NativeSignInResult.Error -> aoFalhar("Não foi possível entrar com o Google.")
                }
            },
        )
        return { estadoGoogle.startFlow() }
    }
}

/** Formato do retorno da função `buscar_cotacoes` do banco. */
@Serializable
private data class CotacaoDto(
    val id: String,
    val produto: String,
    @SerialName("preco_centavos") val precoCentavos: Long,
    val validade: String? = null,
    val obs: String? = null,
    val fonte: String,
    @SerialName("loja_id") val lojaId: String? = null,
    @SerialName("pdv_id") val pdvId: String? = null,
    @SerialName("pdv_nome") val pdvNome: String,
    @SerialName("loja_nome") val lojaNome: String? = null,
    val endereco: String? = null,
    val telefone: String? = null,
    val site: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("data_nf") val dataNf: String? = null,
) {
    fun paraCotacao() = Cotacao(
        id = id,
        produto = produto,
        precoCentavos = precoCentavos,
        validade = validade?.let(LocalDate::parse),
        obs = obs,
        fonte = Fonte.doCodigo(fonte),
        lojaId = lojaId,
        pdvId = pdvId,
        pdvNome = pdvNome,
        lojaNome = lojaNome,
        endereco = endereco,
        telefone = telefone,
        site = site,
        local = if (latitude != null && longitude != null) PontoGeo(latitude, longitude) else null,
        criadoEm = lerInstante(createdAt),
        dataNf = dataNf?.let(LocalDate::parse),
    )

    private fun lerInstante(texto: String): Instant =
        runCatching { OffsetDateTime.parse(texto).toInstant() }.getOrElse { Instant.parse(texto) }
}

class SupabaseCotacoesRepositorio(private val supabase: SupabaseClient) : CotacoesRepositorio {
    override suspend fun buscar(termo: String?, posicao: PontoGeo?): List<Cotacao> = traduzindoErros {
        supabase.postgrest.rpc(
            "buscar_cotacoes",
            buildJsonObject {
                put("p_termo", termo?.trim()?.takeIf { it.isNotEmpty() })
                put("p_lat", posicao?.latitude)
                put("p_lng", posicao?.longitude)
                put("p_limite", 100)
            },
        ).decodeList<CotacaoDto>().map { it.paraCotacao() }
    }
}

@Serializable
private data class LojaDoCnpjDto(
    @SerialName("loja_id") val lojaId: String,
    @SerialName("pdv_nome") val pdvNome: String,
    @SerialName("loja_nome") val lojaNome: String? = null,
    val endereco: String,
)

@Serializable
private data class RespostaEnvioNf(val itens: Int)

class SupabaseNotaFiscalRepositorio(private val supabase: SupabaseClient) : NotaFiscalRepositorio {
    override suspend fun lojasDoCnpj(cnpj: String): List<LojaResumo> = traduzindoErros {
        supabase.postgrest.rpc("lojas_do_cnpj", buildJsonObject { put("p_cnpj", cnpj) })
            .decodeList<LojaDoCnpjDto>()
            .map { LojaResumo(it.lojaId, it.pdvNome, it.lojaNome, it.endereco) }
    }

    override suspend fun enviar(rascunho: RascunhoNf): Int = traduzindoErros {
        supabase.postgrest.rpc(
            "enviar_nota_fiscal",
            buildJsonObject {
                put("p_chave_acesso", rascunho.chaveAcesso.filter { it.isDigit() }.ifEmpty { null })
                put("p_loja_id", rascunho.lojaId)
                put("p_pdv_nome", rascunho.pdvNome.trim().ifEmpty { null })
                put("p_pdv_endereco", rascunho.pdvEndereco.trim().ifEmpty { null })
                put("p_data_nf", rascunho.dataNf.toString())
                put("p_itens", buildJsonArray {
                    rascunho.itensParaEnvio().forEach { item ->
                        addJsonObject {
                            put("produto", item.produto)
                            put("preco_centavos", item.precoCentavos)
                        }
                    }
                })
            },
        ).decodeAs<RespostaEnvioNf>().itens
    }
}

