plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val moexStoreFile = providers.environmentVariable("MOEX_SIGNING_KEYSTORE_FILE").orNull
val moexStorePassword = providers.environmentVariable("MOEX_SIGNING_STORE_PASSWORD").orNull
val moexKeyAlias = providers.environmentVariable("MOEX_SIGNING_KEY_ALIAS").orNull
val moexKeyPassword = providers.environmentVariable("MOEX_SIGNING_KEY_PASSWORD").orNull

val hasMoexSigning = !moexStoreFile.isNullOrBlank() &&
    !moexStorePassword.isNullOrBlank() &&
    !moexKeyAlias.isNullOrBlank() &&
    !moexKeyPassword.isNullOrBlank()

android {
    namespace = "ru.moex.tradinglab"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.moex.tradinglab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasMoexSigning) {
            create("moex") {
                storeFile = file(moexStoreFile!!)
                storePassword = moexStorePassword
                keyAlias = moexKeyAlias
                keyPassword = moexKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasMoexSigning) {
                signingConfig = signingConfigs.getByName("moex")
            }
        }
        getByName("release") {
            if (hasMoexSigning) {
                signingConfig = signingConfigs.getByName("moex")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
