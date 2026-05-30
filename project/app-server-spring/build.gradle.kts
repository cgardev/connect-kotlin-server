import com.google.protobuf.gradle.id

plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions-boot")
    id("com.google.protobuf") version "0.9.4"
}

val grpcVersion = "1.69.0"
val protobufVersion = "4.28.3"
val grpcKotlinVersion = "1.4.1"
val coroutinesVersion = "1.9.0"

dependencies {
    // The auto-configuration starter wires the Connect server and manages its
    // SmartLifecycle; it brings the Spring components and the core transitively.
    // (Import :project:lib-connect-server-spring instead to wire it manually.)
    implementation(project(":project:lib-connect-server-spring-boot-autoconfigure"))

    // Core Spring Boot only — no embedded servlet container; Netty (from the
    // Connect library) is the HTTP server.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // gRPC service definitions generated from the demo proto, for both the Java
    // (ImplBase) and Kotlin (coroutine) server APIs.
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    // Supplies javax.annotation.Generated referenced by the generated gRPC stubs.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Transport for the gRPC client used by Http2AndGrpcPortTest.
    testRuntimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")
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
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

// The Kotlin sources reference the generated gRPC service stubs, so code
// generation must complete before Kotlin compilation begins.
tasks.named("compileKotlin") {
    dependsOn("generateProto")
}
