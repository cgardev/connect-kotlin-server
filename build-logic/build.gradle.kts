plugins {
    `kotlin-dsl`
}

// Repositories needed for the build-logic module itself
repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://repo.spring.io/milestone") }
}

// Gradle plugins used by the convention plugins declared in this module
dependencies {
    // Spring (Dependency Management)
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")

    // Kotlin (JetBrains)
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.0")
}
