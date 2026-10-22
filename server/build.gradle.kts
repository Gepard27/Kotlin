plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "ru.campus.equipment"
version = "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = rootProject.group
    version = rootProject.version
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging { events("passed", "skipped", "failed") }
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ru.campus.equipment.ApplicationKt")
}

dependencies {
    implementation(project(":http"))
    implementation(project(":postgres"))
    implementation(project(":booking"))
    implementation(project(":inventory"))
    implementation(project(":identity"))
    implementation(project(":common"))
    implementation(libs.hikari)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.embedded.postgres) {
        exclude(group = "io.zonky.test.postgres")
    }
    val os = System.getProperty("os.name").lowercase()
    val platform = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "darwin"
        else -> "linux"
    }
    val architecture = if (System.getProperty("os.arch").contains("aarch64")) "arm64v8" else "amd64"
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-$platform-$architecture:${libs.versions.postgres.binaries.get()}")
    testImplementation(libs.jackson)
}

tasks.test {
    dependsOn(tasks.installDist)
    systemProperty("campus.installDir", layout.buildDirectory.dir("install/campus-equipment-server").get().asFile.absolutePath)
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
