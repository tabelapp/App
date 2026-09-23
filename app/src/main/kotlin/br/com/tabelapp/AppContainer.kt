package br.com.tabelapp

import android.content.Context
import br.com.tabelapp.dados.AuthRepositorio
import br.com.tabelapp.dados.CotacoesRepositorio
import br.com.tabelapp.dados.EncarteRepositorio
import br.com.tabelapp.dados.Imagens
import br.com.tabelapp.dados.LeitorTexto
import br.com.tabelapp.dados.Localizacao
import br.com.tabelapp.dados.NotaFiscalRepositorio
import br.com.tabelapp.dados.Preferencias
import br.com.tabelapp.dados.demo.DemoAuthRepositorio
import br.com.tabelapp.dados.demo.DemoBanco
import br.com.tabelapp.dados.demo.DemoCotacoesRepositorio
import br.com.tabelapp.dados.demo.DemoEncarteRepositorio
import br.com.tabelapp.dados.demo.DemoNotaFiscalRepositorio
import br.com.tabelapp.dados.supabase.SupabaseAuthRepositorio
import br.com.tabelapp.dados.supabase.SupabaseCotacoesRepositorio
import br.com.tabelapp.dados.supabase.SupabaseEncarteRepositorio
import br.com.tabelapp.dados.supabase.SupabaseNotaFiscalRepositorio
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.compose.auth.ComposeAuth
import io.github.jan.supabase.compose.auth.googleNativeLogin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Injeção de dependências manual (sem Hilt, para manter o projeto simples).
 *
 * Sem SUPABASE_URL configurado, o app sobe em MODO DEMONSTRAÇÃO com dados
 * fictícios — dá para instalar e navegar sem backend nenhum.
 */
class AppContainer(contexto: Context) {

    val modoDemo: Boolean = BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val preferencias = Preferencias(contexto)
    val localizacao = Localizacao(contexto)
    val imagens = Imagens(contexto)
    val leitorTexto = LeitorTexto(imagens)

    val auth: AuthRepositorio
    val cotacoes: CotacoesRepositorio
    val notasFiscais: NotaFiscalRepositorio
    val encartes: EncarteRepositorio

    init {
        if (modoDemo) {
            val banco = DemoBanco()
            auth = DemoAuthRepositorio()
            cotacoes = DemoCotacoesRepositorio(banco)
            notasFiscais = DemoNotaFiscalRepositorio(banco)
            encartes = DemoEncarteRepositorio(banco)
        } else {
            val googleConfigurado = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
            val supabase = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
                install(Auth)
                install(Postgrest)
                install(Storage)
                install(ComposeAuth) {
                    if (googleConfigurado) googleNativeLogin(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)
                }
            }
            auth = SupabaseAuthRepositorio(supabase, escopo, googleConfigurado)
            cotacoes = SupabaseCotacoesRepositorio(supabase)
            notasFiscais = SupabaseNotaFiscalRepositorio(supabase)
            encartes = SupabaseEncarteRepositorio(supabase)
        }
    }
}
