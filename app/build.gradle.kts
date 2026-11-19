plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Assinatura permanente opcional: as credenciais vêm de Secrets do GitHub Actions.
// Não guardar JKS ou senhas no repositório.
val signingStoreFile = System.getenv("RAVO_SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("RAVO_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("RAVO_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("RAVO_SIGNING_KEY_PASSWORD")
val hasPersistentSigning = listOf(
    signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.moto.painel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moto.painel"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2) + 2
        versionName = "1.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (hasPersistentSigning) {
            create("ravoPersistent") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("ravoPersistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.11.0")
    testImplementation("junit:junit:4.13.2")
}
