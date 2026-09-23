package br.com.tabelapp.dados

import br.com.tabelapp.core.EncarteEnviado
import br.com.tabelapp.core.ItemEncarte
import br.com.tabelapp.core.LojaResumo
import java.time.LocalDate

interface EncarteRepositorio {
    /** Estabelecimentos cadastrados cujo nome/bairro contém o termo. */
    suspend fun buscarLojas(termo: String): List<LojaResumo>

    /**
     * Sobe as fotos (JPEG já comprimido) e publica na busca os [itens] lidos do
     * encarte e confirmados pelo usuário. Devolve quantos preços foram publicados.
     * [lojaId] = estabelecimento cadastrado; senão, [pdvNome]/[pdvEndereco] em texto livre.
     */
    suspend fun publicar(
        fotos: List<ByteArray>,
        lojaId: String?,
        pdvNome: String,
        pdvEndereco: String,
        validade: LocalDate,
        itens: List<ItemEncarte>,
    ): Int

    /** Encartes enviados pelo próprio usuário, mais recentes primeiro. */
    suspend fun meusEncartes(): List<EncarteEnviado>
}
