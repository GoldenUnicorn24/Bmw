plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val signing = listOf("NIGHTDESK_KEYSTORE", "NIGHTDESK_STORE_PASSWORD", "NIGHTDESK_KEY_ALIAS", "NIGHTDESK_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val canSign = signing.values.all { !it.isNullOrBlank() }

android {
    namespace = "de.nightdesk.trade"
    compileSdk = 35
    defaultConfig {
        applicationId = "de.nightdesk.trade"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (canSign) {
            create("nightdesk") {
                storeFile = file(signing.getValue("NIGHTDESK_KEYSTORE")!!)
                storePassword = signing.getValue("NIGHTDESK_STORE_PASSWORD")
                keyAlias = signing.getValue("NIGHTDESK_KEY_ALIAS")
                keyPassword = signing.getValue("NIGHTDESK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".paper"
            versionNameSuffix = "-papier"
            buildConfigField("boolean", "LIVE_ENABLED", "false")
        }
        release {
            if (canSign) signingConfig = signingConfigs.getByName("nightdesk")
            buildConfigField("boolean", "LIVE_ENABLED", canSign.toString())
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
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
