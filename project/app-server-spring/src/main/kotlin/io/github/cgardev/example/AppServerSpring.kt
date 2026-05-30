package io.github.cgardev.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AppServerSpring

fun main(args: Array<String>) {
    runApplication<AppServerSpring>(*args)
}
