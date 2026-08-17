import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sherpaVersion = "1.13.5"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
val sherpaSha256 = "6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b"

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val b = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            md.update(b, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        if (sherpaAar.isFile && sha256(sherpaAar).equals(sherpaSha256, true)) return@doLast
        sherpaAar.parentFile.mkdirs()
        val part = File(sherpaAar.parentFile, sherpaAar.name + ".part")
        val urls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar",
            "https://sourceforge.net/projects/sherpa-onnx.mirror/files/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar/download"
        )
        var last: Throwable? = null
        for (u in urls) {
            try {
                println("Fetching sherpa-onnx Android runtime $sherpaVersion from $u")
                var current = u
                var connection: HttpURLConnection? = null
                for (redirect in 0 until 8) {
                    connection?.disconnect()
                    val base = URL(current)
                    connection = base.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("User-Agent", "ClassHelperNative-build")
                    connection.connect()
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: error("Redirect without Location")
                        current = URL(base, location).toString()
                    } else {
                        break
                    }
                    if (redirect == 7) error("Too many redirects while fetching sherpa-onnx")
                }
                val c = connection ?: error("No connection")
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                c.inputStream.use { input -> part.outputStream().buffered(256 * 1024).use { output -> input.copyTo(output, 256 * 1024) } }
                c.disconnect()
                if (!sha256(part).equals(sherpaSha256, true)) error("sherpa-onnx AAR SHA-256 mismatch")
                if (sherpaAar.exists()) sherpaAar.delete()
                if (!part.renameTo(sherpaAar)) { part.copyTo(sherpaAar, overwrite = true); part.delete() }
                last = null
                break
            } catch (t: Throwable) {
                last = t
                part.delete()
            }
        }
        if (last != null || !sherpaAar.isFile) {
            throw GradleException("Unable to fetch sherpa-onnx $sherpaVersion AAR. Put the official AAR at ${sherpaAar.path}", last)
        }
    }
}

android {
    namespace = "io.github.paper.classhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.paper.classhelper"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 179
        versionName = "1.7.9-sensevoice"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            useLegacyPackaging = false
            // sherpa-onnx and onnxruntime-android both bundle libonnxruntime.so.
            // Resolve the APK merge conflict deterministically instead of failing mergeNativeLibs.
            pickFirsts += setOf("**/libonnxruntime.so")
        }
    }
}

tasks.named("preBuild").configure { dependsOn(fetchSherpaAar) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.14.0")

    implementation("io.github.ahmerafzal1:ahmer-pdfviewer:2.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(files(sherpaAar))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
}
