import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquo)
}

android {
    val appId = "${project.group}.android"

    namespace = appId
    compileSdk = 37

    val abis = listOf("arm64-v8a", "x86_64")
    val cmakeVersion = "4.1.2"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = appId

        minSdk = 24
        targetSdk = 37

        // 0.1.2 → 102: every release has a higher code, which the self-update compares (REWRITE §4.14)
        versionName = project.version.toString()
        versionCode = versionName!!.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

        multiDexEnabled = true

        // The Melogold server the app offers first (Settings › Server can point it elsewhere). Until the
        // official domain exists this is the owner's instance behind a sslip.io name (REWRITE §3.5.12)
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://178-250-187-202.sslip.io\"")

        // Where the self-update reads the latest release (REWRITE §4.14); empty: the build doesn't
        // update itself. Only the release build does: the others are other packages
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += abis
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            isUniversalApk = false
        }
    }

    signingConfigs {
        // The key of the releases on GitHub. It never enters the repository: its path and passwords
        // come from ~/.gradle/gradle.properties or the environment; without them the release build
        // is unsigned
        create("release") {
            fun secret(property: String, variable: String) =
                providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull

            secret("melogold.release.storeFile", "MELOGOLD_RELEASE_STORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = secret("melogold.release.storePassword", "MELOGOLD_RELEASE_STORE_PASSWORD")
                keyAlias = secret("melogold.release.keyAlias", "MELOGOLD_RELEASE_KEY_ALIAS")
                keyPassword = secret("melogold.release.keyPassword", "MELOGOLD_RELEASE_KEY_PASSWORD")
            }
        }

        create("ci") {
            storeFile = System.getenv("ANDROID_NIGHTLY_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_NIGHTLY_KEYSTORE_ALIAS")
            keyPassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            manifestPlaceholders["appName"] = "Melogold Debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "Melogold"
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                "\"" + providers.gradleProperty("melogold.updateManifestUrl")
                    .getOrElse("https://github.com/melogold-app/melogoldAndroid/releases/latest/download/update.json") + "\""
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"

            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-NIGHTLY"
            manifestPlaceholders["appName"] = "Melogold Nightly"
            signingConfig = signingConfigs.findByName("ci")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
        }

        // The release build (R8, not debuggable) signed with the debug key: it installs over the
        // debug app and keeps its data. For checks on slow devices and emulators, where a
        // debuggable app runs in the slowest interpreter
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += "release"

            applicationIdSuffix = ".debug"
            versionNameSuffix = "-STAGING"
            manifestPlaceholders["appName"] = "Melogold Debug"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        // Robolectric reads the merged resources and manifest (REWRITE §4.13)
        unitTests.isIncludeAndroidResources = true
        // A real backup for LegacyImporterTest, never committed: ./gradlew … -Pmelogold.importSample=/path/to.db
        // A Melogold server for LinkDeviceLiveTest: -Pmelogold.testServer=http://127.0.0.1:8787
        // Where the screen tests put their pictures: -Pmelogold.screenshots=/path/to/dir
        unitTests.all { test ->
            test.systemProperty("melogold.importSample", providers.gradleProperty("melogold.importSample").orNull.orEmpty())
            test.systemProperty("melogold.testServer", providers.gradleProperty("melogold.testServer").orNull.orEmpty())
            test.systemProperty("melogold.screenshots", providers.gradleProperty("melogold.screenshots").orNull.orEmpty())
        }
    }

    packaging {
        resources.excludes.add("META-INF/**/*")
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    externalNativeBuild {
        cmake {
            version = cmakeVersion
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

afterEvaluate {
    val jniLibs = file("${layout.projectDirectory}/src/main/jniLibs").also { it.mkdirs() }
    android.buildTypes.forEach { type ->
        val typeCapitalized = type.name.let {
            it.first().uppercase() + it.substring(1)
        }

        tasks.named("assemble${typeCapitalized}").configure {
            doFirst {
                val cxxDir =
                    file("${layout.buildDirectory.get()}/intermediates/cxx/${if (typeCapitalized == "Debug") "Debug" else "RelWithDebInfo"}")

                cxxDir.walkTopDown().forEach cxx@{ f ->
                    if (f.name != "qjs") return@cxx

                    f.copyTo(
                        target = jniLibs
                            .resolve(f.parentFile.name)
                            .also { it.mkdirs() }
                            .resolve("libqjs.so"), // disguise because fuck you
                        overwrite = true
                    )
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_5)

        freeCompilerArgs.addAll(
            "-Xconsistent-data-class-copy-visibility"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches FileDescriptor internals through jdk.internal.access (JDK 17+)
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}

composeCompiler {
    if (project.findProperty("enableComposeCompilerReports") == "true") {
        val dest = layout.buildDirectory.dir("compose_metrics")
        metricsDestination = dest
        reportsDestination = dest
    }
}

// region R2.4
chaquopy {
    defaultConfig {
        version = "3.14"
        pip {
            install("yt-dlp>=2026.08.19")
            install("yt-dlp-ejs>=0.8.0")
        }
    }
}
// endregion R2.4

dependencies {
    coreLibraryDesugaring(libs.desugaring)

    implementation(projects.compose.persist)
    implementation(projects.compose.preferences)
    implementation(projects.compose.routing)
    implementation(libs.reorderable)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.activity)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.material3)
    implementation(libs.compose.adaptive)

    implementation(libs.coil.compose)
    implementation(libs.coil.ktor)
    implementation(libs.ktor.client.core)
    // The Melogold server API (sync, account)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.material.color.utilities)

    implementation(libs.exoplayer)
    implementation(libs.exoplayer.workmanager)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.media)

    implementation(libs.lifecycle.process)

    implementation(libs.workmanager)
    implementation(libs.workmanager.ktx)

    // QR sign-in / device linking (task T2.5); the versions are owned by Phase 1
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.immutable)
    implementation(libs.kotlin.datetime)

    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.framework)

    implementation(libs.log4j)
    implementation(libs.slf4j)
    implementation(libs.logback)

    implementation(projects.providers.innertube)
    implementation(projects.providers.kugou)
    implementation(projects.providers.lrclib)
    implementation(projects.providers.sponsorblock)
    implementation(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.core.ui)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.espresso.core)
    // The empty activity the screen tests render in; a debug build only
    debugImplementation(libs.compose.ui.test.manifest)
}
