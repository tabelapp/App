package br.com.tabelapp.dados

import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.LojaResumo
import java.time.LocalDate

interface EncarteRepositorio {
    /** Estabelecimentos cadastrados cujo nome/bairro contém o termo. */
    suspend fun buscarLojas(termo: String): List<LojaResumo>

    /**
     * Sobe as fotos (JPEG já comprimido) e coloca o encarte na fila do Admin.
     * [lojaId] = estabelecimento cadastrado; senão, [pdvNome]/[pdvEndereco] em texto livre.
     */
    suspend fun enviar(
        fotos: List<ByteArray>,
        lojaId: String?,
        pdvNome: String,
        pdvEndereco: String,
        validade: LocalDate?,
        comentario: String,
    )

    /** Encartes enviados pelo próprio usuário, mais recentes primeiro. */
    suspend fun meusEncartes(): List<EncarteEnviado>
}
