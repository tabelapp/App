import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Chaves lidas de local.properties (não versionado) ou de variáveis de ambiente.
// Sem SUPABASE_URL o app roda em MODO DEMONSTRAÇÃO, com dados fictícios.
val locais = Properties().apply {
    val arquivo = rootProject.file("local.properties")
    if (arquivo.exists()) arquivo.inputStream().use { load(it) }
}
fun config(nome: String): String = locais.getProperty(nome) ?: System.getenv(nome) ?: ""

android {
    namespace = "br.com.tabelapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.tabelapp"
        minSdk = 26
        targetSdk = 36
        // Na Play Store cada envio precisa de um número maior que o anterior: a CI de
        // publicação passa VERSAO_CODIGO (contador do GitHub) e VERSAO_NOME (ex.: 1.0.0).
        versionCode = config("VERSAO_CODIGO").toIntOrNull() ?: 1
        versionName = config("VERSAO_NOME").ifBlank { "0.1.0" }

        buildConfigField("String", "SUPABASE_URL", "\"${config("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${config("SUPABASE_ANON_KEY")}\"")
        // Client ID do tipo "Web application" no Google Cloud (usado pelo login nativo do Google).
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${config("GOOGLE_WEB_CLIENT_ID")}\"")
        // Chave do Maps SDK for Android (Google Cloud), restrita ao pacote e à assinatura do app.
        // Sem ela, "Ver no mapa" avisa que o mapa ainda não foi configurado.
        buildConfigField("String", "MAPS_API_KEY", "\"${config("MAPS_API_KEY")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = config("MAPS_API_KEY")
    }

    // Chave de debug fixa no repositório: cada APK gerado pela CI tem a mesma assinatura,
    // então dá para instalar a versão nova por cima da antiga (sem desinstalar).
    // NÃO é a chave de publicação na Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Chave de ENVIO para a Play Store (upload key). Nunca fica no repositório: a CI
        // monta o arquivo a partir dos secrets ASSINATURA_*. Sem eles, o release sai sem
        // assinatura (serve só para conferir que compila).
        if (config("ASSINATURA_ARQUIVO").isNotBlank()) {
            create("envio") {
                storeFile = file(config("ASSINATURA_ARQUIVO"))
                storePassword = config("ASSINATURA_SENHA")
                keyAlias = config("ASSINATURA_ALIAS")
                keyPassword = config("ASSINATURA_SENHA_CHAVE").ifBlank { config("ASSINATURA_SENHA") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("envio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.compose.auth)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.play.services.code.scanner)
    implementation(libs.play.services.text.recognition)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)

    testImplementation(libs.junit)
}
