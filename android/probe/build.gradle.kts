plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.wobsoriano.museportal.probe"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.wobsoriano.museportal.probe"
    minSdk = 28
    targetSdk = 28
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  sourceSets["main"].kotlin.srcDir("../app/src/main/java/dev/wobsoriano/museportal/ble/gatt")
  sourceSets["main"].kotlin.srcDir("../app/src/main/java/dev/wobsoriano/museportal/audio/call")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(project(":muse"))
  implementation(project(":wake"))
  implementation(libs.vosk.android)
}
