# connect-rpc-kotlin-server

Clean Kotlin / Spring Boot multi-module server, scaffolded following the Gradle
convention-plugin layout of the `digging-insights-cloud-jvm` reference project.

## Stack

- Kotlin 2.2.10 (JVM toolchain 24)
- Spring Boot 4.0.0
- Gradle 9.1.0 (wrapper included)

## Structure

```
build-logic/                Convention plugins (com.metalogenia.gradle.common.*)
project/app-server-spring/  Spring Boot web application
```

The convention plugins centralize the Kotlin, Spring and dependency-management
configuration so each module only declares what it actually needs.

## Common commands

```bash
./gradlew build                                   # compile + test everything
./gradlew :project:app-server-spring:bootRun      # run the server (port 8080)
./gradlew :project:app-server-spring:test         # run module tests
```

## Notes

RPC (Connect RPC / gRPC) is intentionally not wired up yet. It can be added on
top of this foundation when needed.
