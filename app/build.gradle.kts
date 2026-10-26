import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.eribssz.passage"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.eribssz.passage"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.4.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("PASSAGE_KEYSTORE") ?: "../keys/passage.jks")
            storePassword = System.getenv("PASSAGE_KS_PASS") ?: "passage"
            keyAlias = "passage"
            keyPassword = System.getenv("PASSAGE_KS_PASS") ?: "passage"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")

    // Headless screenshot rendering of the UI (no emulator needed)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android.testOptions.unitTests {
    isIncludeAndroidResources = true
    all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2"); it.maxHeapSize = "3g"; it.systemProperty("shots.dir", rootProject.file("shots").path) }
}
