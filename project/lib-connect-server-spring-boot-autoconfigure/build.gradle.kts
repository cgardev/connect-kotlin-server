import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Spring Boot auto-configuration starter for connect-kotlin-server."

dependencies {
    // The Spring integration components; re-exported so importing this module alone
    // brings everything needed.
    api(project(":project:lib-connect-server-spring"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "connect-kotlin-server-spring-boot-autoconfigure"
    }
}
