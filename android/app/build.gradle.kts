import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Lokaler, selbstsignierter Test-Keystore fuer installierbare Release-Builds auf
// diesem Entwicklungsrechner (siehe local.properties). NICHT der Play-Store-
// Upload-Keystore -- der ist ein separater, noch offener Schritt (STATUS.md).
// Fehlt local.properties oder einer der Keys (z. B. auf einem anderen Rechner
// oder in CI), bleibt der Release-Build unsigniert statt zu brechen.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val testKeystorePath = localProperties.getProperty("mukkeklopper.testKeystore.path")
val testKeystoreFile = testKeystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "de.schliemannosaurusrex.mukkeklopper"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.schliemannosaurusrex.mukkeklopper"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (testKeystoreFile != null) {
            create("testRelease") {
                storeFile = testKeystoreFile
                storePassword = localProperties.getProperty("mukkeklopper.testKeystore.storePassword")
                keyAlias = localProperties.getProperty("mukkeklopper.testKeystore.keyAlias")
                keyPassword = localProperties.getProperty("mukkeklopper.testKeystore.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (testKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("testRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.sshj)
    // sshj deklariert eddsa/bcprov/slf4j-api nicht im Compile-Scope; SshKeys.kt,
    // MukkeKlopperApplication (BouncyCastleProvider) und die SLF4J-Bridge (debug/) brauchen die Typen
    implementation(libs.eddsa)
    implementation(libs.bcprov)
    implementation(libs.slf4j.api)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
