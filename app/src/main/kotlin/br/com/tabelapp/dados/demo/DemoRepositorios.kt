package br.com.tabelapp.dados.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import br.com.tabelapp.core.Banner
import br.com.tabelapp.core.CadastroPdv
import br.com.tabelapp.core.Cnpj
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.core.SugestaoProduto
import br.com.tabelapp.core.ListaResumo
import br.com.tabelapp.core.ItemSalvo
import br.com.tabelapp.core.DadosReceita
import br.com.tabelapp.core.DadosDemo
import br.com.tabelapp.core.Texto
import br.com.tabelapp.core.Fonte
import br.com.tabelapp.core.LojaPdv
import br.com.tabelapp.core.LojaResumo
import br.com.tabelapp.core.MeuPdv
import br.com.tabelapp.core.NomeSugerido
import br.com.tabelapp.core.PagamentoPendente
import br.com.tabelapp.core.PdvPendente
import br.com.tabelapp.core.Planilha
import br.com.tabelapp.core.PromocaoPdv
import br.com.tabelapp.core.SimulacaoImportacao
import br.com.tabelapp.core.PrecoPdv
import br.com.tabelapp.core.RegrasCota
import br.com.tabelapp.core.SaldoCota
import br.com.tabelapp.core.StatusPdv
import br.com.tabelapp.core.TipoOperacao
import br.com.tabelapp.core.PontoGeo
import br.com.tabelapp.core.RascunhoNf
import br.com.tabelapp.core.Validade
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.dados.ListasRepositorio
import br.com.tabelapp.dados.EstadoSessao
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.PdvRepositorio
import br.com.tabelapp.dados.TipoConta
import br.com.tabelapp.dados.Usuario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Modo demonstração: roda sem Supabase configurado, com os dados fictícios
 * de Petrópolis. Qualquer e-mail/senha entra. Nada é salvo.
 */
class DemoAuthRepositorio : AuthRepositorio {
    private val _estado = MutableStateFlow<EstadoSessao>(EstadoSessao.Deslogado)
    override val estado: StateFlow<EstadoSessao> = _estado.asStateFlow()

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
        return DadosDemo.buscar(termo, extras = banco.enviados)
    }

    override suspend fun banners(termo: String?, posicao: PontoGeo?): List<Banner> =
        banco.promocoesDemo.filter { it.second.status == "ativa" }.map { (_, p) ->
            Banner(p.id, "Seu banner (demonstração)", p.titulo, p.descricao, null, p.link)
        } + listOf(
        Banner("demo-1", "Supermercado Serra Imperial", "Semana do hortifrúti",
            "Frutas e verduras com até 30% de desconto. Só até domingo!", null),
        Banner("demo-2", "Empório Itaipava", "Queijos e vinhos da serra",
            "Leve 3, pague 2 em queijos artesanais.", null),
    )

    override suspend fun registrarVisualizacao(bannerId: String) {}
}

/** Guarda em memória o que foi enviado na demonstração (some ao fechar o app). */
class DemoBanco {
    val enviados = mutableListOf<Cotacao>()
    val chavesEnviadas = mutableSetOf<String>()
    val pdvs = mutableListOf<DemoPdv>()
    val precosPdv = mutableListOf<PrecoPdv>()
    val operacoesUsadas = mutableMapOf<String, Int>()
    var nomeDemoDecidido = false
    val lojasEditadas = mutableMapOf<String, LojaPdv>()
    val promocoesDemo = mutableListOf<Pair<String, PromocaoPdv>>()
    val pacotesPendentes = mutableMapOf<String, String>()
    val pacotesPagos = mutableMapOf<String, Int>()
}

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

    override suspend fun cadastrar(dados: CadastroPdv, receita: DadosReceita?, alvaraJpeg: ByteArray, cnpjNoAlvara: Boolean) {
        delay(600)
        val cnpj = dados.cnpj.filter { it.isDigit() }
        banco.pdvs.removeAll { it.meu.cnpj == cnpj }
        val id = "pdv-" + UUID.randomUUID()
        banco.pdvs += DemoPdv(
            meu = MeuPdv(id, cnpj, dados.razaoSocial, dados.nomeFantasia.trim(), StatusPdv.PENDENTE, null, cnpjNoAlvara, Instant.now()),
            pendente = PdvPendente(
                id = id, cnpj = cnpj, razaoSocial = dados.razaoSocial, nomeFantasia = dados.nomeFantasia.trim(),
                endereco = listOf(dados.endereco, dados.bairro, dados.cidade).filter { it.isNotBlank() }.joinToString(", "),
                telefone = dados.telefone.ifBlank { null }, alvaraPath = id, cnpjConferidoNoAlvara = cnpjNoAlvara,
                situacaoReceita = receita?.situacao, razaoSocialReceita = receita?.razaoSocial,
                enderecoReceita = receita?.let { listOfNotNull(it.endereco, it.bairro, it.cidade, it.uf).joinToString(", ") },
                donoNome = "Você (demonstração)", donoEmail = null, enviadoEm = Instant.now(),
            ),
            alvara = alvaraJpeg,
        )
    }

    override suspend fun lojas(pdvId: String): List<LojaPdv> {
        val p = banco.pdvs.firstOrNull { it.meu.id == pdvId } ?: return emptyList()
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
        banco.lojasEditadas[loja.id] = loja.copy(endereco = completo, whatsapp = loja.whatsapp?.filter { it.isDigit() })
    }

    override suspend fun simularPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): SimulacaoImportacao {
        delay(300)
        val tipos = linhas.map { l ->
            val atual = banco.precosPdv.firstOrNull { it.lojaId == lojaId && Texto.normalizar(it.produto) == Texto.normalizar(l.produto) }
            TipoOperacao.classificar(atual?.precoCentavos, l.precoCentavos)
        }
        val operacoes = tipos.count { it.contaNaCota }
        val restantes = cota(pdvId, lojaId).restantes
        val faltam = (operacoes - restantes).coerceAtLeast(0)
        return SimulacaoImportacao(
            criados = tipos.count { it == TipoOperacao.CRIAR_ITEM }, aumentados = tipos.count { it == TipoOperacao.AUMENTAR_PRECO },
            diminuidos = tipos.count { it == TipoOperacao.DIMINUIR_PRECO }, inalterados = tipos.count { it == TipoOperacao.EDITAR_DADOS },
            operacoes = operacoes, restantes = restantes, cabeNaCota = faltam == 0,
            pacotesNecessarios = (faltam + RegrasCota.OPERACOES_POR_PACOTE - 1) / RegrasCota.OPERACOES_POR_PACOTE,
        )
    }

    override suspend fun importarPlanilha(pdvId: String, lojaId: String, linhas: List<Planilha.Linha>): Int {
        val simulacao = simularPlanilha(pdvId, lojaId, linhas)
        if (!simulacao.cabeNaCota) throw ErroAmigavel("As operações desta loja não bastam para esta planilha.")
        var usadas = 0
        linhas.forEach { usadas += salvarPreco(pdvId, lojaId, it.produto, it.precoCentavos, it.validade, it.obs) }
        return usadas
    }

    override suspend fun promocoes(pdvId: String): List<PromocaoPdv> = banco.promocoesDemo.filter { it.first == pdvId }.map { it.second }

    override suspend fun enviarArte(pdvId: String, jpeg: ByteArray): String = "$pdvId/demo-${UUID.randomUUID()}.jpg"

    override suspend fun criarPromocao(
        pdvId: String, titulo: String, descricao: String?, link: String?, artePath: String?,
        palavrasChave: List<String>, visualizacoes: Int,
    ): String {
        delay(300)
        val valor = when (visualizacoes) { 100 -> 1000L; 250 -> 2500L; 500 -> 5000L; else -> throw ErroAmigavel("Pacote inválido.") }
        if (titulo.isBlank()) throw ErroAmigavel("Dê um título para a promoção.")
        val pagamento = "pag-" + UUID.randomUUID()
        banco.promocoesDemo += pdvId to PromocaoPdv(
            "promo-" + UUID.randomUUID(), titulo.trim(), descricao, link, artePath, palavrasChave, "aguardando_pagamento",
            0, 0, pagamento, valor, visualizacoes,
        )
        return pagamento
    }

    override suspend fun excluirPromocao(promocaoId: String): Boolean {
        banco.promocoesDemo.removeAll { it.second.id == promocaoId }
        return true
    }

    override suspend fun comprarPacoteOperacoes(pdvId: String, lojaId: String): String {
        val id = "pag-" + UUID.randomUUID()
        banco.pacotesPendentes[id] = lojaId
        return id
    }

    override suspend fun pagamentosPendentes(): List<PagamentoPendente> =
        banco.promocoesDemo.mapNotNull { (_, p) ->
            p.pagamentoPendenteId?.let { PagamentoPendente(it, "pacote_visualizacoes", "PDV demonstração", "Banner: ${p.titulo}",
                p.visualizacoesPendentes ?: 0, p.valorPendenteCentavos ?: 0, "Você (demonstração)", null, Instant.now()) }
        } + banco.pacotesPendentes.keys.map {
            PagamentoPendente(it, "pacote_operacoes", "PDV demonstração", "+50 operações", 50, 1000, "Você (demonstração)", null, Instant.now())
        }

    override suspend fun confirmarPagamento(pagamentoId: String) {
        banco.pacotesPendentes.remove(pagamentoId)?.let { loja ->
            banco.pacotesPagos[loja] = (banco.pacotesPagos[loja] ?: 0) + RegrasCota.OPERACOES_POR_PACOTE
        }
        val i = banco.promocoesDemo.indexOfFirst { it.second.pagamentoPendenteId == pagamentoId }
        if (i >= 0) banco.promocoesDemo[i] = banco.promocoesDemo[i].let { (pdv, p) ->
            pdv to p.copy(status = "ativa", visualizacoesContratadas = p.visualizacoesContratadas + (p.visualizacoesPendentes ?: 0),
                pagamentoPendenteId = null, valorPendenteCentavos = null, visualizacoesPendentes = null)
        }
    }

    override suspend fun cancelarPagamento(pagamentoId: String) {
        banco.pacotesPendentes.remove(pagamentoId)
        val i = banco.promocoesDemo.indexOfFirst { it.second.pagamentoPendenteId == pagamentoId }
        if (i >= 0) banco.promocoesDemo[i] = banco.promocoesDemo[i].let { (pdv, p) ->
            pdv to p.copy(pagamentoPendenteId = null, valorPendenteCentavos = null, visualizacoesPendentes = null)
        }
    }

    override suspend fun precos(pdvId: String, lojaId: String): List<PrecoPdv> =
        banco.precosPdv.filter { it.lojaId == lojaId }.sortedBy { Texto.normalizar(it.produto) }

    override suspend fun cota(pdvId: String, lojaId: String): SaldoCota {
        val usadas = banco.operacoesUsadas[lojaId] ?: 0
        val pacote = (banco.pacotesPagos[lojaId] ?: 0) - (usadas - RegrasCota.GRATIS_POR_MES).coerceAtLeast(0)
        return SaldoCota(gratisUsadas = usadas.coerceAtMost(RegrasCota.GRATIS_POR_MES), saldoPacotes = pacote.coerceAtLeast(0))
    }

    override suspend fun salvarPreco(
        pdvId: String, lojaId: String, produto: String, precoCentavos: Long, validade: LocalDate?, obs: String?,
    ): Int {
        delay(300)
        val pdv = banco.pdvs.firstOrNull { it.meu.id == pdvId } ?: throw ErroAmigavel("Cadastro não encontrado.")
        if (pdv.meu.status != StatusPdv.APROVADO) throw ErroAmigavel("Seu cadastro ainda não foi aprovado.")
        val atual = banco.precosPdv.firstOrNull { it.lojaId == lojaId && Texto.normalizar(it.produto) == Texto.normalizar(produto) }
        val conta = TipoOperacao.classificar(atual?.precoCentavos, precoCentavos).contaNaCota
        val usadas = banco.operacoesUsadas[lojaId] ?: 0
        if (conta && cota(pdvId, lojaId).restantes <= 0) {
            throw ErroAmigavel("As operações desta loja acabaram. Compre +50 operações por R\$ 10 via Pix (valem 30 dias).")
        }
        if (conta) banco.operacoesUsadas[lojaId] = usadas + 1
        val item = PrecoPdv(
            id = atual?.id ?: ("pp-" + UUID.randomUUID()), lojaId = lojaId, produto = produto.trim(),
            precoCentavos = precoCentavos, validade = validade ?: Validade.padrao(LocalDate.now()),
            obs = obs?.trim()?.ifEmpty { null },
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
            criadoEm = Instant.now(),
        )
        return if (conta) 1 else 0
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
