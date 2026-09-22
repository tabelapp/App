package br.com.tabelapp.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Validação das linhas da planilha de preços do PDV (importação Excel).
 * Colunas esperadas: produto | preço | validade (opcional) | obs (opcional).
 * A leitura do arquivo .xlsx em si fica no app; aqui só entram os textos das células.
 */
object Planilha {

    data class Linha(
        val numero: Int,
        val produto: String,
        val precoCentavos: Long,
        val validade: LocalDate,
        val obs: String?,
    )

    data class ErroLinha(val numero: Int, val mensagem: String)

    data class Resultado(val linhas: List<Linha>, val erros: List<ErroLinha>) {
        val ok: Boolean get() = erros.isEmpty() && linhas.isNotEmpty()
    }

    const val MAX_OBS = 140
    const val MAX_PRODUTO = 200

    private val formatoBr = DateTimeFormatter.ofPattern("d/M/uuuu")

    /**
     * @param celulas linhas já lidas do arquivo (sem o cabeçalho), numeradas a partir de [primeiraLinha].
     */
    fun validar(celulas: List<List<String?>>, hoje: LocalDate, primeiraLinha: Int = 2): Resultado {
        val linhas = mutableListOf<Linha>()
        val erros = mutableListOf<ErroLinha>()
        val vistos = mutableMapOf<String, Int>()

        celulas.forEachIndexed { i, cols ->
            val n = primeiraLinha + i
            val produto = cols.getOrNull(0)?.trim().orEmpty()
            val precoTxt = cols.getOrNull(1)?.trim().orEmpty()
            val validadeTxt = cols.getOrNull(2)?.trim().orEmpty()
            val obs = cols.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }

            if (produto.isEmpty() && precoTxt.isEmpty()) return@forEachIndexed // linha em branco

            val erro = when {
                produto.isEmpty() -> "Produto vazio"
                produto.length > MAX_PRODUTO -> "Nome do produto muito longo"
                Dinheiro.parse(precoTxt).let { it == null || it <= 0 } -> "Preço inválido: \"$precoTxt\""
                obs != null && obs.length > MAX_OBS -> "OBS maior que $MAX_OBS caracteres"
                else -> null
            }
            if (erro != null) {
                erros += ErroLinha(n, erro)
                return@forEachIndexed
            }

            val validade = if (validadeTxt.isEmpty()) Validade.padrao(hoje) else lerData(validadeTxt)
            if (validade == null) {
                erros += ErroLinha(n, "Data de validade inválida: \"$validadeTxt\"")
                return@forEachIndexed
            }
            Validade.validar(validade, hoje)?.let {
                erros += ErroLinha(n, it.mensagem)
                return@forEachIndexed
            }

            val chave = Texto.normalizar(produto)
            val anterior = vistos.put(chave, n)
            if (anterior != null) {
                erros += ErroLinha(n, "Produto repetido (já está na linha $anterior)")
                return@forEachIndexed
            }

            linhas += Linha(n, produto, Dinheiro.parse(precoTxt)!!, validade, obs)
        }
        return Resultado(linhas, erros)
    }

    private fun lerData(texto: String): LocalDate? =
        try {
            if ('-' in texto) LocalDate.parse(texto.take(10)) else LocalDate.parse(texto, formatoBr)
        } catch (e: DateTimeParseException) {
            null
        }
}
