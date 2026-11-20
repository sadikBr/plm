// Base conventions for every Kotlin project in the build.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "com.plm"

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Spring Boot's BOM is the single source of truth for third-party versions.
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    "testImplementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    "testImplementation"(lib("spring-boot-starter-test"))
    "testRuntimeOnly"(lib("junit-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
