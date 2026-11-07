plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.smerrybeta.toolanapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.smerrybeta.toolanapp"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.annotation.jvm)
    implementation(libs.navigation.runtime.android)
    implementation(libs.fragment)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WebSocket dependency
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    testImplementation(libs.junit)
    // JVM 单测里的 org.json（StreamMeta 序列化）；运行时用 Android 内置实现
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
