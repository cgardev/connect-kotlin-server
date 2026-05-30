import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Server-side Connect (connectrpc) protocol implementation for the JVM: " +
    "serve gRPC services over Connect, Connect streaming and gRPC-Web."

val grpcVersion = "1.69.0"
val protobufVersion = "4.28.3"
val jacksonVersion = "2.18.2"
val slf4jVersion = "2.0.16"
val nettyVersion = "4.1.115.Final"

dependencies {
    // gRPC: in-process server/channel, stub helpers (ClientCalls/ServerCalls), protobuf marshallers.
    api("io.grpc:grpc-api:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-core:$grpcVersion")
    implementation("io.grpc:grpc-inprocess:$grpcVersion")

    // Protocol buffers: message handling and proto <-> JSON conversion.
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")

    // Netty: the embedded HTTP transport that terminates the Connect protocols.
    api("io.netty:netty-codec-http:$nettyVersion")

    // JSON for the Connect error envelope.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The Spring Boot BOM does not manage gRPC/protobuf; pin them consistently so the
// in-process marshallers and the protobuf-java runtime never diverge.
configurations.all {
    resolutionStrategy {
        force(
            "com.google.protobuf:protobuf-java:$protobufVersion",
            "io.grpc:grpc-api:$grpcVersion",
            "io.grpc:grpc-core:$grpcVersion",
            "io.grpc:grpc-protobuf:$grpcVersion",
            "io.grpc:grpc-stub:$grpcVersion",
            "io.grpc:grpc-inprocess:$grpcVersion",
        )
    }
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "connect-kotlin-server"
    }
}
