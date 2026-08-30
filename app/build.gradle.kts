plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val focusFlowSigningStore = System.getenv("FOCUSFLOW_SIGNING_STORE_FILE")
val focusFlowCiRun = System.getenv("GITHUB_RUN_NUMBER") ?: "local"

android {
    namespace = "com.sakata.focusflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakata.focusflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 465
        versionName = "6.3.0"
        buildConfigField("String", "CI_RUN_NUMBER", "\"$focusFlowCiRun\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true; buildConfig = true }

    signingConfigs {
        if (!focusFlowSigningStore.isNullOrBlank()) {
            create("focusFlowStable") {
                storeFile = file(requireNotNull(focusFlowSigningStore))
                storePassword = System.getenv("FOCUSFLOW_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("FOCUSFLOW_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("FOCUSFLOW_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!focusFlowSigningStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("focusFlowStable")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
