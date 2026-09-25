plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val focusFlowSigningStore = System.getenv("FOCUSFLOW_SIGNING_STORE_FILE")
val focusFlowCiRun = System.getenv("GITHUB_RUN_NUMBER") ?: "local"

android {
    namespace = "com.sakata.focusflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sakata.focusflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 557
        versionName = "8.3.0-rc.23"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

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
        getByName("release") {
            isDebuggable = false
            // Keep this stability release behavior-equivalent; defer shrinking to a separate change.
            isMinifyEnabled = false
            if (!focusFlowSigningStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("focusFlowStable")
        }
        getByName("debug") {
            if (!focusFlowSigningStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("focusFlowStable")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
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
    implementation(libs.haze)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
