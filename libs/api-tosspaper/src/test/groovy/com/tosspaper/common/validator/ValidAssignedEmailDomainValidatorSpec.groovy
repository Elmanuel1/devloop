package com.tosspaper.common.validator

import spock.lang.Specification
import spock.lang.Unroll

class ValidAssignedEmailDomainValidatorSpec extends Specification {

    ValidAssignedEmailDomainValidator validator

    def setup() {
        validator = new ValidAssignedEmailDomainValidator()
        validator.allowedDomain = "useassetiq.com"
    }

    @Unroll
    def "should validate email '#email' as #expected"() {
        expect:
        validator.isValid(email, null) == expected

        where:
        email                           | expected
        "manager@useassetiq.com"        | true
        "admin@useassetiq.com"          | true
        "test.user@useassetiq.com"      | true
        "ADMIN@USEASSETIQ.COM"          | true
        "manager@acme.com"              | false
        "admin@gmail.com"               | false
        "test@useassetiq.co"            | false
        "test@useassetiq.com.au"        | false
        null                            | true
        ""                              | true
        "   "                           | true
        "invalid-email"                 | false
        "@useassetiq.com"               | false
        "user@@useassetiq.com"          | false   // multiple @
        "user@"                         | false   // empty domain
        "user@test@useassetiq.com"      | false   // multiple @
        "user@sub.useassetiq.com"       | false   // subdomain
    }

    def "email with whitespace is trimmed and validated"() {
        expect:
        validator.isValid("  user@useassetiq.com  ", null)
    }
}

