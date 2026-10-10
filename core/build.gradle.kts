import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Regras de negócio do Tabelapp em Kotlin puro: sem Android, sem rede.
// Tudo aqui é testável com `./gradlew :core:test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Lê o HTML da página de consulta da NFC-e (Sefaz).
    implementation(libs.jsoup)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
