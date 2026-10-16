package br.com.tabelapp.dados.supabase

import androidx.compose.runtime.Composable
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.ConfigConfianca
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.ErrosServidor
import br.com.tabelapp.core.EstadoItem
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.ItemFora
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.LojaSemLocal
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.OrigemLimite
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PdvAdmin
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PlanoPdvAdmin
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoDenunciado
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.TipoEmpresa
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.ConsultaCnpj
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import br.com.tabelapp.erros.Erros
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
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    } catch (e: ErroAmigavel) {
        throw e
    } catch (e: RestException) {
        throw Erros.doServidor(e, e.statusCode)
    } catch (e: Exception) {
        // Rede, instabilidade ou erro inesperado: o usuário vê só a mensagem calma.
        throw Erros.doServidor(e)
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

    private val _sessaoExpirada = MutableStateFlow(false)
    /** "Sair" ou "Excluir conta": não é sessão vencida. */
    @Volatile private var saindoDeProposito = false
    override val sessaoExpirada: StateFlow<Boolean> = _sessaoExpirada.asStateFlow()

    init {
        escopo.launch {
            supabase.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _sessaoExpirada.value = false
                        saindoDeProposito = false
                        carregarPerfil()
                    }
                    is SessionStatus.NotAuthenticated -> {
                        // Estava logado e não tocou em "Sair": a sessão venceu (ex.: senha trocada, token inválido).
                        if (_estado.value is EstadoSessao.Logado && !status.isSignOut && !saindoDeProposito) {
                            _sessaoExpirada.value = true
                            Erros.registrar("sessao", "sessão terminou sem sair")
                        }
                        _estado.value = EstadoSessao.Deslogado
                    }
                    // Ao voltar de outro app (ex.: leitor de QR Code) a biblioteca reinicia a sessão:
                    // quem já estava logado continua na tela em que estava (não volta ao "carregando").
                    SessionStatus.Initializing ->
                        if (_estado.value !is EstadoSessao.Logado) _estado.value = EstadoSessao.Carregando
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
        saindoDeProposito = true
        supabase.auth.signOut()
    }

    override suspend fun atualizarNome(nome: String) = traduzindoErros {
        supabase.postgrest.rpc("atualizar_meu_nome", buildJsonObject { put("p_nome", nome.trim()) })
        carregarPerfil()
    }

    override suspend fun registrarAceiteTermos(versao: String) = traduzindoErros {
        supabase.postgrest.rpc("registrar_aceite_termos", buildJsonObject { put("p_versao", versao) })
        Unit
    }

    override suspend fun excluirConta() = traduzindoErros {
        supabase.postgrest.rpc("excluir_minha_conta")
        // A conta não existe mais no servidor: só limpa a sessão guardada no celular.
        saindoDeProposito = true
        runCatching { supabase.auth.clearSession() }
        _estado.value = EstadoSessao.Deslogado
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
    val unidade: String? = null,
    @SerialName("a_partir_de") val aPartirDe: Boolean = false,
    @SerialName("parte_de_combo") val parteDeCombo: Boolean = false,
    val denuncias: Int = 0,
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
        endereco = endereco?.takeIf { it.isNotBlank() },
        telefone = telefone,
        site = site,
        local = if (latitude != null && longitude != null) PontoGeo(latitude, longitude) else null,
        criadoEm = lerInstante(createdAt),
        dataNf = dataNf?.let(LocalDate::parse),
        unidade = Unidade.deCodigo(unidade),
        aPartirDe = aPartirDe,
        parteDeCombo = parteDeCombo,
        denuncias = denuncias,
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
                put("p_limite", 6)
            },
        ).decodeList<BannerDto>().map { d ->
            Banner(
                id = d.id, pdvNome = d.pdvNome, titulo = d.titulo, descricao = d.descricao,
                // Artes ficam no bucket público "promocoes".
                imagemUrl = d.artePath?.let { supabase.storage.from("promocoes").publicUrl(it) },
                link = d.link,
            )
        }
    }

    override suspend fun registrarVisualizacao(bannerId: String) {
        runCatching {
            supabase.postgrest.rpc("registrar_visualizacao_promocao", buildJsonObject { put("p_promocao_id", bannerId) })
        }
    }

    override suspend fun configConfianca(): ConfigConfianca {
        val padrao = ConfigConfianca()
        val valores = runCatching {
            supabase.from("configuracao_app").select().decodeList<ConfigDto>().associate { it.chave to it.valor }
        }.getOrElse { return padrao }
        fun numero(chave: String) = (valores[chave] as? JsonPrimitive)?.content?.toDoubleOrNull()
        return ConfigConfianca(
            fracaoAtipico = numero("preco_atipico_fracao") ?: padrao.fracaoAtipico,
            minResultados = numero("preco_atipico_min_resultados")?.toInt() ?: padrao.minResultados,
            toleranciaPdv = numero("selo_tolerancia_pdv") ?: padrao.toleranciaPdv,
            limiteDenuncias = numero("denuncias_limite")?.toInt() ?: padrao.limiteDenuncias,
        )
    }

    override suspend fun denunciar(cotacaoId: String, motivo: String?): Boolean = traduzindoErros {
        supabase.postgrest.rpc("denunciar_preco", buildJsonObject {
            put("p_cotacao_id", cotacaoId)
            put("p_motivo", motivo)
        }).decodeAs<Boolean>()
    }
}

@Serializable
private data class ConfigDto(val chave: String, val valor: JsonElement)

@Serializable
private data class BannerDto(
    val id: String,
    @SerialName("pdv_nome") val pdvNome: String,
    val titulo: String,
    val descricao: String? = null,
    @SerialName("arte_path") val artePath: String? = null,
    val link: String? = null,
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
                            if (item.parteDeCombo) put("parte_de_combo", true)
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
    val site: String? = null,
    @SerialName("motivo_suspensao") val motivoSuspensao: String? = null,
    val tipo: String? = null,
)

@Serializable
private data class LojaPdvDto(
    val id: String,
    val nome: String? = null,
    val endereco: String,
    val telefone: String? = null,
    val logradouro: String? = null,
    val bairro: String? = null,
    val cidade: String? = null,
    val uf: String? = null,
    val whatsapp: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("coordenadas_origem") val coordenadasOrigem: String? = null,
)

@Serializable
private data class LojaSemLocalDto(val id: String, val nome: String? = null, val endereco: String)

@Serializable
private data class ImportacaoDto(
    val criados: Int = 0,
    val aumentados: Int = 0,
    val diminuidos: Int = 0,
    val inalterados: Int = 0,
    @SerialName("fora_do_limite") val foraDoLimite: Int = 0,
    @SerialName("itens_fora") val itensFora: List<ItemForaDto> = emptyList(),
    val limite: Int = 0,
    @SerialName("plano_nome") val planoNome: String = "",
) {
    fun paraResultado() = ResultadoImportacao(
        criados, aumentados, diminuidos, inalterados, foraDoLimite,
        itensFora.map { ItemFora(it.linha, it.produto) }, limite, planoNome,
    )
}

@Serializable
private data class PromocaoDto(
    val id: String,
    val titulo: String,
    val descricao: String? = null,
    val link: String? = null,
    @SerialName("arte_path") val artePath: String? = null,
    @SerialName("palavras_chave") val palavrasChave: List<String> = emptyList(),
    val status: String,
    @SerialName("visualizacoes_contratadas") val contratadas: Int = 0,
    @SerialName("visualizacoes_exibidas") val exibidas: Int = 0,
    @SerialName("pagamento_pendente_id") val pagamentoPendenteId: String? = null,
    @SerialName("valor_pendente_centavos") val valorPendente: Long? = null,
    @SerialName("visualizacoes_pendentes") val visualizacoesPendentes: Int? = null,
)

@Serializable
private data class PagamentoPendenteDto(
    val id: String,
    val tipo: String,
    @SerialName("pdv_nome") val pdvNome: String,
    val descricao: String,
    val quantidade: Int,
    @SerialName("valor_centavos") val valorCentavos: Long,
    @SerialName("dono_nome") val donoNome: String? = null,
    @SerialName("dono_email") val donoEmail: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class PrecoPdvDto(
    val id: String,
    @SerialName("loja_id") val lojaId: String,
    val produto: String,
    @SerialName("preco_centavos") val precoCentavos: Long,
    val validade: String,
    val obs: String? = null,
    val unidade: String? = null,
    @SerialName("a_partir_de") val aPartirDe: Boolean = false,
    val estado: String? = null,
)

@Serializable
private data class StatusCatalogoDto(
    val plano: String,
    @SerialName("plano_nome") val planoNome: String,
    @SerialName("limite_plano") val limitePlano: Int,
    @SerialName("plano_efetivo") val planoEfetivo: String,
    @SerialName("plano_efetivo_nome") val planoEfetivoNome: String,
    val limite: Int,
    @SerialName("itens_ativos") val itensAtivos: Int,
    val disponiveis: Int,
    @SerialName("plano_ate") val planoAte: String? = null,
    @SerialName("teste_ate") val testeAte: String? = null,
    val origem: String? = null,
    @SerialName("em_tolerancia") val emTolerancia: Boolean = false,
    val fim: String? = null,
    @SerialName("tolerancia_ate") val toleranciaAte: String? = null,
    @SerialName("itens_publicados") val itensPublicados: Int? = null,
    @SerialName("pausados_limite") val pausadosLimite: Int = 0,
    @SerialName("pausados_pdv") val pausadosPdv: Int = 0,
    @SerialName("limite_gratis") val limiteGratis: Int = 5,
    @SerialName("dias_tolerancia") val diasTolerancia: Int = 7,
    @SerialName("aviso_dias") val avisoDias: Int = 7,
    @SerialName("whatsapp_contato") val whatsappContato: String? = null,
)

@Serializable
private data class PlanoPdvAdminDto(
    val id: String,
    @SerialName("nome_fantasia") val nomeFantasia: String,
    val cnpj: String,
    val plano: String,
    @SerialName("plano_nome") val planoNome: String,
    @SerialName("plano_ate") val planoAte: String? = null,
    @SerialName("teste_ate") val testeAte: String? = null,
    val limite: Int,
    val origem: String? = null,
    @SerialName("em_tolerancia") val emTolerancia: Boolean = false,
    @SerialName("itens_ativos") val itensAtivos: Int = 0,
)

@Serializable
private data class PlanoDto(
    val codigo: String,
    val nome: String,
    @SerialName("limite_itens") val limiteItens: Int,
    @SerialName("preco_referencia_centavos") val precoReferenciaCentavos: Long? = null,
    val ordem: Int = 0,
)

@Serializable
private data class ItemForaDto(val linha: Int, val produto: String)

@Serializable
private data class NomeSugeridoDto(
    val cnpj: String,
    val nome: String,
    val sugestoes: Int,
    @SerialName("razao_social") val razaoSocial: String? = null,
    val endereco: String? = null,
)

@Serializable
private data class DenunciaDto(
    @SerialName("cotacao_id") val cotacaoId: String,
    val produto: String,
    @SerialName("preco_centavos") val precoCentavos: Long,
    val fonte: String,
    @SerialName("parte_de_combo") val parteDeCombo: Boolean = false,
    @SerialName("pdv_nome") val pdvNome: String? = null,
    val denuncias: Int,
    @SerialName("combo_ou_promocao") val comboOuPromocao: Int = 0,
    @SerialName("preco_mudou") val precoMudou: Int = 0,
    @SerialName("produto_errado") val produtoErrado: Int = 0,
    val outros: Int = 0,
)

@Serializable
private data class PdvAdminDto(
    val id: String,
    @SerialName("nome_fantasia") val nomeFantasia: String,
    val cnpj: String,
    val status: String,
    @SerialName("motivo_suspensao") val motivoSuspensao: String? = null,
    val cidade: String? = null,
    @SerialName("dono_nome") val donoNome: String? = null,
    @SerialName("dono_email") val donoEmail: String? = null,
    @SerialName("itens_publicados") val itensPublicados: Int = 0,
    val tipo: String? = null,
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
    val tipo: String? = null,
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
                modoRede = d.modoRede, site = d.site, motivoSuspensao = d.motivoSuspensao,
                tipo = TipoEmpresa.doCodigo(d.tipo),
            )
        }
    }

    override suspend fun lojas(pdvId: String): List<LojaPdv> = traduzindoErros {
        supabase.postgrest.rpc("minhas_lojas", buildJsonObject { put("p_pdv_id", pdvId) })
            .decodeList<LojaPdvDto>()
            .map {
                LojaPdv(
                    it.id, it.nome, it.endereco, it.telefone, it.logradouro.orEmpty(), it.bairro, it.cidade, it.uf, it.whatsapp,
                    local = if (it.latitude != null && it.longitude != null) PontoGeo(it.latitude, it.longitude) else null,
                    localPeloAparelho = it.coordenadasOrigem == "aparelho",
                )
            }
    }

    override suspend fun definirCoordenadas(lojaId: String, ponto: PontoGeo, peloAparelho: Boolean) = traduzindoErros {
        supabase.postgrest.rpc("definir_coordenadas_loja", buildJsonObject {
            put("p_loja_id", lojaId)
            put("p_lat", ponto.latitude)
            put("p_lng", ponto.longitude)
            put("p_origem", if (peloAparelho) "aparelho" else "endereco")
        })
        Unit
    }

    override suspend fun lojasSemCoordenadas(): List<LojaSemLocal> = traduzindoErros {
        supabase.postgrest.rpc("admin_lojas_sem_coordenadas").decodeList<LojaSemLocalDto>().map {
            LojaSemLocal(it.id, it.nome, it.endereco)
        }
    }

    override suspend fun atualizarPdv(pdvId: String, nomeFantasia: String, site: String?) = traduzindoErros {
        supabase.postgrest.rpc("atualizar_pdv", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_nome_fantasia", nomeFantasia.trim())
            put("p_site", site?.trim())
        })
        Unit
    }

    override suspend fun atualizarLoja(loja: LojaPdv) = traduzindoErros {
        supabase.postgrest.rpc("atualizar_loja", buildJsonObject {
            put("p_loja_id", loja.id)
            put("p_nome", loja.nome)
            put("p_endereco", loja.logradouro)
            put("p_bairro", loja.bairro)
            put("p_cidade", loja.cidade)
            put("p_telefone", loja.telefone)
            put("p_whatsapp", loja.whatsapp)
        })
        Unit
    }

    private fun itensPlanilha(linhas: List<Planilha.Linha>) = buildJsonArray {
        linhas.forEach { l ->
            addJsonObject {
                put("produto", l.produto)
                put("preco_centavos", l.precoCentavos)
                put("validade", l.validade.toString())
                l.obs?.let { put("obs", it) }
            }
        }
    }

    private suspend fun planilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>, simular: Boolean) =
        traduzindoErros {
            supabase.postgrest.rpc("pdv_salvar_precos", buildJsonObject {
                put("p_pdv_id", pdvId)
                put("p_loja_id", lojaId)
                put("p_itens", itensPlanilha(linhas))
                put("p_fonte", "pdv_excel")
                put("p_simular", simular)
            }).decodeAs<ImportacaoDto>().paraResultado()
        }

    override suspend fun simularPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>) =
        planilha(pdvId, lojaId, linhas, simular = true)

    override suspend fun importarPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>) =
        planilha(pdvId, lojaId, linhas, simular = false)

    override suspend fun promocoes(pdvId: String): List<PromocaoPdv> = traduzindoErros {
        supabase.postgrest.rpc("minhas_promocoes", buildJsonObject { put("p_pdv_id", pdvId) })
            .decodeList<PromocaoDto>().map {
                PromocaoPdv(it.id, it.titulo, it.descricao, it.link, it.artePath, it.palavrasChave, it.status,
                    it.contratadas, it.exibidas, it.pagamentoPendenteId, it.valorPendente, it.visualizacoesPendentes)
            }
    }

    override suspend fun enviarArte(pdvId: String, jpeg: ByteArray): String = traduzindoErros {
        // A regra do bucket público "promocoes" exige a pasta do próprio PDV.
        val caminho = "$pdvId/${java.util.UUID.randomUUID()}.jpg"
        supabase.storage.from("promocoes").upload(caminho, jpeg) { upsert = false }
        caminho
    }

    override suspend fun adminCriarBanner(
        pdvId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>, visualizacoes: Int,
    ): String = traduzindoErros {
        supabase.postgrest.rpc("admin_criar_banner", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_titulo", titulo.trim())
            put("p_descricao", descricao?.trim())
            put("p_link", link?.trim())
            put("p_palavras_chave", buildJsonArray { palavrasChave.forEach { add(JsonPrimitive(it)) } })
            put("p_visualizacoes", visualizacoes)
        }).decodeAs<String>()
    }

    override suspend fun excluirPromocao(promocaoId: String): Boolean = traduzindoErros {
        supabase.postgrest.rpc("encerrar_promocao", buildJsonObject { put("p_promocao_id", promocaoId) })
            .decodeAs<String>() == "excluida"
    }

    override suspend fun editarPromocao(
        promocaoId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>,
        trocarArte: Boolean, artePath: String?,
    ) = traduzindoErros {
        supabase.postgrest.rpc("editar_promocao", buildJsonObject {
            put("p_promocao_id", promocaoId)
            put("p_titulo", titulo.trim())
            put("p_descricao", descricao?.trim())
            put("p_link", link?.trim())
            put("p_palavras_chave", buildJsonArray { palavrasChave.forEach { add(JsonPrimitive(it)) } })
            put("p_trocar_arte", trocarArte)
            put("p_arte_path", artePath)
        })
        Unit
    }

    override suspend fun pagamentosPendentes(): List<PagamentoPendente> = traduzindoErros {
        supabase.postgrest.rpc("pagamentos_pendentes").decodeList<PagamentoPendenteDto>().map {
            PagamentoPendente(it.id, it.tipo, it.pdvNome, it.descricao, it.quantidade, it.valorCentavos,
                it.donoNome, it.donoEmail, instante(it.createdAt))
        }
    }

    override suspend fun confirmarPagamento(pagamentoId: String) = traduzindoErros {
        supabase.postgrest.rpc("admin_confirmar_pagamento", buildJsonObject { put("p_pagamento_id", pagamentoId) })
        Unit
    }

    override suspend fun cancelarPagamento(pagamentoId: String) = traduzindoErros {
        supabase.postgrest.rpc("admin_cancelar_pagamento", buildJsonObject { put("p_pagamento_id", pagamentoId) })
        Unit
    }

    override suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv> = traduzindoErros {
        supabase.postgrest.rpc("meus_precos", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
        }).decodeList<PrecoPdvDto>().map {
            PrecoPdv(it.id, it.lojaId, it.produto, it.precoCentavos, LocalDate.parse(it.validade), it.obs,
                Unidade.deCodigo(it.unidade), it.aPartirDe, EstadoItem.doCodigo(it.estado))
        }
    }

    override suspend fun statusCatalogo(pdvId: String, lojaId: String): StatusCatalogo = traduzindoErros {
        val d = supabase.postgrest.rpc("status_catalogo", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
        }).decodeList<StatusCatalogoDto>().first()
        StatusCatalogo(
            plano = d.plano, planoNome = d.planoNome, limitePlano = d.limitePlano,
            planoAte = d.planoAte?.let(LocalDate::parse), testeAte = d.testeAte?.let(LocalDate::parse),
            planoEfetivo = d.planoEfetivo, planoEfetivoNome = d.planoEfetivoNome, limite = d.limite,
            origem = OrigemLimite.doCodigo(d.origem), emTolerancia = d.emTolerancia, fim = d.fim?.let(LocalDate::parse),
            toleranciaAte = d.toleranciaAte?.let(LocalDate::parse), itensAtivos = d.itensAtivos, disponiveis = d.disponiveis,
            itensPublicados = d.itensPublicados ?: minOf(d.itensAtivos, d.limite), pausadosLimite = d.pausadosLimite,
            pausadosPdv = d.pausadosPdv, limiteGratis = d.limiteGratis, diasTolerancia = d.diasTolerancia,
            avisoDias = d.avisoDias, whatsappContato = d.whatsappContato,
        )
    }

    override suspend fun planos(): List<Plano> = traduzindoErros {
        supabase.from("planos").select { filter { eq("ativo", true) } }.decodeList<PlanoDto>()
            .map { Plano(it.codigo, it.nome, it.limiteItens, it.precoReferenciaCentavos, it.ordem) }
            .sortedBy { it.ordem }
    }

    override suspend fun pausarItem(precoId: String, pausar: Boolean) = traduzindoErros {
        supabase.postgrest.rpc("pdv_pausar_item", buildJsonObject {
            put("p_cotacao_id", precoId)
            put("p_pausar", pausar)
        })
        Unit
    }

    override suspend fun planosPdvs(busca: String?): List<PlanoPdvAdmin> = traduzindoErros {
        supabase.postgrest.rpc("admin_planos_pdvs", buildJsonObject { put("p_busca", busca?.trim()?.ifEmpty { null }) })
            .decodeList<PlanoPdvAdminDto>().map {
                PlanoPdvAdmin(
                    id = it.id, nomeFantasia = it.nomeFantasia, cnpj = it.cnpj, plano = it.plano, planoNome = it.planoNome,
                    planoAte = it.planoAte?.let(LocalDate::parse), testeAte = it.testeAte?.let(LocalDate::parse),
                    limite = it.limite, origem = OrigemLimite.doCodigo(it.origem), emTolerancia = it.emTolerancia,
                    itensAtivos = it.itensAtivos,
                )
            }
    }

    override suspend fun definirPlano(pdvId: String, plano: String, validade: LocalDate, observacao: String?) =
        traduzindoErros {
            supabase.postgrest.rpc("admin_definir_plano", buildJsonObject {
                put("p_pdv_id", pdvId)
                put("p_plano", plano)
                put("p_validade", validade.toString())
                put("p_observacao", observacao?.trim()?.ifEmpty { null })
            })
            Unit
        }

    override suspend fun cancelarPlano(pdvId: String, observacao: String?) = traduzindoErros {
        supabase.postgrest.rpc("admin_cancelar_plano", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_observacao", observacao?.trim()?.ifEmpty { null })
        })
        Unit
    }

    override suspend fun salvarPreco(
        pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?,
        unidade: Unidade, aPartirDe: Boolean,
    ) = traduzindoErros {
        supabase.postgrest.rpc("pdv_salvar_precos", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_loja_id", lojaId)
            put("p_itens", buildJsonArray {
                addJsonObject {
                    put("produto", produto.trim())
                    put("preco_centavos", precoCentavos)
                    validade?.let { put("validade", it.toString()) }
                    obs?.trim()?.ifEmpty { null }?.let { put("obs", it) }
                    put("unidade", unidade.codigo)
                    put("a_partir_de", aPartirDe)
                }
            })
        })
        Unit
    }

    override suspend fun excluirPreco(precoId: String) = traduzindoErros {
        supabase.postgrest.rpc("pdv_excluir_item", buildJsonObject { put("p_cotacao_id", precoId) })
        Unit
    }

    override suspend fun cadastrar(
        dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray?, cnpjNoAlvara: Boolean,
    ) = traduzindoErros {
        val uid = supabase.auth.currentUserOrNull()?.id ?: throw ErroAmigavel(ErrosServidor.traduzir("nao_autenticado"))
        // A regra do bucket exige a pasta do próprio usuário: alvaras/<id>/<aleatório>.jpg
        // Loja e anunciante: o comprovante do CNPJ é obrigatório.
        val foto = alvaraJpeg ?: throw ErroAmigavel(ErrosServidor.traduzir("alvara_obrigatorio"))
        val caminho = "$uid/${java.util.UUID.randomUUID()}.jpg"
        supabase.storage.from("alvaras").upload(caminho, foto) { upsert = false }
        val dadosReceita = receita?.let { r ->
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
        } ?: JsonNull
        if (dados.tipo == TipoEmpresa.ANUNCIANTE) {
            supabase.postgrest.rpc(
                "cadastrar_anunciante",
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
                    put("p_site", dados.site.trim().ifEmpty { null })
                    put("p_documento_path", caminho)
                    put("p_cnpj_no_documento", cnpjNoAlvara)
                    put("p_dados_receita", dadosReceita)
                },
            )
            return@traduzindoErros
        }
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
                put("p_dados_receita", dadosReceita)
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
                tipo = TipoEmpresa.doCodigo(d.tipo),
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

    override suspend fun precosDenunciados(): List<PrecoDenunciado> = traduzindoErros {
        supabase.from("denuncias_mais_frequentes").select { limit(100) }.decodeList<DenunciaDto>().map {
            PrecoDenunciado(
                cotacaoId = it.cotacaoId, produto = it.produto, precoCentavos = it.precoCentavos,
                pdvNome = it.pdvNome, oficial = it.fonte in setOf("pdv_manual", "pdv_excel"),
                parteDeCombo = it.parteDeCombo, denuncias = it.denuncias, comboOuPromocao = it.comboOuPromocao,
                precoMudou = it.precoMudou, produtoErrado = it.produtoErrado, outros = it.outros,
            )
        }
    }

    override suspend fun removerCotacao(cotacaoId: String, motivo: String?) = traduzindoErros {
        supabase.postgrest.rpc("admin_remover_cotacao", buildJsonObject {
            put("p_cotacao_id", cotacaoId)
            put("p_motivo", motivo?.trim())
        })
        Unit
    }

    override suspend fun manterCotacao(cotacaoId: String) = traduzindoErros {
        supabase.postgrest.rpc("admin_manter_cotacao", buildJsonObject { put("p_cotacao_id", cotacaoId) })
        Unit
    }

    override suspend fun estabelecimentos(busca: String?): List<PdvAdmin> = traduzindoErros {
        supabase.postgrest.rpc("admin_pdvs", buildJsonObject { put("p_busca", busca?.trim()?.ifEmpty { null }) })
            .decodeList<PdvAdminDto>().map {
                PdvAdmin(
                    id = it.id, nomeFantasia = it.nomeFantasia, cnpj = it.cnpj, status = StatusPdv.doCodigo(it.status),
                    motivoSuspensao = it.motivoSuspensao, cidade = it.cidade, donoNome = it.donoNome,
                    donoEmail = it.donoEmail, itensPublicados = it.itensPublicados, tipo = TipoEmpresa.doCodigo(it.tipo),
                )
            }
    }

    override suspend fun suspenderPdv(pdvId: String, motivo: String) = traduzindoErros {
        supabase.postgrest.rpc("admin_suspender_pdv", buildJsonObject {
            put("p_pdv_id", pdvId)
            put("p_motivo", motivo.trim())
        })
        Unit
    }

    override suspend fun reativarPdv(pdvId: String) = traduzindoErros {
        supabase.postgrest.rpc("admin_reativar_pdv", buildJsonObject { put("p_pdv_id", pdvId) })
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
