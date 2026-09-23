plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "app.melogold.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation(projects.core.data)

    implementation(libs.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.shimmer)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.material3)
    implementation(libs.palette)
    implementation(libs.material.color.utilities)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
