package br.com.tabelapp.dados

import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LojaSemLocal
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PdvAdmin
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PlanoPdvAdmin
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoDenunciado
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.Unidade
import java.time.LocalDate

/** Cadastro do PDV (conta CNPJ) e a fila de aprovação do Admin. */
interface PdvRepositorio {
    /** Consulta pública do CNPJ na Receita Federal; null = CNPJ não encontrado. */
    suspend fun consultarCnpj(cnpj: String): DadosReceita?

    /** PDVs do usuário logado, com a situação do cadastro. */
    suspend fun meusPdvs(): List<MeuPdv>

    /** Sobe a foto do alvará e cria o pedido de cadastro (fica pendente para o Admin). */
    /**
     * Cria o pedido de cadastro (fica pendente para o Admin), sempre com a foto do comprovante
     * do CNPJ. Anunciante ([CadastroPdv.tipo]): o endereço é da empresa, não vira loja.
     */
    suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray?, cnpjNoAlvara: Boolean)

    // --- Painel do PDV aprovado ---
    suspend fun lojas(pdvId: String): List<LojaPdv>

    /** Tabela de preços oficial (modo rede: a mesma em todas as lojas). */
    suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv>

    /** Tamanho do catálogo da loja (ou da rede), limite efetivo, teste grátis, plano e pausados. */
    suspend fun statusCatalogo(pdvId: String, lojaId: String): StatusCatalogo

    /** Planos (faixas de itens ativos e preço por mês). A assinatura é pelo WhatsApp, sem cobrança no app. */
    suspend fun planos(): List<Plano>

    /** Pausa (grátis) ou reativa um item. Reativar acima do limite efetivo dá erro "limite_catalogo". */
    suspend fun pausarItem(precoId: String, pausar: Boolean)

    /**
     * Inclui ou atualiza um item. Atualizar nunca é bloqueado; item novo acima do
     * limite do plano lança [ErroAmigavel] com codigo "limite_catalogo".
     */
    suspend fun salvarPreco(
        pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?,
        unidade: Unidade = Unidade.UNIDADE, aPartirDe: Boolean = false,
    )

    /** Exclui o item (grátis). */
    suspend fun excluirPreco(precoId: String)

    /** Edição do cadastro (nome de exibição e site; dados da loja). */
    suspend fun atualizarPdv(pdvId: String, nomeFantasia: String, site: String?)
    suspend fun atualizarLoja(loja: LojaPdv)

    /** Grava o pino da loja no mapa. [peloAparelho] = "Estou na loja" (localização do celular). */
    suspend fun definirCoordenadas(lojaId: String, ponto: PontoGeo, peloAparelho: Boolean = false)

    /** Planilha: prévia (o que entra e o que ficaria fora do limite) e importação de verdade. */
    suspend fun simularPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): ResultadoImportacao
    suspend fun importarPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): ResultadoImportacao

    // --- Promoções e pacotes ---
    suspend fun promocoes(pdvId: String): List<PromocaoPdv>
    /** Sobe a arte do banner (JPEG) e devolve o caminho no armazenamento. */
    suspend fun enviarArte(pdvId: String, jpeg: ByteArray): String
    /**
     * Admin: cria o banner já no ar, com as visualizações combinadas fora do app (o app não vende).
     * O dono ajusta texto, link, imagem e palavras depois, em "Meu negócio". Devolve o id da promoção.
     */
    suspend fun adminCriarBanner(
        pdvId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>, visualizacoes: Int,
    ): String
    /**
     * Exclui a promoção: true = apagada (sem pagamento); false = já tinha pagamento: sai do ar e da
     * lista do dono, mas fica guardada no histórico com o pagamento.
     */
    suspend fun excluirPromocao(promocaoId: String): Boolean

    /**
     * Edita título, texto, link e palavras-chave (as visualizações compradas não mudam).
     * [trocarArte] = true grava [artePath] (null = tira a imagem); false mantém a arte atual.
     */
    suspend fun editarPromocao(
        promocaoId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>,
        trocarArte: Boolean, artePath: String?,
    )

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

    // --- Moderação (Admin) ---
    /** Preços com denúncia "Esse preço não confere", os mais denunciados primeiro. */
    suspend fun precosDenunciados(): List<PrecoDenunciado>

    /** Tira o preço da busca (fica no histórico como excluído). */
    suspend fun removerCotacao(cotacaoId: String, motivo: String? = null)

    /** O preço está certo: limpa as denúncias dele. */
    suspend fun manterCotacao(cotacaoId: String)

    /** Estabelecimentos aprovados ou suspensos; [busca] = parte do nome ou do CNPJ. */
    suspend fun estabelecimentos(busca: String?): List<PdvAdmin>

    /** Suspende (preços oficiais saem da busca; nada é apagado). O dono vê o [motivo]. */
    suspend fun suspenderPdv(pdvId: String, motivo: String)
    suspend fun reativarPdv(pdvId: String)

    // --- Planos dos PDVs (Admin; assinatura manual, sem cobrança no app) ---
    suspend fun planosPdvs(busca: String?): List<PlanoPdvAdmin>
    /** Ativa, renova ou troca o plano pago até [validade]. */
    suspend fun definirPlano(pdvId: String, plano: String, validade: LocalDate, observacao: String?)
    suspend fun cancelarPlano(pdvId: String, observacao: String?)

    /** Lojas ainda sem pino no mapa (o Admin localiza de uma vez). */
    suspend fun lojasSemCoordenadas(): List<LojaSemLocal>
}
