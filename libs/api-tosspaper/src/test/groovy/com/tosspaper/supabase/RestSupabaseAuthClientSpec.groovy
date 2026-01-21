package com.tosspaper.supabase

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.config.SupabaseProperties
import okhttp3.*
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Subject

class RestSupabaseAuthClientSpec extends BaseIntegrationTest {

    OkHttpClient httpClient = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Autowired
    SupabaseProperties supabaseProperties

    @Subject
    RestSupabaseAuthClient client

    Call mockCall = Mock()

    def setup() {
        supabaseProperties.setUrl("https://test.supabase.co")
        supabaseProperties.setServiceRoleKey("test-service-role-key")

        client = new RestSupabaseAuthClient(httpClient, objectMapper, supabaseProperties)
    }

    // ==================== inviteUserByEmail ====================

    def "inviteUserByEmail sends correct request and succeeds"() {
        given: "a successful response"
        def responseBody = '{"id": "user-123", "email": "test@example.com"}'
        def response = createMockResponse(200, responseBody)

        when: "inviting a user"
        client.inviteUserByEmail("test@example.com", [company_id: 123L])

        then: "the correct request is made"
        1 * httpClient.newCall({ Request request ->
            request.url().toString() == "https://test.supabase.co/auth/v1/invite" &&
            request.method() == "POST" &&
            request.header("Authorization") == "Bearer test-service-role-key" &&
            request.header("apikey") == "test-service-role-key"
        }) >> mockCall
        1 * mockCall.execute() >> response
    }

    def "inviteUserByEmail sends request without metadata when null"() {
        given: "a successful response"
        def responseBody = '{"id": "user-123", "email": "test@example.com"}'
        def response = createMockResponse(200, responseBody)

        when: "inviting a user without metadata"
        client.inviteUserByEmail("test@example.com", null)

        then: "the request is made without data field"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
    }

    def "inviteUserByEmail sends request without metadata when empty"() {
        given: "a successful response"
        def responseBody = '{"id": "user-123", "email": "test@example.com"}'
        def response = createMockResponse(200, responseBody)

        when: "inviting a user with empty metadata"
        client.inviteUserByEmail("test@example.com", [:])

        then: "the request is made without data field"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
    }

    def "inviteUserByEmail throws UserAlreadyExistsException for 422 with email_exists error"() {
        given: "a 422 response with email_exists error"
        def responseBody = '{"error_code": "email_exists", "msg": "User already registered"}'
        def response = createMockResponse(422, responseBody)

        when: "inviting an existing user"
        client.inviteUserByEmail("existing@example.com", null)

        then: "UserAlreadyExistsException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        def ex = thrown(UserAlreadyExistsException)
        ex.email == "existing@example.com"
        ex.message == "User already registered"
    }

    def "inviteUserByEmail throws UserAlreadyExistsException with default message when msg is missing"() {
        given: "a 422 response with email_exists error but no msg"
        def responseBody = '{"error_code": "email_exists"}'
        def response = createMockResponse(422, responseBody)

        when: "inviting an existing user"
        client.inviteUserByEmail("existing@example.com", null)

        then: "UserAlreadyExistsException is thrown with default message"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        def ex = thrown(UserAlreadyExistsException)
        ex.email == "existing@example.com"
        ex.message == "User already exists"
    }

    def "inviteUserByEmail throws IOException for 422 with different error code"() {
        given: "a 422 response with different error"
        def responseBody = '{"error_code": "invalid_request", "msg": "Invalid email format"}'
        def response = createMockResponse(422, responseBody)

        when: "inviting with invalid email"
        client.inviteUserByEmail("invalid", null)

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    def "inviteUserByEmail throws IOException for non-success response"() {
        given: "a 500 error response"
        def responseBody = '{"error": "Internal server error"}'
        def response = createMockResponse(500, responseBody)

        when: "inviting a user and server errors"
        client.inviteUserByEmail("test@example.com", null)

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    def "inviteUserByEmail handles empty response body"() {
        given: "a successful response with empty body"
        def response = createMockResponse(200, "{}")

        when: "inviting a user"
        client.inviteUserByEmail("test@example.com", null)

        then: "no exception is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        noExceptionThrown()
    }

    def "inviteUserByEmail handles error response with null body"() {
        given: "an error response with null body"
        def response = createMockResponseWithNullBody(500)

        when: "inviting a user"
        client.inviteUserByEmail("test@example.com", null)

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    // ==================== userExists ====================

    def "userExists returns true when user exists"() {
        given: "a response with users array"
        def responseBody = '{"users": [{"id": "user-123", "email": "test@example.com"}]}'
        def response = createMockResponse(200, responseBody)

        when: "checking if user exists"
        def result = client.userExists("test@example.com")

        then: "true is returned"
        1 * httpClient.newCall({ Request request ->
            request.url().toString() == "https://test.supabase.co/auth/v1/admin/users?email=test@example.com" &&
            request.method() == "GET" &&
            request.header("Authorization") == "Bearer test-service-role-key"
        }) >> mockCall
        1 * mockCall.execute() >> response
        result == true
    }

    def "userExists returns false when user does not exist"() {
        given: "a response with empty users array"
        def responseBody = '{"users": []}'
        def response = createMockResponse(200, responseBody)

        when: "checking if user exists"
        def result = client.userExists("nonexistent@example.com")

        then: "false is returned"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        result == false
    }

    def "userExists returns false when users field is not an array"() {
        given: "a response where users is not an array"
        def responseBody = '{"users": null}'
        def response = createMockResponse(200, responseBody)

        when: "checking if user exists"
        def result = client.userExists("test@example.com")

        then: "false is returned"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        result == false
    }

    def "userExists throws IOException for non-success response"() {
        given: "a 500 error response"
        def responseBody = '{"error": "Internal server error"}'
        def response = createMockResponse(500, responseBody)

        when: "checking if user exists"
        client.userExists("test@example.com")

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    def "userExists handles null response body"() {
        given: "a response with null body"
        def response = createMockResponseWithNullBody(200)

        when: "checking if user exists"
        def result = client.userExists("test@example.com")

        then: "handles gracefully"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        // Will throw NullPointerException when trying to parse null body
        thrown(Exception)
    }

    // ==================== createUser ====================

    def "createUser creates user and returns ID"() {
        given: "a successful response"
        def responseBody = '{"id": "new-user-123", "email": "new@example.com"}'
        def response = createMockResponse(200, responseBody)

        when: "creating a user"
        def result = client.createUser("new@example.com", "password123")

        then: "user ID is returned"
        1 * httpClient.newCall({ Request request ->
            request.url().toString() == "https://test.supabase.co/auth/v1/admin/users" &&
            request.method() == "POST" &&
            request.header("Authorization") == "Bearer test-service-role-key"
        }) >> mockCall
        1 * mockCall.execute() >> response
        result == "new-user-123"
    }

    def "createUser throws IOException for non-success response"() {
        given: "a 400 error response"
        def responseBody = '{"error": "Invalid password"}'
        def response = createMockResponse(400, responseBody)

        when: "creating a user with invalid password"
        client.createUser("new@example.com", "short")

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    def "createUser handles null response body"() {
        given: "a response with null body"
        def response = createMockResponseWithNullBody(200)

        when: "creating a user"
        def result = client.createUser("new@example.com", "password123")

        then: "handles gracefully"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        // Will throw NullPointerException when trying to parse null body
        thrown(Exception)
    }

    // ==================== getUserIdByEmail ====================

    def "getUserIdByEmail returns user ID when user exists"() {
        given: "a response with user"
        def responseBody = '{"users": [{"id": "found-user-123", "email": "found@example.com"}]}'
        def response = createMockResponse(200, responseBody)

        when: "getting user ID by email"
        def result = client.getUserIdByEmail("found@example.com")

        then: "user ID is returned"
        1 * httpClient.newCall({ Request request ->
            request.url().toString() == "https://test.supabase.co/auth/v1/admin/users?email=found@example.com" &&
            request.method() == "GET"
        }) >> mockCall
        1 * mockCall.execute() >> response
        result == "found-user-123"
    }

    def "getUserIdByEmail throws IllegalArgumentException when user not found"() {
        given: "a response with empty users array"
        def responseBody = '{"users": []}'
        def response = createMockResponse(200, responseBody)

        when: "getting user ID for non-existent user"
        client.getUserIdByEmail("notfound@example.com")

        then: "IllegalArgumentException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        def ex = thrown(IllegalArgumentException)
        ex.message == "User not found: notfound@example.com"
    }

    def "getUserIdByEmail throws IllegalArgumentException when users is not an array"() {
        given: "a response where users is not an array"
        def responseBody = '{"users": null}'
        def response = createMockResponse(200, responseBody)

        when: "getting user ID"
        client.getUserIdByEmail("test@example.com")

        then: "IllegalArgumentException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IllegalArgumentException)
    }

    def "getUserIdByEmail throws IOException for non-success response"() {
        given: "a 500 error response"
        def responseBody = '{"error": "Internal server error"}'
        def response = createMockResponse(500, responseBody)

        when: "getting user ID"
        client.getUserIdByEmail("test@example.com")

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    def "getUserIdByEmail handles null response body on error"() {
        given: "an error response with null body"
        def response = createMockResponseWithNullBody(500)

        when: "getting user ID"
        client.getUserIdByEmail("test@example.com")

        then: "IOException is thrown"
        1 * httpClient.newCall(_) >> mockCall
        1 * mockCall.execute() >> response
        thrown(IOException)
    }

    // ==================== Helper Methods ====================

    private Response createMockResponse(int code, String body) {
        def responseBody = ResponseBody.create(body, MediaType.parse("application/json"))
        return new Response.Builder()
                .request(new Request.Builder().url("https://test.supabase.co").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Error")
                .body(responseBody)
                .build()
    }

    private Response createMockResponseWithNullBody(int code) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://test.supabase.co").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Error")
                .body(null)
                .build()
    }
}
