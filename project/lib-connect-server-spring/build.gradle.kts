plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
}

dependencies {
    // The framework-agnostic core; re-exported so consumers get it transitively.
    api(project(":project:lib-connect-server"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
