package com.metalogenia.server.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {

    @GetMapping("/")
    fun root(): Map<String, String> = mapOf("status" to "ok")
}
