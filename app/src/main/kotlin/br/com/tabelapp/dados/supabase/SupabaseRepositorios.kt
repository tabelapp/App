package br.com.tabelapp.dados.supabase

import androidx.compose.runtime.Composable
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.ErrosServidor
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.dados.ConsultaCnpj
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
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
import io.github.jan.supabase.storage.storage
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

private fun instante(texto: String): Instant =
    runCatching { OffsetDateTime.parse(texto).toInstant() }.getOrElse { Instant.parse(texto) }

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

    override suspend fun enviarCodigoSenha(email: String) = traduzindoErros {
        // O modelo "Reset Password" do Supabase precisa mostrar o código ({{ .Token }}) — ver README.
        supabase.auth.resetPasswordForEmail(email.trim(), redirectUrl = null)
    }

    override suspend fun redefinirSenha(email: String, codigo: String, novaSenha: String) = traduzindoErros {
        // Ao conferir o código a pessoa já entra; a troca da senha não pode ser interrompida
        // quando a tela de login sai de cena.
        withContext(NonCancellable) {
            try {
                supabase.auth.verifyEmailOtp(OtpType.Email.RECOVERY, email = email.trim(), token = codigo.trim())
            } catch (e: RestException) {
                throw ErroAmigavel("Código inválido ou vencido. Peça um novo código.", e)
            }
            supabase.auth.updateUser { password = novaSenha }
        }
        Unit
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

    override suspend fun banners(termo: String?, posicao: PontoGeo?): List<Banner> = traduzindoErros {
        supabase.postgrest.rpc(
            "promocoes_para_busca",
            buildJsonObject {
                put("p_termo", termo?.trim()?.takeIf { it.isNotEmpty() })
                put("p_lat", posicao?.latitude)
                put("p_lng", posicao?.longitude)
                put("p_limite", 3)
            },
        ).decodeList<BannerDto>().map { d ->
            Banner(
                id = d.id, pdvNome = d.pdvNome, titulo = d.titulo, descricao = d.descricao,
                // Artes ficam no bucket público "promocoes".
                imagemUrl = d.artePath?.let { supabase.storage.from("promocoes").publicUrl(it) },
            )
        }
    }

    override suspend fun registrarVisualizacao(bannerId: String) {
        runCatching {
            supabase.postgrest.rpc("registrar_visualizacao_promocao", buildJsonObject { put("p_promocao_id", bannerId) })
        }
    }
}

@Serializable
private data class BannerDto(
    val id: String,
    @SerialName("pdv_nome") val pdvNome: String,
    val titulo: String,
    val descricao: String? = null,
    @SerialName("arte_path") val artePath: String? = null,
)

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

    override suspend fun sugerirNomePdv(cnpj: String, nome: String): Boolean = traduzindoErros {
        supabase.postgrest.rpc("sugerir_nome_pdv", buildJsonObject {
            put("p_cnpj", cnpj)
            put("p_nome", nome.trim())
        }).decodeAs<String>() == "confirmado"
    }
}


@Serializable
private data class MeuPdvDto(
    val id: String,
    val cnpj: String,
    @SerialName("razao_social") val razaoSocial: String? = null,
    @SerialName("nome_fantasia") val nomeFantasia: String,
    val status: String,
    @SerialName("motivo_rejeicao") val motivoRejeicao: String? = null,
    @SerialName("cnpj_conferido_no_alvara") val cnpjConferidoNoAlvara: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("modo_rede") val modoRede: Boolean = true,
)

@Serializable
private data class LojaPdvDto(val id: String, val nome: String? = null, val endereco: String, val telefone: String? = null)

@Serializable
private data class PrecoPdvDto(
    val id: String,
    @SerialName("loja_id") val lojaId: String,
    val produto: String,
    @SerialName("preco_centavos") val precoCentavos: Long,
    val validade: String,
    val obs: String? = null,
)

@Serializable
private data class CotaDto(
    @SerialName("gratis_usadas") val gratisUsadas: Int,
    @SerialName("saldo_pacotes") val saldoPacotes: Int = 0,
)

@Serializable
private data class NomeSugeridoDto(
    val cnpj: String,
    val nome: String,
    val sugestoes: Int,
    @SerialName("razao_social") val razaoSocial: String? = null,
    val endereco: String? = null,
)

@Serializable
private data class PdvPendenteDto(
    val id: String,
    val cnpj: String,
    @SerialName("razao_social") val razaoSocial: String? = null,
    @SerialName("nome_fantasia") val nomeFantasia: String,
    val endereco: String? = null,
    val telefone: String? = null,
    @SerialName("alvara_path") val alvaraPath: String? = null,
    @SerialName("cnpj_conferido_no_alvara") val cnpjConferidoNoAlvara: Boolean = false,
    @SerialName("dados_receita") val dadosReceita: JsonObject? = null,
    @SerialName("dono_nome") val donoNome: String? = null,
    @SerialName("dono_email") val donoEmail: String? = null,
    @SerialName("created_at") val createdAt: String,
)

class SupabasePdvRepositorio(
    private val supabase: SupabaseClient,
    private val consulta: ConsultaCnpj,
) : PdvRepositorio {

    override suspend fun consultarCnpj(cnpj: String): DadosReceita? = consulta.consultar(cnpj)

    override suspend fun meusPdvs(): List<MeuPdv> = traduzindoErros {
        supabase.postgrest.rpc("meus_pdvs").decodeList<MeuPdvDto>().map { d ->
            MeuPdv(
                id = d.id, cnpj = d.cnpj, razaoSocial = d.razaoSocial, nomeFantasia = d.nomeFantasia,
                status = StatusPdv.doCodigo(d.status), motivoRejeicao = d.motivoRejeicao,
                cnpjConferidoNoAlvara = d.cnpjConferidoNoAlvara, enviadoEm = instante(d.createdAt),
                modoRede = d.modoRede,
            )
        }
    }

    override suspend fun lojas(pdvId: String): List<LojaPdv> = traduzindoErros {
        supabase.postgrest.rpc("minhas_lojas", buildJsonObject { put("p_pdv_id", pdvId) })
            .decodeList<LojaPdvDto>()
            .map { LojaPdv(it.id, it.nome, it.endereco, it.telefone) }
    }

    override suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv> = traduzindoErros {
        supabase.postgrest.rpc("meus_precos", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
        }).decodeList<PrecoPdvDto>().map {
            PrecoPdv(it.id, it.lojaId, it.produto, it.precoCentavos, LocalDate.parse(it.validade), it.obs)
        }
    }

    override suspend fun cota(pdvId: String, lojaId: String): SaldoCota = traduzindoErros {
        val c = supabase.postgrest.rpc("cota_status", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
        }).decodeList<CotaDto>().firstOrNull() ?: CotaDto(0, 0)
        SaldoCota(gratisUsadas = c.gratisUsadas, saldoPacotes = c.saldoPacotes)
    }

    override suspend fun salvarPreco(
        pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?,
    ): Int = traduzindoErros {
        val resposta = supabase.postgrest.rpc("pdv_salvar_precos", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
            put("p_itens", buildJsonArray {
                addJsonObject {
                    put("produto", produto.trim())
                    put("preco_centavos", precoCentavos)
                    validade?.let { put("validade", it.toString()) }
                    obs?.trim()?.ifEmpty { null }?.let { put("obs", it) }
                }
            })
        }).decodeAs<JsonObject>()
        (resposta["operacoes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    override suspend fun excluirPreco(precoId: String) = traduzindoErros {
        supabase.postgrest.rpc("pdv_excluir_item", buildJsonObject { put("p_cotacao_id", precoId) })
        Unit
    }

    override suspend fun cadastrar(
        dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray, cnpjNoAlvara: Boolean,
    ) = traduzindoErros {
        val uid = supabase.auth.currentUserOrNull()?.id ?: throw ErroAmigavel(ErrosServidor.traduzir("nao_autenticado"))
        // A regra do bucket exige a pasta do próprio usuário: alvaras/<id>/<aleatório>.jpg
        val caminho = "$uid/${java.util.UUID.randomUUID()}.jpg"
        supabase.storage.from("alvaras").upload(caminho, alvaraJpeg) { upsert = false }
        supabase.postgrest.rpc(
            "cadastrar_pdv",
            buildJsonObject {
                put("p_cnpj", dados.cnpj.filter { it.isDigit() })
                put("p_nome_fantasia", dados.nomeFantasia.trim())
                put("p_razao_social", dados.razaoSocial?.trim())
                put("p_endereco", dados.endereco.trim())
                put("p_bairro", dados.bairro.trim())
                put("p_cidade", dados.cidade.trim())
                put("p_uf", dados.uf.trim())
                put("p_cep", dados.cep.trim())
                put("p_telefone", dados.telefone.trim())
                put("p_alvara_path", caminho)
                put("p_cnpj_no_alvara", cnpjNoAlvara)
                put("p_dados_receita", receita?.let { r ->
                    buildJsonObject {
                        put("razao_social", r.razaoSocial)
                        put("nome_fantasia", r.nomeFantasia)
                        put("situacao", r.situacao)
                        put("endereco", r.endereco)
                        put("bairro", r.bairro)
                        put("cidade", r.cidade)
                        put("uf", r.uf)
                        put("cep", r.cep)
                        put("telefone", r.telefone)
                        put("atividade", r.atividade)
                    }
                } ?: JsonNull)
            },
        )
        Unit
    }

    override suspend fun pendentes(): List<PdvPendente> = traduzindoErros {
        supabase.postgrest.rpc("pdvs_pendentes").decodeList<PdvPendenteDto>().map { d ->
            fun receita(campo: String) = (d.dadosReceita?.get(campo) as? JsonPrimitive)
                ?.takeUnless { it is JsonNull }?.content
            PdvPendente(
                id = d.id, cnpj = d.cnpj, razaoSocial = d.razaoSocial, nomeFantasia = d.nomeFantasia,
                endereco = d.endereco, telefone = d.telefone, alvaraPath = d.alvaraPath,
                cnpjConferidoNoAlvara = d.cnpjConferidoNoAlvara,
                situacaoReceita = receita("situacao"),
                razaoSocialReceita = receita("razao_social"),
                enderecoReceita = listOfNotNull(receita("endereco"), receita("bairro"), receita("cidade"), receita("uf"))
                    .joinToString(", ").ifEmpty { null },
                donoNome = d.donoNome, donoEmail = d.donoEmail, enviadoEm = instante(d.createdAt),
            )
        }
    }

    override suspend fun fotoAlvara(caminho: String): ByteArray = traduzindoErros {
        supabase.storage.from("alvaras").downloadAuthenticated(caminho)
    }

    override suspend fun aprovar(pdvId: String) = traduzindoErros {
        supabase.postgrest.rpc("aprovar_pdv", buildJsonObject { put("p_pdv_id", pdvId) })
        Unit
    }

    override suspend fun nomesSugeridos(): List<NomeSugerido> = traduzindoErros {
        supabase.postgrest.rpc("nomes_sugeridos_pendentes").decodeList<NomeSugeridoDto>().map {
            NomeSugerido(it.cnpj, it.nome, it.sugestoes, it.razaoSocial, it.endereco)
        }
    }

    override suspend fun decidirNome(cnpj: String, nome: String, aprovar: Boolean) = traduzindoErros {
        supabase.postgrest.rpc("decidir_nome_pdv", buildJsonObject {
            put("p_cnpj", cnpj)
            put("p_nome", nome)
            put("p_aprovar", aprovar)
        })
        Unit
    }

    override suspend fun rejeitar(pdvId: String, motivo: String) = traduzindoErros {
        supabase.postgrest.rpc("rejeitar_pdv", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_motivo", motivo.trim())
        })
        Unit
    }
}

@Serializable
private data class ListaDto(val id: String, val nome: String, val itens: Int = 0, @SerialName("updated_at") val updatedAt: String)

@Serializable
private data class ItemListaDto(val id: String, val produto: String, val quantidade: Double)

@Serializable
private data class SugestaoDto(
    val produto: String,
    @SerialName("menor_preco_centavos") val menorPrecoCentavos: Long,
    val lugares: Int,
)

class SupabaseListasRepositorio(private val supabase: SupabaseClient) : ListasRepositorio {
    override suspend fun listas(): List<ListaResumo> = traduzindoErros {
        supabase.postgrest.rpc("minhas_listas").decodeList<ListaDto>()
            .map { ListaResumo(it.id, it.nome, it.itens, instante(it.updatedAt)) }
    }

    override suspend fun criar(nome: String): String = traduzindoErros {
        supabase.postgrest.rpc("criar_lista", buildJsonObject { put("p_nome", nome) }).decodeAs<String>()
    }

    override suspend fun renomear(listaId: String, nome: String) = traduzindoErros {
        supabase.postgrest.rpc("renomear_lista", buildJsonObject {
            put("p_lista_id", listaId)
            put("p_nome", nome)
        })
        Unit
    }

    override suspend fun excluir(listaId: String) = traduzindoErros {
        supabase.postgrest.rpc("excluir_lista", buildJsonObject { put("p_lista_id", listaId) })
        Unit
    }

    override suspend fun itens(listaId: String): List<ItemSalvo> = traduzindoErros {
        supabase.postgrest.rpc("itens_da_lista", buildJsonObject { put("p_lista_id", listaId) })
            .decodeList<ItemListaDto>().map { ItemSalvo(it.id, it.produto, it.quantidade) }
    }

    override suspend fun adicionar(listaId: String, produto: String, quantidade: Double) = traduzindoErros {
        supabase.postgrest.rpc("adicionar_item_lista", buildJsonObject {
            put("p_lista_id", listaId)
            put("p_produto", produto)
            put("p_quantidade", quantidade)
        })
        Unit
    }

    override suspend fun alterarQuantidade(itemId: String, quantidade: Double) = traduzindoErros {
        supabase.postgrest.rpc("alterar_quantidade_item", buildJsonObject {
            put("p_item_id", itemId)
            put("p_quantidade", quantidade)
        })
        Unit
    }

    override suspend fun remover(itemId: String) = traduzindoErros {
        supabase.postgrest.rpc("remover_item_lista", buildJsonObject { put("p_item_id", itemId) })
        Unit
    }

    override suspend fun sugerir(termo: String): List<SugestaoProduto> = traduzindoErros {
        supabase.postgrest.rpc("sugerir_produtos", buildJsonObject { put("p_termo", termo) })
            .decodeList<SugestaoDto>().map { SugestaoProduto(it.produto, it.menorPrecoCentavos, it.lugares) }
    }
}
