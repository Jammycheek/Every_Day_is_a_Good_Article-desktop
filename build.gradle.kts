plugins { kotlin("multiplatform") version "2.1.20" }
kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
