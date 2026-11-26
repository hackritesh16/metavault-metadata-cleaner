import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.metavault.cleaner"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.metavault.cleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

/**
 * Download the OFL-licensed Bricolage Grotesque and Instrument Sans fonts used by the app (the same
 * static files as build #116, pinned to that commit). Cached under build/generated so no font
 * network request is made at runtime. Builds need network only on a clean checkout; downloads are
 * checked to be nonempty before being used as Android resources.
 */
val generatedFonts = layout.buildDirectory.dir("generated/aurora-fonts")
val prepareAuroraFonts = tasks.register("prepareAuroraFonts") {
    outputs.dir(generatedFonts)
    doLast {
        val directory = generatedFonts.get().asFile.resolve("font")
        directory.mkdirs()
        val base = "https://raw.githubusercontent.com/hackritesh16/metavault-metadata-cleaner/" +
            "af135039a0ed443363a0a35f671a5d03337eb5ca/android/app/src/main/res/font/"
        val fonts = listOf(
            "bricolage_grotesque_semibold", "bricolage_grotesque_bold",
            "instrument_sans_regular", "instrument_sans_medium", "instrument_sans_semibold"
        ).associate { "$it.ttf" to "$base$it.ttf" }
        fonts.forEach { (name, url) ->
            val output = directory.resolve(name)
            if (!output.exists() || output.length() < 10000L) {
                val temp = directory.resolve("$name.download")
                try {
                    URI(url).toURL().openConnection().apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }.getInputStream().use { input -> temp.outputStream().use(input::copyTo) }
                    require(temp.length() > 10000L) { "Invalid font download: $name" }
                    if (output.exists()) output.delete()
                    check(temp.renameTo(output)) { "Could not install $name" }
                } finally {
                    temp.delete()
                }
            }
        }
    }
}

android.sourceSets.getByName("main").res.srcDir(generatedFonts)
tasks.named("preBuild").configure { dependsOn(prepareAuroraFonts) }
