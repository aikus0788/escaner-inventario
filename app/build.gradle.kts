plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.inventario.escaner"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.inventario.escaner"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions pasa el número de compilación; así cada versión publicada es mayor que la anterior.
        val compilacion = (project.findProperty("compilacion") as String?)?.toIntOrNull() ?: 2
        versionCode = compilacion
        versionName = "2.0.$compilacion"
    }

    // Llave de firma fija: así cada versión nueva se instala encima de la anterior sin perder datos.
    val llave = file("inventario.jks")
    signingConfigs {
        if (llave.exists()) {
            create("inventario") {
                storeFile = llave
                storePassword = System.getenv("STORE_PASSWORD") ?: ""
                keyAlias = "inventario"
                keyPassword = System.getenv("STORE_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (llave.exists()) "inventario" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
