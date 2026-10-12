plugins { kotlin("multiplatform") version "2.1.20" }
kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
tasks.register<JavaExec>("runWindowsProbe") {
    dependsOn("jvmMainClasses")
    val target = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
    mainClass.set("poc.WindowsProbeKt")
    args(providers.gradleProperty("probeExe").getOrElse("build/native/Release/wpd-probe.exe"),
        providers.gradleProperty("probeCommand").getOrElse("devices"))
}
