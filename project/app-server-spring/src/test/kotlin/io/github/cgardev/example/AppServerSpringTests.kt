package io.github.cgardev.example

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["connect.server.port=0"])
class AppServerSpringTests {

    @Test
    fun contextLoads() {
    }
}
