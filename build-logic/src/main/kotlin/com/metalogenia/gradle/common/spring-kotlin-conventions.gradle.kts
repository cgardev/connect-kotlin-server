package com.metalogenia.gradle.common

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

val kotlinVersion = "2.2.10"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))
    constraints {
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-test-junit5:${kotlinVersion}")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
