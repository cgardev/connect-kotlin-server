package com.metalogenia.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AppServerSpring

fun main(args: Array<String>) {
    runApplication<AppServerSpring>(*args)
}
