import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun resolveSigningValue(propKey: String, envKey: String): String? {
    return keystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
}

val releaseStoreFilePath = resolveSigningValue("storeFile", "LAGUKU_KEYSTORE_FILE")
val releaseStorePassword = resolveSigningValue("storePassword", "LAGUKU_KEYSTORE_PASSWORD")
val releaseKeyAlias = resolveSigningValue("keyAlias", "LAGUKU_KEY_ALIAS")
val releaseKeyPassword = resolveSigningValue("keyPassword", "LAGUKU_KEY_PASSWORD")

val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.laguku.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.laguku.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

gradle.taskGraph.whenReady {
    val isReleasePackageTask = allTasks.any {
        it.name.contains("Release", ignoreCase = true) &&
            (it.name.contains("assemble", ignoreCase = true) ||
                it.name.contains("bundle", ignoreCase = true))
    }
    if (isReleasePackageTask && !hasReleaseSigning) {
        throw GradleException(
            "Konfigurasi signing release belum lengkap. Isi keystore.properties atau variabel lingkungan LAGUKU_KEYSTORE_*."
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}