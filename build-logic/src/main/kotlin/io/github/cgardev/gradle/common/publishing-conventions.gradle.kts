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
