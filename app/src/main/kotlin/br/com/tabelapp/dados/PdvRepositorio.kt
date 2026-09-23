package br.com.tabelapp.dados

import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.SaldoCota
import java.time.LocalDate

/** Cadastro do PDV (conta CNPJ) e a fila de aprovação do Admin. */
interface PdvRepositorio {
    /** Consulta pública do CNPJ na Receita Federal; null = CNPJ não encontrado. */
    suspend fun consultarCnpj(cnpj: String): DadosReceita?

    /** PDVs do usuário logado, com a situação do cadastro. */
    suspend fun meusPdvs(): List<MeuPdv>

    /** Sobe a foto do alvará e cria o pedido de cadastro (fica pendente para o Admin). */
    suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray, cnpjNoAlvara: Boolean)

    // --- Painel do PDV aprovado ---
    suspend fun lojas(pdvId: String): List<LojaPdv>

    /** Tabela de preços oficial (modo rede: a mesma em todas as lojas). */
    suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv>

    /** Saldo de operações da loja (ou da rede). */
    suspend fun cota(pdvId: String, lojaId: String): SaldoCota

    /** Inclui ou atualiza um item; devolve quantas operações da cota foram usadas (0 ou 1). */
    suspend fun salvarPreco(pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?): Int

    /** Exclui o item (grátis). */
    suspend fun excluirPreco(precoId: String)

    // --- Admin ---
    suspend fun pendentes(): List<PdvPendente>
    suspend fun fotoAlvara(caminho: String): ByteArray
    suspend fun aprovar(pdvId: String)
    suspend fun rejeitar(pdvId: String, motivo: String)
}
