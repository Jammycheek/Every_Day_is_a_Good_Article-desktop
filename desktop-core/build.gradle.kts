// UI-independent PC sync client: protocol v1 + the existing request-bound grant/recovery controller.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":"))
    api(project(":protocol"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
