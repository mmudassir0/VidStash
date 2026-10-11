plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mudassir.ytdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mudassir.ytdownloader"
        minSdk = 24
        targetSdk = 35
        // Every CI build gets a higher number, so each new APK installs as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + build
        versionName = "1.2.$build"
    }

    signingConfigs {
        // A fixed key committed with the app. GitHub's runners make a brand-new debug key
        // on every build, and Android refuses to update an app signed with a different key.
        // Personal-use app only: this key is public, like a debug key.
        create("personal") {
            storeFile = file("ytdownloader.keystore")
            storePassword = "ytdownloader"
            keyAlias = "ytdownloader"
            keyPassword = "ytdownloader"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        // NewPipeExtractor needs java.nio APIs on Android < 13
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    // MP3 encoding (pure Java port of LAME)
    implementation("de.sciss:jump3r:1.0.5")
    testImplementation("junit:junit:4.13.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("io.coil-kt:coil:2.7.0")

    // Built-in player with background audio
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
