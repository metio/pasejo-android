// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

import com.android.build.api.variant.AndroidComponentsExtension
import java.io.File

plugins {
    id("com.android.application")
}

// The pasejo workspace that holds pasejo-mobile; a sibling checkout by default.
val pasejoDir: File =
    rootProject.file(providers.gradleProperty("pasejo.dir").getOrElse("../pasejo")).canonicalFile
val cargoTarget: File = layout.buildDirectory.dir("cargo").get().asFile
val minSdkLevel = 33
val abis = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")

android {
    namespace = "wtf.metio.pasejo"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "wtf.metio.pasejo"
        minSdk = minSdkLevel
        targetSdk = 36
        versionCode = 27890647
        versionName = "2026.11.19192407"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis.keys }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

/** Runs `command` in the pasejo workspace and fails the build when it fails. */
fun run(command: List<String>, environment: Map<String, String> = emptyMap()) {
    val process = ProcessBuilder(command)
        .directory(pasejoDir)
        .inheritIO()
        .apply {
            environment().putAll(environment)
            // askama resolves its templates through canonical paths, so cargo's
            // home must be spelled canonically where it sits behind a symlink.
            System.getenv("CARGO_HOME")?.let { environment()["CARGO_HOME"] = File(it).canonicalPath }
            environment()["CARGO_TARGET_DIR"] = cargoTarget.path
        }
        .start()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed" }
}

abstract class CargoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val ndkDir = extensions.getByType(AndroidComponentsExtension::class.java).sdkComponents.ndkDirectory

val cargoAndroid by tasks.registering(CargoTask::class) {
    description = "Cross-compiles pasejo-mobile for every ABI the app ships."
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    inputs.dir(File(pasejoDir, "crates"))
    inputs.file(File(pasejoDir, "Cargo.lock"))
    doLast {
        val bin = ndkDir.get().asFile.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        for ((abi, triple) in abis) {
            val clang = bin.resolve("$triple$minSdkLevel-clang").path
            val key = triple.uppercase().replace('-', '_')
            val lower = triple.replace('-', '_')
            run(
                listOf("cargo", "build", "--locked", "--release", "--package", "pasejo-mobile", "--lib", "--target", triple),
                mapOf(
                    "CARGO_TARGET_${key}_LINKER" to clang,
                    "CARGO_TARGET_${key}_RUSTFLAGS" to "-C link-arg=-Wl,-z,max-page-size=16384",
                    "CC_$lower" to clang,
                    "AR_$lower" to bin.resolve("llvm-ar").path,
                    "CARGO_PROFILE_RELEASE_STRIP" to "symbols",
                ),
            )
            val library = cargoTarget.resolve("$triple/release/libpasejo_mobile.so")
            library.copyTo(outputDir.get().asFile.resolve("$abi/libpasejo_mobile.so"), overwrite = true)
        }
    }
}

val cargoHost by tasks.registering {
    description = "Builds pasejo-mobile and the binding generator for this computer."
    inputs.dir(File(pasejoDir, "crates"))
    inputs.file(File(pasejoDir, "Cargo.lock"))
    outputs.file(cargoTarget.resolve("debug/libpasejo_mobile.so"))
    outputs.file(cargoTarget.resolve("debug/uniffi-bindgen"))
    doLast {
        run(listOf("cargo", "build", "--locked", "--package", "pasejo-mobile", "--features", "bindgen"))
    }
}

val bindings by tasks.registering(CargoTask::class) {
    description = "Generates the Kotlin bindings of pasejo-mobile."
    dependsOn(cargoHost)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi"))
    doLast {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        run(
            listOf(
                cargoTarget.resolve("debug/uniffi-bindgen").path,
                "generate",
                cargoTarget.resolve("debug/libpasejo_mobile.so").path,
                "--language",
                "kotlin",
                "--no-format",
                "--out-dir",
                out.path,
            ),
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(bindings, CargoTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoAndroid, CargoTask::outputDir)
    }
}

val cargoServer by tasks.registering {
    description = "Builds the pasejo server that the JVM tests run against."
    inputs.dir(File(pasejoDir, "crates"))
    inputs.file(File(pasejoDir, "Cargo.lock"))
    outputs.file(cargoTarget.resolve("debug/pasejo-server"))
    doLast {
        run(listOf("cargo", "build", "--locked", "--package", "pasejo-server", "--bin", "pasejo-server"))
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHost, cargoServer)
    systemProperty("jna.library.path", cargoTarget.resolve("debug").path)
    systemProperty("pasejo.server", cargoTarget.resolve("debug/pasejo-server").path)
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
