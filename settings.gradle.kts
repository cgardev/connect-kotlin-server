pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

rootProject.name = "connect-rpc-kotlin-server"

includeBuild("build-logic")

// Apps
include("project:app-server-spring")

// Libs
include("project:lib-connect-server")
