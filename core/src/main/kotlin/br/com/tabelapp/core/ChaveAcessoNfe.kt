package br.com.tabelapp.core

/**
 * Chave de acesso de NF-e / NFC-e (44 dígitos).
 *
 * Layout: cUF(2) AAMM(4) CNPJ(14) modelo(2) série(3) número(9) tpEmis(1) código(8) DV(1).
 * O DV é módulo 11 com pesos 2..9 da direita para a esquerda.
 *
 * No V1 a NF é digitada à mão; a chave é opcional e guardada só para
 * rastreabilidade/antifraude futura. O CPF do comprador NUNCA é lido nem guardado.
 */
@JvmInline
value class ChaveAcessoNfe private constructor(val digitos: String) {

    val codigoUf: String get() = digitos.substring(0, 2)
    val anoMes: String get() = digitos.substring(2, 6)
    val cnpjEmitente: String get() = digitos.substring(6, 20)
    val modelo: String get() = digitos.substring(20, 22)
    val serie: String get() = digitos.substring(22, 25)
    val numero: String get() = digitos.substring(25, 34)

    val ehDoRioDeJaneiro: Boolean get() = codigoUf == UF_RJ
    val ehNfce: Boolean get() = modelo == "65"

    /** "3326 0911 1111 ..." em blocos de 4, como impresso no cupom. */
    fun formatada(): String = digitos.chunked(4).joinToString(" ")

    override fun toString(): String = digitos

    companion object {
        const val UF_RJ = "33"

        /** Aceita a chave com espaços/pontos. Retorna null se não tiver 44 dígitos ou o DV não bater. */
        fun deTexto(texto: String): ChaveAcessoNfe? {
            val d = texto.filter { it.isDigit() }
            if (d.length != 44 || texto.any { !it.isDigit() && !it.isWhitespace() && it != '.' && it != '-' }) {
                return null
            }
            return if (dvValido(d)) ChaveAcessoNfe(d) else null
        }

        /**
         * Extrai a chave do conteúdo do QR Code da NFC-e, ex.:
         * https://consultadfe.fazenda.rj.gov.br/consultaNFCe/QRCode?p=<chave>|<versão>|<amb>|<idToken>|<hash>
         */
        fun doQrCode(conteudo: String): ChaveAcessoNfe? {
            val p = Regex("""[?&]p=([^&]+)""").find(conteudo)?.groupValues?.get(1)
                ?: return deTexto(conteudo)
            val primeiro = java.net.URLDecoder.decode(p, "UTF-8").substringBefore('|')
            return deTexto(primeiro)
        }

        fun calcularDv(primeiros43: String): Int {
            require(primeiros43.length == 43 && primeiros43.all { it.isDigit() })
            var peso = 2
            var soma = 0
            for (i in primeiros43.indices.reversed()) {
                soma += (primeiros43[i] - '0') * peso
                peso = if (peso == 9) 2 else peso + 1
            }
            val resto = soma % 11
            return if (resto < 2) 0 else 11 - resto
        }

        private fun dvValido(d: String): Boolean = calcularDv(d.substring(0, 43)) == d[43] - '0'
    }
}
