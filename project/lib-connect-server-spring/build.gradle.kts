import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Spring integration components (SmartLifecycle, configuration properties) for connect-kotlin-server."

dependencies {
    // The framework-agnostic core; re-exported so consumers get it transitively.
    api(project(":project:lib-connect-server"))

    // Spring context (SmartLifecycle) and Boot (@ConfigurationProperties).
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "connect-kotlin-server-spring"
    }
}
