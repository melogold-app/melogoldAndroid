plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

dependencies {
    implementation(projects.ktorClientBrotli)
    implementation(projects.providers.common)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.client.serialization)
    implementation(libs.ktor.serialization.json)
    implementation(libs.log4j)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    // Rule vectors shared with the desktops (docs/spec/README.md)
    systemProperty("melogold.specDir", rootProject.file("docs/spec").absolutePath)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
