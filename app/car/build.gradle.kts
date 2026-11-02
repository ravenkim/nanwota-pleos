plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 기획서·영상용 차량 화면 캡처 (에뮬레이터 없이): ./gradlew :car:recordPaparazziDebug
    id("app.cash.paparazzi")
}

android {
    namespace = "ai.nanwota.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.nanwota.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 서버 주소: -Pnanwota.server=https://... 로 바꾼다. 기본값은 에뮬레이터에서 본 PC의 개발 서버.
        val server = (project.findProperty("nanwota.server") as String?) ?: "http://10.0.2.2:8787"
        buildConfigField("String", "SERVER_URL", "\"$server\"")
    }

    // AAOS CarUxRestrictions (주행 중 QR 금지)
    useLibrary("android.car")

    signingConfigs {
        // 공모전 제출용 release APK. 키가 없으면 debug 키로 서명한다 (스토어 배포용 아님).
        create("contest") {
            val ks = rootProject.file("nanwota-release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = project.findProperty("nanwota.storePassword") as String? ?: ""
                keyAlias = "nanwota"
                keyPassword = project.findProperty("nanwota.keyPassword") as String? ?: ""
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("contest")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))

    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
