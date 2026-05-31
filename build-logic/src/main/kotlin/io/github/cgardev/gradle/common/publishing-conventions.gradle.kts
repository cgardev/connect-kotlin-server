package io.github.cgardev.gradle.common

import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `maven-publish`
    signing
}

// Source and Javadoc jars are required for Maven Central and useful elsewhere.
plugins.withType<JavaPlugin> {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
}

val githubOwner = "cgardev"
val githubRepo = "connect-kotlin-server"
val projectUrl = "https://github.com/$githubOwner/$githubRepo"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // artifactId and description are set per module; the POM reads them lazily.
            pom {
                name.set(providers.provider { artifactId })
                description.set(providers.provider { project.description ?: artifactId })
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
        // Maven Central through the Sonatype Central Portal. The portal exposes an
        // OSSRH-compatible staging endpoint, so the standard maven-publish flow keeps
        // working: artifacts upload to a staging deployment that is then released from
        // the Central Portal (manually, or automatically when the namespace is set to
        // automatic publishing). Requires a verified namespace, a Central Portal user
        // token (CENTRAL_USERNAME / CENTRAL_PASSWORD), and a GPG signing key.
        maven {
            name = "MavenCentral"
            val releasesUrl =
                uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = providers.environmentVariable("CENTRAL_USERNAME").orNull
                password = providers.environmentVariable("CENTRAL_PASSWORD").orNull
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
