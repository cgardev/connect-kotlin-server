plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
}

dependencies {
    // The Spring integration components; re-exported so importing this module alone
    // brings everything needed.
    api(project(":project:lib-connect-server-spring"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
