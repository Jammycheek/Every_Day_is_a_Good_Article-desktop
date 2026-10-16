// Windows-first Compose Multiplatform desktop app. Platform adapters (WPD mailbox, DPAPI storage)
// sit behind interfaces so macOS/Linux adapters can be added without touching the sync core.
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":desktop-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "everyarticle.desktop.MainKt"
    }
}
