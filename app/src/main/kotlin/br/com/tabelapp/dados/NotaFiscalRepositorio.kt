package br.com.tabelapp.dados

import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.RascunhoNf

interface NotaFiscalRepositorio {
    /** Lojas cadastradas do CNPJ emitente da nota (0, 1 ou várias, se for uma rede). */
    suspend fun lojasDoCnpj(cnpj: String): List<LojaResumo>

    /** Envia todos os produtos da nota de uma vez. @return quantos preços foram gravados. */
    suspend fun enviar(rascunho: RascunhoNf): Int
}
