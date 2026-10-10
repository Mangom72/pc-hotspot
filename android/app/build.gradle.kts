plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "kr.pc.hotspot"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.pc.hotspot"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
    }
    signingConfigs {
        create("localRelease") {
            storeFile = file(requireNotNull(System.getenv("HOTSPOT_KEYSTORE")) { "Run scripts/build-apk.sh" })
            storePassword = System.getenv("HOTSPOT_KEY_PASSWORD")
            keyAlias = "pc-hotspot"
            keyPassword = System.getenv("HOTSPOT_KEY_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("localRelease"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2") }
