plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

dependencies {
    implementation(libs.kotlin.coroutines)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
