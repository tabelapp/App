package br.com.tabelapp.dados

import br.com.tabelapp.core.DadosReceita
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Consulta pública do CNPJ (dados da Receita Federal) pela BrasilAPI — gratuita,
 * sem chave. Serve para preencher o cadastro e para o Admin conferir; quem
 * aprova o PDV continua sendo o Admin.
 */
class ConsultaCnpj {
    private val cliente by lazy { HttpClient(OkHttp) }

    suspend fun consultar(cnpj: String): DadosReceita? {
        val digitos = cnpj.filter { it.isDigit() }
        val resposta = try {
            cliente.get("https://brasilapi.com.br/api/cnpj/v1/$digitos")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErroAmigavel("Não consegui consultar a Receita agora. Verifique a internet e tente de novo.", e)
        }
        if (resposta.status == HttpStatusCode.NotFound || resposta.status == HttpStatusCode.BadRequest) return null
        if (resposta.status.value !in 200..299) {
            throw ErroAmigavel("A consulta à Receita está indisponível agora. Tente de novo em instantes.")
        }
        val json = runCatching { Json.parseToJsonElement(resposta.bodyAsText()).jsonObject }.getOrNull()
            ?: throw ErroAmigavel("Resposta inesperada da consulta à Receita.")
        return ler(json, digitos)
    }

    companion object {
        /** Lê a resposta da BrasilAPI (campos ausentes viram null). */
        fun ler(json: JsonObject, cnpj: String): DadosReceita {
            fun campo(nome: String): String? =
                (json[nome] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content?.trim()?.ifEmpty { null }
            val endereco = listOfNotNull(
                listOfNotNull(campo("descricao_tipo_de_logradouro"), campo("logradouro")).joinToString(" ").ifEmpty { null },
                campo("numero"),
                campo("complemento"),
            ).joinToString(", ").ifEmpty { null }
            return DadosReceita(
                cnpj = cnpj,
                razaoSocial = campo("razao_social"),
                nomeFantasia = campo("nome_fantasia"),
                situacao = campo("descricao_situacao_cadastral"),
                endereco = endereco,
                bairro = campo("bairro"),
                cidade = campo("municipio"),
                uf = campo("uf"),
                cep = campo("cep"),
                telefone = campo("ddd_telefone_1"),
                atividade = campo("cnae_fiscal_descricao"),
            )
        }
    }
}
