import com.google.protobuf.gradle.id

plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions-boot")
    id("com.google.protobuf") version "0.9.4"
}

val grpcVersion = "1.69.0"
val protobufVersion = "4.28.3"

dependencies {
    implementation(project(":project:lib-connect-server"))

    // Core Spring Boot only — no embedded servlet container; Netty (from the
    // Connect library) is the HTTP server.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // gRPC service definitions generated from the demo proto.
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // Supplies javax.annotation.Generated referenced by the generated gRPC stubs.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

// The Kotlin sources reference the generated gRPC service stubs, so code
// generation must complete before Kotlin compilation begins.
tasks.named("compileKotlin") {
    dependsOn("generateProto")
}
