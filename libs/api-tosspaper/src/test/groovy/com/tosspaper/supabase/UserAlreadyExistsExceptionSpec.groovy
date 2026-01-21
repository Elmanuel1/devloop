package com.tosspaper.supabase

import spock.lang.Specification

class UserAlreadyExistsExceptionSpec extends Specification {

    def "constructor sets email and message correctly"() {
        given: "email and message values"
        def email = "test@example.com"
        def message = "User already exists in the system"

        when: "creating the exception"
        def exception = new UserAlreadyExistsException(email, message)

        then: "email and message are set correctly"
        exception.email == email
        exception.message == message
    }

    def "getEmail returns the email address"() {
        given: "an exception with an email"
        def exception = new UserAlreadyExistsException("user@domain.com", "Some message")

        when: "getting the email"
        def result = exception.getEmail()

        then: "the email is returned"
        result == "user@domain.com"
    }

    def "exception can be thrown and caught"() {
        when: "throwing the exception"
        throw new UserAlreadyExistsException("throw@test.com", "Test throw")

        then: "exception is caught"
        def ex = thrown(UserAlreadyExistsException)
        ex.email == "throw@test.com"
        ex.message == "Test throw"
    }

    def "exception is a subclass of Exception"() {
        given: "an instance of UserAlreadyExistsException"
        def exception = new UserAlreadyExistsException("test@test.com", "Test")

        expect: "it is an instance of Exception"
        exception instanceof Exception
    }

    def "exception works with null email"() {
        when: "creating exception with null email"
        def exception = new UserAlreadyExistsException(null, "Message with null email")

        then: "exception is created"
        exception.email == null
        exception.message == "Message with null email"
    }

    def "exception works with empty email"() {
        when: "creating exception with empty email"
        def exception = new UserAlreadyExistsException("", "Message with empty email")

        then: "exception is created"
        exception.email == ""
        exception.message == "Message with empty email"
    }
}
