import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        namespace = "com.kyant.backdrop"
        compileSdk = 37
        buildToolsVersion = "37.0.0"
        minSdk = 28
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
    sourceSets.androidMain.dependencies {
        implementation("io.github.kyant0:shapes:1.2.1")
        implementation("org.jetbrains:annotations:26.1.0")
    }
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
        implementation("io.github.kyant0:shapes:1.2.1")
        implementation("org.jetbrains:annotations:26.1.0")
    }
}
