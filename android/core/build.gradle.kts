// Pure Kotlin/JVM module: the feed contract (SPEC.md) with no Android imports, so it is
// unit-testable with plain `./gradlew :core:test` and no emulator.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The conformance test walks up from here to find the repo's conformance/ folder.
    systemProperty("wristfeed.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
