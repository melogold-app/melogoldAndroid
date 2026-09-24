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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.kotlin.serialization.json)
    testImplementation(libs.sqlite.bundled.jvm)
}
