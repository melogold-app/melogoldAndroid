plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    // The only androidx dependency allowed here: the SQLiteConnection interface for the importer
    // (REWRITE §4.1). Everything else stays pure JVM so the desktop clients can port the rules
    implementation(libs.sqlite)
    // The voice commands time their searches out (tasks/0006-gemini-app-functions.md)
    implementation(libs.kotlin.coroutines)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.kotlin.serialization.json)
    testImplementation(libs.sqlite.bundled.jvm)
}

tasks.test {
    // Rule vectors shared with the server and the desktops (docs/spec/README.md)
    systemProperty("melogold.specDir", rootProject.file("docs/spec").absolutePath)
}
