package com.metalogenia.gradle.common

plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.10"

dependencies {
    constraints {
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-test-junit5:${kotlinVersion}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
