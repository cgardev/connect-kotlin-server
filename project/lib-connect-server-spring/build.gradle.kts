plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
}

dependencies {
    // The framework-agnostic core; re-exported so consumers get it transitively.
    api(project(":project:lib-connect-server"))

    // Spring context (SmartLifecycle) and Boot (@ConfigurationProperties).
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
