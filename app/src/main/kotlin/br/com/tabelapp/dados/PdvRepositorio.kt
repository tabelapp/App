package br.com.tabelapp.dados

import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PdvPendente

/** Cadastro do PDV (conta CNPJ) e a fila de aprovação do Admin. */
interface PdvRepositorio {
    /** Consulta pública do CNPJ na Receita Federal; null = CNPJ não encontrado. */
    suspend fun consultarCnpj(cnpj: String): DadosReceita?

    /** PDVs do usuário logado, com a situação do cadastro. */
    suspend fun meusPdvs(): List<MeuPdv>

    /** Sobe a foto do alvará e cria o pedido de cadastro (fica pendente para o Admin). */
    suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray, cnpjNoAlvara: Boolean)

    // --- Admin ---
    suspend fun pendentes(): List<PdvPendente>
    suspend fun fotoAlvara(caminho: String): ByteArray
    suspend fun aprovar(pdvId: String)
    suspend fun rejeitar(pdvId: String, motivo: String)
}
