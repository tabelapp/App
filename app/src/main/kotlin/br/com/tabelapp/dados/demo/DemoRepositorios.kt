package br.com.tabelapp.dados.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.BannerDenunciado
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.ConfigConfianca
import br.com.tabelapp.core.ContatoTabelapp
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.DadosDemo
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.ErrosServidor
import br.com.tabelapp.core.EstadoItem
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.ItemFora
import br.com.tabelapp.core.ItemLoja
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.Limites
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.LojaSemLocal
import br.com.tabelapp.core.MapaPrecos
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PaginaLoja
import br.com.tabelapp.core.PdvAdmin
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.Plano
import br.com.tabelapp.core.PlanoPdvAdmin
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.PrecoDenunciado
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.ResultadoImportacao
import br.com.tabelapp.core.StatusCatalogo
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.TipoEmpresa
import br.com.tabelapp.core.TipoOperacao
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Modo demonstração: roda sem Supabase configurado, com os dados fictícios
 * de Petrópolis. Qualquer e-mail/senha entra. Nada é salvo.
 */
class DemoAuthRepositorio : AuthRepositorio {
    private val _estado = MutableStateFlow<EstadoSessao>(EstadoSessao.Deslogado)
    override val estado: StateFlow<EstadoSessao> = _estado.asStateFlow()
    override val sessaoExpirada: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override suspend fun entrarComEmail(email: String, senha: String) {
        delay(300)
        if (!email.contains('@') || senha.isEmpty()) throw ErroAmigavel("Informe e-mail e senha.")
        // Na demonstração, e-mail começando com "admin" entra como Admin (para ver a fila de cadastros).
        val tipo = if (email.trim().lowercase().startsWith("admin")) TipoConta.ADMIN else TipoConta.CPF
        _estado.value = EstadoSessao.Logado(
            Usuario("demo", email.substringBefore('@'), email, tipo, cadastroCompleto = true)
        )
    }

    override suspend fun cadastrarComEmail(nome: String, email: String, senha: String, tipo: TipoConta): Boolean {
        delay(300)
        _estado.value = EstadoSessao.Logado(Usuario("demo", nome, email, tipo, cadastroCompleto = true))
        return true
    }

    override suspend fun concluirCadastro(nome: String, tipo: TipoConta) {
        val atual = (_estado.value as? EstadoSessao.Logado)?.usuario ?: return
        _estado.value = EstadoSessao.Logado(atual.copy(nome = nome, tipo = tipo, cadastroCompleto = true))
    }

    override suspend fun sair() {
        _estado.value = EstadoSessao.Deslogado
    }

    override suspend fun atualizarNome(nome: String) {
        delay(200)
        if (nome.trim().length < 2) throw ErroAmigavel(ErrosServidor.traduzir("nome_invalido"))
        val atual = (_estado.value as? EstadoSessao.Logado)?.usuario ?: return
        _estado.value = EstadoSessao.Logado(atual.copy(nome = nome.trim()))
    }

    override suspend fun registrarAceiteTermos(versao: String) {}

    override suspend fun excluirConta() {
        delay(400)
        _estado.value = EstadoSessao.Deslogado
    }

    override suspend fun enviarCodigoSenha(email: String) {
        delay(400)
        if (!email.contains('@')) throw ErroAmigavel("Informe um e-mail válido.")
    }

    /** Na demonstração não há e-mail: qualquer código de 6 números serve. */
    override suspend fun redefinirSenha(email: String, codigo: String, novaSenha: String) {
        delay(400)
        if (codigo.trim().length != 6) throw ErroAmigavel("Código inválido ou vencido. Peça um novo código.")
        entrarComEmail(email, novaSenha)
    }

    /** Simula o primeiro login pelo Google: cai na tela de escolher CPF/CNPJ. */
    @Composable
    override fun lembrarLoginGoogle(aoFalhar: (String) -> Unit): (() -> Unit)? {
        val escopo = rememberCoroutineScope()
        return {
            escopo.launch {
                delay(300)
                _estado.value = EstadoSessao.Logado(
                    Usuario("demo", "Visitante Google", "visitante@gmail.com", TipoConta.CPF, cadastroCompleto = false)
                )
            }
        }
    }
}

class DemoCotacoesRepositorio(private val banco: DemoBanco) : CotacoesRepositorio {
    override suspend fun buscar(termo: String?, posicao: PontoGeo?): List<Cotacao> {
        delay(250)
        val suspensos = banco.pdvs.filter { it.meu.status == StatusPdv.SUSPENSO }.map { it.meu.id }.toSet()
        return DadosDemo.buscar(termo, extras = banco.enviados)
            .filter { it.id !in banco.removidos && it.pdvId !in suspensos }
    }

    override suspend fun banners(termo: String?, posicao: PontoGeo?): List<Banner> = (
        banco.promocoesDemo.filter { it.second.status == "ativa" }.map { (_, p) ->
            Banner(p.id, "Seu banner (demonstração)", p.titulo, p.descricao, null, p.link)
        } + listOf(
            Banner("demo-1", "Supermercado Serra Imperial", "Semana do hortifrúti",
                "Frutas e verduras com até 30% de desconto. Só até domingo!", null),
            Banner("demo-2", "Empório Itaipava", "Queijos e vinhos da serra",
                "3 por 2 em queijos artesanais.", null),
        )
    ).filter { it.id !in banco.bannersPausados }

    override suspend fun registrarVisualizacao(bannerId: String) {}

    override suspend fun configConfianca(): ConfigConfianca = ConfigConfianca()

    override suspend fun denunciar(cotacaoId: String, motivo: String?): Boolean {
        delay(200)
        return banco.denuncias.add(cotacaoId)
    }

    private suspend fun precosDemo(): List<Cotacao> = buscar(null, null)

    override suspend fun paginaLoja(lojaId: String): PaginaLoja? {
        delay(200)
        val c = precosDemo().firstOrNull { it.lojaId == lojaId } ?: return null
        return PaginaLoja(lojaId, c.pdvId.orEmpty(), c.pdvNome, c.lojaNome, c.endereco, c.telefone, c.site, c.local)
    }

    override suspend fun precosDaLoja(lojaId: String, termo: String?): List<ItemLoja> {
        delay(200)
        val hoje = LocalDate.now()
        return precosDemo()
            .filter { it.lojaId == lojaId && it.fonte.ehDoPdv && (it.validade ?: hoje) >= hoje }
            .filter { termo.isNullOrBlank() || Texto.casaBusca(it.produto, termo) }
            .sortedBy { Texto.normalizar(it.produto) }
            .map { ItemLoja(it.id, it.produto, it.precoCentavos, it.validade ?: hoje, it.obs, it.unidade, it.aPartirDe) }
    }

    override suspend fun bannersDaLoja(pdvId: String): List<Banner> {
        val nome = precosDemo().firstOrNull { it.pdvId == pdvId }?.pdvNome ?: return emptyList()
        return banners(null, null).filter { it.pdvNome == nome }
    }

    override suspend fun denunciarBanner(promocaoId: String, motivo: String?): Boolean {
        delay(200)
        val banner = banners(null, null).firstOrNull { it.id == promocaoId } ?: return false
        if (promocaoId in banco.bannersDenunciados) return false
        banco.bannersDenunciados[promocaoId] = banner to motivo
        return true
    }
}

/** Guarda em memória o que foi enviado na demonstração (some ao fechar o app). */
class DemoBanco {
    val enviados = mutableListOf<Cotacao>()
    val chavesEnviadas = mutableSetOf<String>()
    val pdvs = mutableListOf<DemoPdv>()
    val precosPdv = mutableListOf<PrecoPdv>()
    var nomeDemoDecidido = false
    val lojasEditadas = mutableMapOf<String, LojaPdv>()
    val promocoesDemo = mutableListOf<Pair<String, PromocaoPdv>>()
    /** Plano pago (código e validade) e teste grátis por PDV, e os itens pausados pelo PDV. */
    val planosPagos = mutableMapOf<String, Pair<String, LocalDate>>()
    val testes = mutableMapOf<String, LocalDate>()
    val pausados = mutableSetOf<String>()
    val denuncias = mutableSetOf<String>()
    /** Preços removidos pelo Admin na demonstração. */
    val removidos = mutableSetOf<String>()
    /** Banners denunciados (com o motivo) e pausados pelo Admin na demonstração. */
    val bannersDenunciados = mutableMapOf<String, Pair<Banner, String?>>()
    val bannersPausados = mutableSetOf<String>()
}

/** Planos da demonstração (no app real vêm da tabela `planos`). */
private val planosDemo = listOf(
    Plano("gratis", "Grátis", 5, 0, 1), Plano("essencial", "Essencial", 30, 999, 2),
    Plano("loja", "Loja", 300, 1999, 3), Plano("mercado", "Mercado", 3000, 2999, 4),
)
private const val TOLERANCIA_DEMO = 7

/**
 * Demonstração do cadastro de PDV: a consulta da Receita devolve dados fictícios
 * para qualquer CNPJ válido, e os pedidos ficam em memória. O Admin de
 * demonstração (e-mail começando com "admin") aprova ou rejeita.
 */
class DemoPdvRepositorio(private val banco: DemoBanco) : PdvRepositorio {
    override suspend fun consultarCnpj(cnpj: String): DadosReceita? {
        delay(500)
        val digitos = cnpj.filter { it.isDigit() }
        if (!Cnpj.valido(digitos)) return null
        return DadosReceita(
            cnpj = digitos, razaoSocial = "EMPRESA DEMONSTRAÇÃO LTDA", nomeFantasia = "Mercadinho Demonstração",
            situacao = "ATIVA", endereco = "Rua do Imperador, 100", bairro = "Centro", cidade = "Petrópolis",
            uf = "RJ", cep = "25620000", telefone = "2422220000", atividade = "Comércio varejista de mercadorias em geral",
        )
    }

    override suspend fun meusPdvs(): List<MeuPdv> = banco.pdvs.map { it.meu }

    override suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray?, cnpjNoAlvara: Boolean) {
        delay(600)
        val cnpj = dados.cnpj.filter { it.isDigit() }
        banco.pdvs.removeAll { it.meu.cnpj == cnpj }
        val id = "pdv-" + UUID.randomUUID()
        banco.pdvs += DemoPdv(
            meu = MeuPdv(
                id, cnpj, dados.razaoSocial, dados.nomeFantasia.trim(), StatusPdv.PENDENTE, null, cnpjNoAlvara, Instant.now(),
                site = dados.site.ifBlank { null }, tipo = dados.tipo,
            ),
            pendente = PdvPendente(
                id = id, cnpj = cnpj, razaoSocial = dados.razaoSocial, nomeFantasia = dados.nomeFantasia.trim(),
                endereco = listOf(dados.endereco, dados.bairro, dados.cidade).filter { it.isNotBlank() }.joinToString(", "),
                telefone = dados.telefone.ifBlank { null }, alvaraPath = id,
                cnpjConferidoNoAlvara = cnpjNoAlvara, tipo = dados.tipo,
                situacaoReceita = receita?.situacao, razaoSocialReceita = receita?.razaoSocial,
                enderecoReceita = receita?.let { listOfNotNull(it.endereco, it.bairro, it.cidade, it.uf).joinToString(", ") },
                donoNome = "Você (demonstração)", donoEmail = null, enviadoEm = Instant.now(),
            ),
            alvara = alvaraJpeg ?: ByteArray(0),
        )
    }

    override suspend fun lojas(pdvId: String): List<LojaPdv> {
        val p = banco.pdvs.firstOrNull { it.meu.id == pdvId } ?: return emptyList()
        if (p.meu.tipo == TipoEmpresa.ANUNCIANTE) return emptyList()
        val id = "loja-$pdvId"
        return listOf(
            banco.lojasEditadas[id]
                ?: LojaPdv(id, null, p.pendente.endereco ?: "Petrópolis", p.pendente.telefone,
                    logradouro = p.pendente.endereco?.substringBefore(',').orEmpty(), cidade = "Petrópolis", uf = "RJ")
        )
    }

    override suspend fun atualizarPdv(pdvId: String, nomeFantasia: String, site: String?) {
        if (nomeFantasia.isBlank()) throw ErroAmigavel("Informe o nome do estabelecimento.")
        val i = banco.pdvs.indexOfFirst { it.meu.id == pdvId }
        if (i >= 0) banco.pdvs[i] = banco.pdvs[i].let {
            it.copy(meu = it.meu.copy(nomeFantasia = nomeFantasia.trim(), site = site?.trim()?.ifEmpty { null }))
        }
    }

    override suspend fun atualizarLoja(loja: LojaPdv) {
        if (loja.logradouro.isBlank()) throw ErroAmigavel("Informe o endereço.")
        val completo = listOfNotNull(loja.logradouro, loja.bairro, loja.cidade).filter { it.isNotBlank() }.joinToString(", ")
        val antes = lojas(loja.id.removePrefix("loja-")).firstOrNull()
        // Mudou o endereço: o pino antigo sai (o painel localiza de novo), como no banco.
        val mudouEndereco = antes == null || antes.endereco != completo
        banco.lojasEditadas[loja.id] = loja.copy(
            endereco = completo, whatsapp = loja.whatsapp?.filter { it.isDigit() },
            local = if (mudouEndereco) null else antes?.local,
            localPeloAparelho = if (mudouEndereco) false else antes?.localPeloAparelho == true,
        )
    }

    override suspend fun definirCoordenadas(lojaId: String, ponto: PontoGeo, peloAparelho: Boolean) {
        if (!MapaPrecos.coordenadaValida(ponto)) throw ErroAmigavel(ErrosServidor.traduzir("coordenada_invalida"))
        val atual = lojas(lojaId.removePrefix("loja-")).firstOrNull() ?: throw ErroAmigavel("Loja não encontrada.")
        banco.lojasEditadas[lojaId] = atual.copy(local = ponto, localPeloAparelho = peloAparelho)
    }

    override suspend fun lojasSemCoordenadas(): List<LojaSemLocal> =
        banco.pdvs.filter { it.meu.status == StatusPdv.APROVADO }
            .flatMap { lojas(it.meu.id) }
            .filter { it.local == null }
            .map { LojaSemLocal(it.id, it.nome, it.endereco) }

    /** Demonstração: plano grátis, sem período de fundador (para ver o limite em ação). */
    private fun limiteDe(pdvId: String) = banco.planosPagos[pdvId].let { pago ->
        Limites.efetivo(planosDemo, pago?.first ?: Plano.GRATIS, pago?.second,
            banco.testes.getOrPut(pdvId) { LocalDate.now().plusDays(30) }, LocalDate.now(), TOLERANCIA_DEMO)
    }

    /** Itens na validade e não pausados, do mais recente para o mais antigo (os primeiros [limite] ficam no ar). */
    private fun ativosDe(lojaId: String) =
        banco.precosPdv.filter { it.lojaId == lojaId && !it.validade.isBefore(LocalDate.now()) && it.id !in banco.pausados }
            .asReversed()

    override suspend fun statusCatalogo(pdvId: String, lojaId: String): StatusCatalogo {
        val ativos = ativosDe(lojaId).size
        val e = limiteDe(pdvId)
        val pago = banco.planosPagos[pdvId]
        val contratado = planosDemo.first { it.codigo == (pago?.first ?: Plano.GRATIS) }
        return StatusCatalogo(
            plano = contratado.codigo, planoNome = contratado.nome, limitePlano = contratado.limiteItens,
            planoAte = pago?.second, testeAte = banco.testes[pdvId], planoEfetivo = e.plano,
            planoEfetivoNome = planosDemo.first { it.codigo == e.plano }.nome, limite = e.limite, origem = e.origem,
            emTolerancia = e.emTolerancia, fim = e.fim, toleranciaAte = e.toleranciaAte, itensAtivos = ativos,
            disponiveis = (e.limite - ativos).coerceAtLeast(0),
            pausadosPdv = banco.precosPdv.count { it.lojaId == lojaId && it.id in banco.pausados },
            limiteGratis = planosDemo.first().limiteItens, diasTolerancia = TOLERANCIA_DEMO,
            whatsappContato = ContatoTabelapp.WHATSAPP,
        )
    }

    override suspend fun planos(): List<Plano> = planosDemo

    override suspend fun pausarItem(precoId: String, pausar: Boolean) {
        delay(150)
        val item = banco.precosPdv.firstOrNull { it.id == precoId } ?: throw ErroAmigavel("Item não encontrado.")
        if (!pausar && item.id in banco.pausados) {
            val pdvId = banco.pdvs.firstOrNull { "loja-${it.meu.id}" == item.lojaId }?.meu?.id.orEmpty()
            if (ativosDe(item.lojaId).size >= limiteDe(pdvId).limite) {
                throw ErroAmigavel(ErrosServidor.traduzir("limite_catalogo"), codigo = "limite_catalogo")
            }
        }
        if (pausar) banco.pausados += precoId else banco.pausados -= precoId
    }

    override suspend fun planosPdvs(busca: String?): List<PlanoPdvAdmin> =
        banco.pdvs.filter { it.meu.status == StatusPdv.APROVADO && it.meu.tipo == TipoEmpresa.LOJA }.map { p ->
            val e = limiteDe(p.meu.id)
            val pago = banco.planosPagos[p.meu.id]
            PlanoPdvAdmin(
                p.meu.id, p.meu.nomeFantasia, p.meu.cnpj, pago?.first ?: Plano.GRATIS,
                planosDemo.first { it.codigo == (pago?.first ?: Plano.GRATIS) }.nome, pago?.second, banco.testes[p.meu.id],
                e.limite, e.origem, e.emTolerancia, ativosDe("loja-${p.meu.id}").size,
            )
        }

    override suspend fun definirPlano(pdvId: String, plano: String, validade: LocalDate, observacao: String?) {
        delay(200)
        if (plano == Plano.GRATIS || planosDemo.none { it.codigo == plano }) throw ErroAmigavel(ErrosServidor.traduzir("plano_invalido"))
        if (validade.isBefore(LocalDate.now())) throw ErroAmigavel(ErrosServidor.traduzir("validade_invalida"))
        banco.planosPagos[pdvId] = plano to validade
    }

    override suspend fun cancelarPlano(pdvId: String, observacao: String?) {
        delay(200)
        banco.planosPagos -= pdvId
    }

    private suspend fun planilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>, simular: Boolean): ResultadoImportacao {
        delay(300)
        var livres = statusCatalogo(pdvId, lojaId).disponiveis
        val tipos = mutableListOf<TipoOperacao>()
        val fora = mutableListOf<ItemFora>()
        linhas.forEach { l ->
            val atual = banco.precosPdv.firstOrNull { it.lojaId == lojaId && Texto.normalizar(it.produto) == Texto.normalizar(l.produto) }
            val tipo = TipoOperacao.classificar(atual?.precoCentavos, l.precoCentavos)
            if (tipo == TipoOperacao.CRIAR_ITEM && livres <= 0) {
                fora += ItemFora(l.numero, l.produto)
                return@forEach
            }
            if (tipo == TipoOperacao.CRIAR_ITEM) livres--
            tipos += tipo
            if (!simular) salvarPreco(pdvId, lojaId, l.produto, l.precoCentavos, l.validade, l.obs)
        }
        return ResultadoImportacao(
            criados = tipos.count { it == TipoOperacao.CRIAR_ITEM }, aumentados = tipos.count { it == TipoOperacao.AUMENTAR_PRECO },
            diminuidos = tipos.count { it == TipoOperacao.DIMINUIR_PRECO }, inalterados = tipos.count { it == TipoOperacao.EDITAR_DADOS },
            foraDoLimite = fora.size, itensFora = fora, limite = limiteDe(pdvId).limite,
            planoNome = planosDemo.first { it.codigo == limiteDe(pdvId).plano }.nome,
        )
    }

    override suspend fun simularPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>) =
        planilha(pdvId, lojaId, linhas, simular = true)

    override suspend fun importarPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>) =
        planilha(pdvId, lojaId, linhas, simular = false)

    override suspend fun promocoes(pdvId: String): List<PromocaoPdv> = banco.promocoesDemo.filter { it.first == pdvId }.map { it.second }

    override suspend fun enviarArte(pdvId: String, jpeg: ByteArray): String = "$pdvId/demo-${UUID.randomUUID()}.jpg"

    override suspend fun adminCriarBanner(
        pdvId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>, visualizacoes: Int,
    ): String {
        delay(300)
        if (visualizacoes < 1) throw ErroAmigavel("Informe quantas visualizações o banner terá.")
        if (titulo.isBlank()) throw ErroAmigavel("Dê um título para a promoção.")
        val id = "promo-" + UUID.randomUUID()
        banco.promocoesDemo += pdvId to PromocaoPdv(
            id, titulo.trim(), descricao, link, null, palavrasChave, "ativa", visualizacoes, 0, null, null, 0,
        )
        return id
    }

    override suspend fun excluirPromocao(promocaoId: String): Boolean {
        val paga = banco.promocoesDemo.firstOrNull { it.second.id == promocaoId }?.second?.visualizacoesContratadas?.let { it > 0 } == true
        banco.promocoesDemo.removeAll { it.second.id == promocaoId }
        return !paga
    }

    override suspend fun editarPromocao(
        promocaoId: String, titulo: String, descricao: String?, link: String?, palavrasChave: List<String>,
        trocarArte: Boolean, artePath: String?,
    ) {
        delay(200)
        if (titulo.isBlank()) throw ErroAmigavel(ErrosServidor.traduzir("titulo_obrigatorio"))
        val i = banco.promocoesDemo.indexOfFirst { it.second.id == promocaoId }
        if (i < 0) throw ErroAmigavel(ErrosServidor.traduzir("sem_permissao"))
        banco.promocoesDemo[i] = banco.promocoesDemo[i].let { (pdv, p) ->
            pdv to p.copy(
                titulo = titulo.trim(), descricao = descricao?.trim()?.ifEmpty { null }, link = link?.trim()?.ifEmpty { null },
                palavrasChave = palavrasChave.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                artePath = if (trocarArte) artePath else p.artePath,
            )
        }
    }

    override suspend fun pagamentosPendentes(): List<PagamentoPendente> =
        banco.promocoesDemo.mapNotNull { (_, p) ->
            p.pagamentoPendenteId?.let { PagamentoPendente(it, "pacote_visualizacoes", "PDV demonstração", "Banner: ${p.titulo}",
                p.visualizacoesPendentes ?: 0, p.valorPendenteCentavos ?: 0, "Você (demonstração)", null, Instant.now()) }
        }

    override suspend fun confirmarPagamento(pagamentoId: String) {
        val i = banco.promocoesDemo.indexOfFirst { it.second.pagamentoPendenteId == pagamentoId }
        if (i >= 0) banco.promocoesDemo[i] = banco.promocoesDemo[i].let { (pdv, p) ->
            pdv to p.copy(status = "ativa", visualizacoesContratadas = p.visualizacoesContratadas + (p.visualizacoesPendentes ?: 0),
                pagamentoPendenteId = null, valorPendenteCentavos = null, visualizacoesPendentes = null)
        }
    }

    override suspend fun cancelarPagamento(pagamentoId: String) {
        val i = banco.promocoesDemo.indexOfFirst { it.second.pagamentoPendenteId == pagamentoId }
        if (i >= 0) banco.promocoesDemo[i] = banco.promocoesDemo[i].let { (pdv, p) ->
            pdv to p.copy(pagamentoPendenteId = null, valorPendenteCentavos = null, visualizacoesPendentes = null)
        }
    }

    override suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv> {
        val noAr = ativosDe(lojaId).take(limiteDe(pdvId).limite).map { it.id }.toSet()
        return banco.precosPdv.filter { it.lojaId == lojaId }.sortedBy { Texto.normalizar(it.produto) }.map {
            it.copy(
                estado = when {
                    it.validade.isBefore(LocalDate.now()) -> EstadoItem.VENCIDO
                    it.id in banco.pausados -> EstadoItem.PAUSADO
                    it.id in noAr -> EstadoItem.ATIVO
                    else -> EstadoItem.PAUSADO_LIMITE
                },
            )
        }
    }

    override suspend fun salvarPreco(
        pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?,
        unidade: Unidade, aPartirDe: Boolean,
    ) {
        delay(300)
        val pdv = banco.pdvs.firstOrNull { it.meu.id == pdvId } ?: throw ErroAmigavel("Cadastro não encontrado.")
        if (pdv.meu.status != StatusPdv.APROVADO) throw ErroAmigavel("Seu cadastro ainda não foi aprovado.")
        val atual = banco.precosPdv.firstOrNull { it.lojaId == lojaId && Texto.normalizar(it.produto) == Texto.normalizar(produto) }
        // Só item novo é conferido contra o limite; atualizar nunca é bloqueado.
        if (atual == null && statusCatalogo(pdvId, lojaId).noLimite) {
            throw ErroAmigavel(ErrosServidor.traduzir("limite_catalogo"), codigo = "limite_catalogo")
        }
        val item = PrecoPdv(
            id = atual?.id ?: ("pp-" + UUID.randomUUID()), lojaId = lojaId, produto = produto.trim(),
            precoCentavos = precoCentavos, validade = validade ?: Validade.padrao(LocalDate.now()),
            obs = obs?.trim()?.ifEmpty { null }, unidade = unidade, aPartirDe = aPartirDe,
        )
        banco.precosPdv.removeAll { it.id == item.id }
        banco.precosPdv += item
        // Aparece na busca da demonstração como preço oficial.
        banco.enviados.removeAll { it.id == item.id }
        banco.enviados += Cotacao(
            id = item.id, produto = item.produto, precoCentavos = item.precoCentavos, validade = item.validade,
            obs = item.obs, fonte = Fonte.PDV_MANUAL, lojaId = null, pdvId = null, pdvNome = pdv.meu.nomeFantasia,
            endereco = banco.lojasEditadas[lojaId]?.endereco ?: pdv.pendente.endereco,
            telefone = banco.lojasEditadas[lojaId]?.let { it.whatsapp ?: it.telefone } ?: pdv.pendente.telefone,
            criadoEm = Instant.now(), unidade = unidade, aPartirDe = aPartirDe,
        )
    }

    override suspend fun excluirPreco(precoId: String) {
        banco.precosPdv.removeAll { it.id == precoId }
        banco.enviados.removeAll { it.id == precoId }
    }

    override suspend fun nomesSugeridos(): List<NomeSugerido> =
        if (banco.nomeDemoDecidido) emptyList()
        else listOf(NomeSugerido("77777777000191", "Empório da Teresa", 1, "COMERCIO DE ALIMENTOS XYZ LTDA", "Rua Teresa, 100"))

    override suspend fun decidirNome(cnpj: String, nome: String, aprovar: Boolean) {
        banco.nomeDemoDecidido = true
    }

    override suspend fun pendentes(): List<PdvPendente> =
        banco.pdvs.filter { it.meu.status == StatusPdv.PENDENTE }.map { it.pendente }

    override suspend fun fotoAlvara(caminho: String): ByteArray =
        banco.pdvs.firstOrNull { it.pendente.alvaraPath == caminho }?.alvara ?: throw ErroAmigavel("Foto não encontrada.")

    override suspend fun aprovar(pdvId: String) = mudarStatus(pdvId, StatusPdv.APROVADO, null)

    override suspend fun rejeitar(pdvId: String, motivo: String) {
        if (motivo.isBlank()) throw ErroAmigavel("Informe o motivo.")
        mudarStatus(pdvId, StatusPdv.REJEITADO, motivo.trim())
    }

    override suspend fun precosDenunciados(): List<PrecoDenunciado> {
        delay(200)
        return DadosDemo.buscar(null, extras = banco.enviados)
            .filter { it.id in banco.denuncias && it.id !in banco.removidos }
            .map {
                PrecoDenunciado(
                    cotacaoId = it.id, produto = it.produto, precoCentavos = it.precoCentavos, pdvNome = it.pdvNome,
                    oficial = it.pdvCadastrado, parteDeCombo = it.parteDeCombo, denuncias = 1, comboOuPromocao = 0,
                    precoMudou = 0, produtoErrado = 0, outros = 1,
                )
            }
    }

    override suspend fun removerCotacao(cotacaoId: String, motivo: String?) {
        delay(200)
        banco.removidos += cotacaoId
        banco.denuncias -= cotacaoId
    }

    override suspend fun manterCotacao(cotacaoId: String) {
        delay(200)
        banco.denuncias -= cotacaoId
    }

    override suspend fun bannersDenunciados(): List<BannerDenunciado> {
        delay(200)
        return banco.bannersDenunciados.map { (id, par) ->
            val (b, motivo) = par
            BannerDenunciado(
                id, b.pdvNome, b.titulo, b.descricao, b.link, denuncias = 1,
                enganoso = if (motivo == "enganoso") 1 else 0, ofensivo = if (motivo == "ofensivo") 1 else 0,
                golpe = if (motivo == "golpe") 1 else 0, outros = if (motivo == null || motivo == "outro") 1 else 0,
            )
        }
    }

    override suspend fun pausarBanner(promocaoId: String, motivo: String?) {
        delay(200)
        banco.bannersPausados += promocaoId
        banco.bannersDenunciados -= promocaoId
    }

    override suspend fun manterBanner(promocaoId: String) {
        delay(200)
        banco.bannersDenunciados -= promocaoId
    }

    override suspend fun estabelecimentos(busca: String?): List<PdvAdmin> {
        delay(200)
        val termo = Texto.normalizar(busca.orEmpty())
        val digitos = busca.orEmpty().filter { it.isDigit() }
        return banco.pdvs.map { it.meu }
            .filter { it.status == StatusPdv.APROVADO || it.status == StatusPdv.SUSPENSO }
            .filter {
                termo.isEmpty() || Texto.normalizar(it.nomeFantasia).contains(termo) ||
                    (digitos.isNotEmpty() && it.cnpj.contains(digitos))
            }
            .map {
                PdvAdmin(
                    id = it.id, nomeFantasia = it.nomeFantasia, cnpj = it.cnpj, status = it.status,
                    motivoSuspensao = it.motivoSuspensao, cidade = "Petrópolis", donoNome = "Dono (demonstração)",
                    tipo = it.tipo,
                    donoEmail = null, itensPublicados = banco.precosPdv.size,
                )
            }
    }

    override suspend fun suspenderPdv(pdvId: String, motivo: String) {
        if (motivo.isBlank()) throw ErroAmigavel(ErrosServidor.traduzir("motivo_obrigatorio"))
        alterarMeu(pdvId) {
            if (it.status != StatusPdv.APROVADO) throw ErroAmigavel("Estabelecimento não encontrado.")
            it.copy(status = StatusPdv.SUSPENSO, motivoSuspensao = motivo.trim())
        }
    }

    override suspend fun reativarPdv(pdvId: String) = alterarMeu(pdvId) {
        if (it.status != StatusPdv.SUSPENSO) throw ErroAmigavel("Estabelecimento não encontrado.")
        it.copy(status = StatusPdv.APROVADO, motivoSuspensao = null)
    }

    private fun alterarMeu(pdvId: String, mudanca: (MeuPdv) -> MeuPdv) {
        val i = banco.pdvs.indexOfFirst { it.meu.id == pdvId }
        if (i < 0) throw ErroAmigavel("Estabelecimento não encontrado.")
        banco.pdvs[i] = banco.pdvs[i].let { it.copy(meu = mudanca(it.meu)) }
    }

    private fun mudarStatus(pdvId: String, status: StatusPdv, motivo: String?) {
        val i = banco.pdvs.indexOfFirst { it.meu.id == pdvId }
        if (i < 0) throw ErroAmigavel("Cadastro não encontrado.")
        banco.pdvs[i] = banco.pdvs[i].let { it.copy(meu = it.meu.copy(status = status, motivoRejeicao = motivo)) }
    }
}

data class DemoPdv(val meu: MeuPdv, val pendente: PdvPendente, val alvara: ByteArray)

class DemoNotaFiscalRepositorio(private val banco: DemoBanco) : NotaFiscalRepositorio {
    /** Na demonstração não há outra pessoa para confirmar: fica "aguardando". */
    override suspend fun sugerirNomePdv(cnpj: String, nome: String): Boolean = false

    override suspend fun lojasDoCnpj(cnpj: String): List<LojaResumo> = DadosDemo.lojasDoCnpj(cnpj)

    override suspend fun enviar(rascunho: RascunhoNf): Int {
        delay(400)
        val chave = rascunho.chaveAcesso.filter { it.isDigit() }.ifEmpty { null }
        if (chave != null && !banco.chavesEnviadas.add(chave)) throw ErroAmigavel("Esta nota fiscal já foi enviada.")
        val loja = rascunho.lojaId?.let { id ->
            chave?.let { DadosDemo.lojasDoCnpj(it.substring(6, 20)) }.orEmpty().firstOrNull { it.lojaId == id }
        }
        val agora = Instant.now()
        val itens = rascunho.itensParaEnvio()
        banco.enviados += itens.map { item ->
            Cotacao(
                id = "nf-" + UUID.randomUUID(),
                produto = item.produto,
                precoCentavos = item.precoCentavos,
                validade = Validade.daNotaFiscal(rascunho.dataNf),
                dataNf = rascunho.dataNf,
                obs = null,
                fonte = Fonte.USUARIO_NF,
                lojaId = loja?.lojaId,
                pdvId = loja?.pdvId,
                pdvNome = loja?.pdvNome ?: rascunho.pdvNome.trim(),
                lojaNome = loja?.lojaNome,
                endereco = loja?.endereco ?: rascunho.pdvEndereco.trim().ifEmpty { null },
                telefone = loja?.telefone,
                local = loja?.local,
                criadoEm = agora,
                parteDeCombo = item.parteDeCombo,
            )
        }
        return itens.size
    }
}

/** Demonstração: listas em memória; a busca inteligente usa os preços fictícios. */
class DemoListasRepositorio(private val banco: DemoBanco) : ListasRepositorio {
    private class Lista(val id: String, var nome: String, val itens: MutableList<ItemSalvo>, var atualizada: Instant)

    private val listas = mutableListOf<Lista>()

    private fun lista(id: String) = listas.firstOrNull { it.id == id } ?: throw ErroAmigavel("Lista não encontrada.")

    override suspend fun listas(): List<ListaResumo> =
        listas.sortedByDescending { it.atualizada }.map { ListaResumo(it.id, it.nome, it.itens.size, it.atualizada) }

    override suspend fun criar(nome: String): String {
        val l = Lista("lista-" + UUID.randomUUID(), nome.trim().ifEmpty { "Minha lista" }, mutableListOf(), Instant.now())
        listas += l
        return l.id
    }

    override suspend fun renomear(listaId: String, nome: String) {
        if (nome.isNotBlank()) lista(listaId).nome = nome.trim()
    }

    override suspend fun excluir(listaId: String) {
        listas.removeAll { it.id == listaId }
    }

    override suspend fun itens(listaId: String): List<ItemSalvo> = lista(listaId).itens.toList()

    override suspend fun adicionar(listaId: String, produto: String, quantidade: Double) {
        val l = lista(listaId)
        val i = l.itens.indexOfFirst { Texto.normalizar(it.produto) == Texto.normalizar(produto) }
        if (i >= 0) l.itens[i] = l.itens[i].copy(quantidade = l.itens[i].quantidade + quantidade)
        else l.itens += ItemSalvo("item-" + UUID.randomUUID(), produto.trim(), quantidade)
        l.atualizada = Instant.now()
    }

    override suspend fun alterarQuantidade(itemId: String, quantidade: Double) {
        listas.forEach { l ->
            val i = l.itens.indexOfFirst { it.id == itemId }
            if (i >= 0) l.itens[i] = l.itens[i].copy(quantidade = quantidade)
        }
    }

    override suspend fun remover(itemId: String) {
        listas.forEach { l -> l.itens.removeAll { it.id == itemId } }
    }

    override suspend fun sugerir(termo: String): List<SugestaoProduto> {
        if (Texto.normalizar(termo).length < 2) return emptyList()
        return DadosDemo.buscar(termo, extras = banco.enviados)
            .groupBy { Texto.normalizar(it.produto) }
            .map { (_, cs) -> SugestaoProduto(cs.first().produto, cs.minOf { it.precoCentavos }, cs.map { it.chaveLocal }.distinct().size) }
            .sortedWith(compareByDescending<SugestaoProduto> { it.lugares }.thenBy { it.produto })
            .take(8)
    }
}
