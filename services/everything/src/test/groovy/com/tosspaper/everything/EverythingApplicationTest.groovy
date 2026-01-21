package com.tosspaper.everything

import spock.lang.Specification
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class EverythingApplicationTest extends Specification {

    def "context loads"() {
        expect:
        true
    }
}
