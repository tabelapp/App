package br.com.tabelapp.dados.supabase

import android.util.Base64
import br.com.tabelapp.core.ItemLidoEncarte
import br.com.tabelapp.core.LeituraEncarte
import br.com.tabelapp.core.LeitorJsonEncarte
import br.com.tabelapp.dados.EncartesAdminRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.LojaEncarte
import br.com.tabelapp.dados.LoteLancado
import br.com.tabelapp.erros.Erros
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Leitor de encartes pelo servidor: a Edge Function `ler-encarte` (chamada direto por HTTPS com o
 * token do Admin) e as funções do banco `admin_lancar_encarte`, `admin_desfazer_lote` e
 * `admin_lojas_encarte`. A chave da API de leitura fica só no Supabase.
 */
class SupabaseEncartesAdmin(
    private val supabase: SupabaseClient,
    private val urlSupabase: String,
    private val chaveAnon: String,
) : EncartesAdminRepositorio {

    override suspend fun ler(arquivo: ByteArray, tipo: String, loja: String): LeituraEncarte {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: throw ErroAmigavel("Sua sessão expirou. Entre novamente.", tipo = br.com.tabelapp.core.TipoErro.SESSAO_EXPIRADA)
        val corpo = buildJsonObject {
            put("imagem_base64", Base64.encodeToString(arquivo, Base64.NO_WRAP))
            put("tipo", tipo)
            put("loja", loja)
        }.toString()
        val (status, resposta) = withContext(Dispatchers.IO) {
            try {
                val conexao = URL(urlSupabase.trimEnd('/') + "/functions/v1/ler-encarte").openConnection() as HttpURLConnection
                conexao.requestMethod = "POST"
                conexao.connectTimeout = 20_000
                conexao.readTimeout = 180_000 // a leitura de um encarte grande pode levar mais de um minuto
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json")
                conexao.setRequestProperty("Authorization", "Bearer $token")
                conexao.setRequestProperty("apikey", chaveAnon)
                conexao.outputStream.use { it.write(corpo.toByteArray()) }
                val codigo = conexao.responseCode
                val texto = (if (codigo in 200..299) conexao.inputStream else conexao.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conexao.disconnect()
                codigo to texto
            } catch (e: Exception) {
                throw Erros.doServidor(e, onde = "ler-encarte")
            }
        }
        if (status !in 200..299) throw ErroAmigavel(mensagemDaFuncao(status), codigo = codigoDaFuncao(status))
        return try {
            LeitorJsonEncarte.ler(resposta)
        } catch (e: LeitorJsonEncarte.JsonInvalido) {
            Erros.registrar("ler-encarte", "resposta fora do formato")
            throw ErroAmigavel(mensagemDaFuncao(502), codigo = "falha_leitura")
        }
    }

    override suspend fun lancar(loja: String, endereco: String, itens: List<ItemLidoEncarte>): LoteLancado = traduzindoErros {
        val r = supabase.postgrest.rpc("admin_lancar_encarte", buildJsonObject {
            put("p_loja_nome", loja)
            put("p_endereco", endereco)
            put("p_itens", buildJsonArray {
                itens.forEach { i ->
                    addJsonObject {
                        put("produto", i.produto)
                        put("preco_centavos", i.precoCentavos)
                        i.precoNormalCentavos?.let { put("preco_normal_centavos", it) }
                        put("preco_app", i.precoApp)
                        put("unidade", i.unidade.codigo)
                        i.validade?.let { put("validade", it.toString()) }
                        i.obs?.let { put("obs", it) }
                    }
                }
            })
        }).decodeAs<JsonObject>()
        LoteLancado(
            loteId = r["lote_id"]!!.jsonPrimitive.content,
            lancados = r["lancados"]!!.jsonPrimitive.int,
            recebidos = r["recebidos"]!!.jsonPrimitive.int,
        )
    }

    override suspend fun desfazer(loteId: String): Int = traduzindoErros {
        supabase.postgrest.rpc("admin_desfazer_lote", buildJsonObject { put("p_lote", loteId) }).decodeAs<Int>()
    }

    override suspend fun lojasConhecidas(): List<LojaEncarte> = traduzindoErros {
        supabase.postgrest.rpc("admin_lojas_encarte").decodeList<LojaEncarteDto>().map { LojaEncarte(it.nome, it.endereco) }
    }

    companion object {
        /** Mensagem calma para cada resposta da função (nunca o detalhe técnico). */
        fun mensagemDaFuncao(status: Int): String = when (status) {
            401 -> "Sua sessão expirou. Entre novamente."
            403 -> "Só o Admin pode ler encartes."
            503, 404 -> "O leitor de encartes ainda não foi configurado. O resto do app funciona normalmente."
            429 -> "Limite de leituras de hoje atingido. Tente amanhã."
            400, 413 -> "Imagem inválida ou grande demais. Envie JPEG, PNG ou PDF."
            else -> "Não deu para ler este encarte agora. Tente de novo ou use outra imagem."
        }

        private fun codigoDaFuncao(status: Int): String = when (status) {
            401 -> "nao_autenticado"
            403 -> "sem_permissao"
            503, 404 -> "nao_configurado"
            429 -> "limite_diario"
            400, 413 -> "pedido_invalido"
            else -> "falha_leitura"
        }
    }
}

@Serializable
private data class LojaEncarteDto(val nome: String, val endereco: String? = null)

