package br.com.tabelapp.erros

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import br.com.tabelapp.R
import br.com.tabelapp.core.ClassificadorErros
import br.com.tabelapp.core.ErrosServidor
import br.com.tabelapp.core.LogSeguro
import br.com.tabelapp.core.TipoErro
import br.com.tabelapp.dados.ErroAmigavel
import java.io.File
import java.time.LocalDateTime

/**
 * Tratamento de erros do app: o usuário só vê mensagens calmas (strings.xml);
 * o detalhe técnico vai para o log interno ([registrar]), sem CPF.
 */
object Erros {
    private lateinit var contexto: Context
    private val arquivo: File by lazy { File(contexto.filesDir, "erros.log") }

    fun iniciar(app: Context) {
        contexto = app.applicationContext
    }

    // ------------------------------------------------------------------ log interno

    /** Grava o erro técnico no log do Android e num arquivo interno (máx. ~200 KB). */
    fun registrar(onde: String, detalhe: String?, erro: Throwable? = null) {
        val linha = LogSeguro.limpar(
            "${LocalDateTime.now()} [$onde] ${detalhe.orEmpty()} ${erro?.let { "${it.javaClass.name}: ${it.message}" }.orEmpty()}"
        )
        Log.w("Tabelapp", linha, erro)
        if (!::contexto.isInitialized) return
        runCatching {
            if (arquivo.length() > 200_000) arquivo.writeText(arquivo.readLines().takeLast(500).joinToString("\n") + "\n")
            arquivo.appendText(linha + "\n")
        }
    }

    // ------------------------------------------------------------------ conexão

    fun temInternet(): Boolean {
        if (!::contexto.isInitialized) return true
        val cm = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val rede = cm.activeNetwork ?: return false
        val capacidades = cm.getNetworkCapabilities(rede) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------ textos

    fun titulo(tipo: TipoErro): String = texto(Textos.titulo(tipo))
    fun texto(tipo: TipoErro): String = texto(Textos.texto(tipo))

    /** Aviso quando o leitor de QR Code do Google não abre (primeiro uso, Play Services baixando). */
    fun leitorIndisponivel(): String = texto(R.string.erro_leitor_indisponivel)

    private fun texto(id: Int): String =
        if (::contexto.isInitialized) contexto.getString(id) else ErrosServidor.GENERICA

    // ------------------------------------------------------------------ servidor

    /**
     * Converte uma falha numa chamada ao servidor em [ErroAmigavel]: regras de
     * negócio conhecidas mantêm a mensagem própria; o resto vira um tipo calmo
     * (sem internet, instabilidade, sessão, inesperado). Sempre registra no log.
     */
    fun doServidor(e: Throwable, statusHttp: Int? = null, onde: String = "servidor"): ErroAmigavel {
        if (e is ErroAmigavel) return e
        val tipo = ClassificadorErros.deServidor(e.javaClass.name, e.message, statusHttp, temInternet())
        registrar(onde, "status=$statusHttp tipo=$tipo", e)
        return if (tipo == null) ErroAmigavel(ErrosServidor.traduzir(e.message), e, codigo = ErrosServidor.codigo(e.message))
        else ErroAmigavel(texto(tipo), e, tipo)
    }
}

/** Ligação entre cada [TipoErro] e os textos do strings.xml. */
object Textos {
    fun titulo(tipo: TipoErro): Int = when (tipo) {
        TipoErro.SEFAZ_INDISPONIVEL -> R.string.erro_sefaz_indisponivel_titulo
        TipoErro.SEM_INTERNET -> R.string.erro_sem_internet_titulo
        TipoErro.CAMERA_NEGADA -> R.string.erro_camera_negada_titulo
        TipoErro.QR_INVALIDO -> R.string.erro_qr_invalido_titulo
        TipoErro.SERVIDOR_INSTAVEL -> R.string.erro_servidor_instavel_titulo
        TipoErro.SESSAO_EXPIRADA -> R.string.erro_sessao_expirada_titulo
        TipoErro.BUSCA_VAZIA -> R.string.erro_busca_vazia_titulo
        TipoErro.FALHA_UPLOAD_FOTO -> R.string.erro_falha_upload_foto_titulo
        TipoErro.FALHA_ENVIO_NF -> R.string.erro_falha_envio_nf_titulo
        TipoErro.FALHA_PAGAMENTO -> R.string.erro_falha_pagamento_titulo
        TipoErro.INESPERADO -> R.string.erro_inesperado_titulo
    }

    fun texto(tipo: TipoErro): Int = when (tipo) {
        TipoErro.SEFAZ_INDISPONIVEL -> R.string.erro_sefaz_indisponivel_texto
        TipoErro.SEM_INTERNET -> R.string.erro_sem_internet_texto
        TipoErro.CAMERA_NEGADA -> R.string.erro_camera_negada_texto
        TipoErro.QR_INVALIDO -> R.string.erro_qr_invalido_texto
        TipoErro.SERVIDOR_INSTAVEL -> R.string.erro_servidor_instavel_texto
        TipoErro.SESSAO_EXPIRADA -> R.string.erro_sessao_expirada_texto
        TipoErro.BUSCA_VAZIA -> R.string.erro_busca_vazia_texto
        TipoErro.FALHA_UPLOAD_FOTO -> R.string.erro_falha_upload_foto_texto
        TipoErro.FALHA_ENVIO_NF -> R.string.erro_falha_envio_nf_texto
        TipoErro.FALHA_PAGAMENTO -> R.string.erro_falha_pagamento_texto
        TipoErro.INESPERADO -> R.string.erro_inesperado_texto
    }

    fun dica(tipo: TipoErro): Int? = when (tipo) {
        TipoErro.SEFAZ_INDISPONIVEL -> R.string.erro_sefaz_indisponivel_dica
        else -> null
    }
}

/** Erros transitórios (rede/instabilidade): o pedido pode ser repetido do jeito que está. */
val ErroAmigavel.transitorio: Boolean
    get() = tipo == TipoErro.SEM_INTERNET || tipo == TipoErro.SERVIDOR_INSTAVEL || tipo == TipoErro.INESPERADO
