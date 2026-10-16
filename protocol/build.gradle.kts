// Platform-neutral sync protocol v1. Pure Kotlin + java.security only, so the same sources
// compile for the Windows desktop, future macOS/Linux builds and (as a pinned copy) Android.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies { testImplementation(kotlin("test")) }

tasks.test { useJUnitPlatform() }
