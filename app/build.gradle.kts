buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "io.github.tatselkrik.statis130lite"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.tatselkrik.statis130lite"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

abstract class AppLicenseAssets : DefaultTask() {
    @get:InputFile abstract val licenseFile: org.gradle.api.file.RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @TaskAction fun copyLicense() {
        val destination = outputDirectory.get().asFile
        destination.mkdirs()
        licenseFile.get().asFile.copyTo(destination.resolve("LICENSE.txt"), overwrite = true)
    }
}
androidComponents {
    onVariants { variant ->
        val licenseAssets = tasks.register<AppLicenseAssets>("copy${variant.name.replaceFirstChar { it.uppercase() }}AppLicense") {
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            outputDirectory.set(layout.buildDirectory.dir("generated/licenseAssets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, AppLicenseAssets::outputDirectory)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("org.apache.commons:commons-statistics-inference:1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
