plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bac9j.bratan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bac9j.bratan"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        // [OPT] Helio G99 = arm64. Убирает нативные библиотеки TFLite для остальных ABI (APK меньше на ~6-8 МБ).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Личное использование: подпись автоматически создаваемым debug-ключом, APK ставится сразу.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Обязательно: иначе assets.openFd() для .tflite падает (файл сжимается), и memory-mapping не работает.
    androidResources { noCompress += listOf("tflite") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
