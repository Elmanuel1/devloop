package com.tosspaper.emailengine

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration

@SpringBootApplication(
    scanBasePackages = ["com.tosspaper.emailengine", "com.tosspaper.models"],
    exclude = [FlywayAutoConfiguration]
)
class TestApplication {
    static void main(String[] args) {
        SpringApplication.run(TestApplication, args)
    }
}