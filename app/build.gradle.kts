plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qnetwing.unlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qnetwing.unlock"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }
    
    // <<< BLOCO ADICIONADO AQUI >>>
    // Configuração para o build nativo (C/C++)
    externalNativeBuild {
        cmake {
            // Aponta para o nosso arquivo CMakeLists.txt
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    // <<< FIM DO BLOCO ADICIONADO >>>

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
    
    sourceSets {
        getByName("main").assets.srcDirs("src/main/assets")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    
    implementation("com.google.android.gms:play-services-ads:23.1.0")
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")
    
    implementation("androidx.multidex:multidex:2.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
