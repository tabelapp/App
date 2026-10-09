package br.com.tabelapp.dados

import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.SimulacaoImportacao
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

    /** Edição do cadastro (nome de exibição e site; dados da loja). */
    suspend fun atualizarPdv(pdvId: String, nomeFantasia: String, site: String?)
    suspend fun atualizarLoja(loja: LojaPdv)

    /** Planilha: prévia (quanto vai custar na cota) e importação de verdade. */
    suspend fun simularPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): SimulacaoImportacao
    suspend fun importarPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): Int

    // --- Promoções e pacotes ---
    suspend fun promocoes(pdvId: String): List<PromocaoPdv>
    /** Sobe a arte do banner (JPEG) e devolve o caminho no armazenamento. */
    suspend fun enviarArte(pdvId: String, jpeg: ByteArray): String
    /** Cria a promoção (aguardando pagamento) e devolve o código do pagamento. */
    suspend fun criarPromocao(
        pdvId: String, titulo: String, descricao: String?, link: String?, artePath: String?,
        palavrasChave: List<String>, visualizacoes: Int,
    ): String
    /** Encerra a promoção: true = apagada; false = já tinha pagamento, só saiu do ar (fica no histórico). */
    suspend fun excluirPromocao(promocaoId: String): Boolean
    /** Pede um pacote de +50 operações (R$ 10); devolve o código do pagamento. */
    suspend fun comprarPacoteOperacoes(pdvId: String, lojaId: String): String

    // --- Admin ---
    suspend fun pagamentosPendentes(): List<PagamentoPendente>
    suspend fun confirmarPagamento(pagamentoId: String)
    suspend fun cancelarPagamento(pagamentoId: String)

    suspend fun pendentes(): List<PdvPendente>
    suspend fun fotoAlvara(caminho: String): ByteArray
    suspend fun aprovar(pdvId: String)
    suspend fun rejeitar(pdvId: String, motivo: String)

    /** Nomes de estabelecimento sugeridos nas NFs, esperando confirmação. */
    suspend fun nomesSugeridos(): List<NomeSugerido>
    suspend fun decidirNome(cnpj: String, nome: String, aprovar: Boolean)
}
