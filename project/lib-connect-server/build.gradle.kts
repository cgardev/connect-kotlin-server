plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    `maven-publish`
    signing
}

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

// ----- Publishing -------------------------------------------------------------------------

java {
    withSourcesJar()
    withJavadocJar()
}

val githubOwner = "cgardev"
val githubRepo = "connect-kotlin-server"
val projectUrl = "https://github.com/$githubOwner/$githubRepo"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "connect-kotlin-server"
            pom {
                name.set("connect-kotlin-server")
                description.set(
                    "Server-side Connect (connectrpc) protocol implementation for the JVM: " +
                        "serve gRPC services over Connect, Connect streaming and gRPC-Web.",
                )
                url.set(projectUrl)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("cgardev")
                        name.set("Cristian Garcia")
                    }
                }
                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:$projectUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/$githubOwner/$githubRepo.git")
                }
            }
        }
    }
    repositories {
        // Ready out of the box in CI via the repository GITHUB_TOKEN.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$githubOwner/$githubRepo")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
        // Maven Central (Sonatype OSSRH). Requires a verified namespace + signing key.
        maven {
            name = "MavenCentral"
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = providers.environmentVariable("OSSRH_USERNAME").orNull
                password = providers.environmentVariable("OSSRH_PASSWORD").orNull
            }
        }
    }
}

// Sign only when a key is supplied (Maven Central), so GitHub Packages publishing
// and local builds work without GPG configured.
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
