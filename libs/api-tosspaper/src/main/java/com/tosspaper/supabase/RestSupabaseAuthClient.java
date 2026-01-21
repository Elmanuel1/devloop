package com.tosspaper.supabase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.config.SupabaseProperties;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Supabase REST API implementation of AuthInvitationClient.
 * Uses OkHttp to call Supabase Auth endpoints.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestSupabaseAuthClient implements AuthInvitationClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SupabaseProperties supabaseProperties;

    @Override
    @SneakyThrows
    public void inviteUserByEmail(String email, Map<String, Object> metadata) {
        log.info("Inviting user by email: {}", email);

            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("email", email);
            if (metadata != null && !metadata.isEmpty()) {
                requestBody.put("data", metadata);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // Call Supabase /auth/v1/invite endpoint
            String url = supabaseProperties.getUrl() + "/auth/v1/invite";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + supabaseProperties.getServiceRoleKey())
                    .addHeader("apikey", supabaseProperties.getServiceRoleKey())
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                var body = response.body() != null ? response.body().string() : null ;
                if (!response.isSuccessful()) {
                    log.error("Failed to invite user {}: HTTP {} {}", email, response.code(), body);

                    // Check if this is a 422 error with "email_exists" error code
                    if (response.code() == 422 && body != null) {
                        var errorJson = objectMapper.readTree(body);
                        String errorCode = errorJson.path("error_code").asText();
                        if ("email_exists".equals(errorCode)) {
                            String errorMsg = errorJson.path("msg").asText("User already exists");
                            log.info("User already exists in Supabase: {}", email);
                            throw new UserAlreadyExistsException(email, errorMsg);
                        }
                    }

                    throw new IOException("Unexpected response code: " + response.code());
                }

                log.info("Successfully invited user: {}", body);
            }
    }

    @Override
    @SneakyThrows
    public boolean userExists(String email) {
        log.info("Checking if user exists: {}", email);

        String url = supabaseProperties.getUrl() + "/auth/v1/admin/users?email=" + email;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + supabaseProperties.getServiceRoleKey())
                .addHeader("apikey", supabaseProperties.getServiceRoleKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                log.error("Failed to check user existence {}: HTTP {} {}", email, response.code(), body);
                throw new IOException("Unexpected response code: " + response.code());
            }

            // Parse response to check if users array is not empty
            var jsonResponse = objectMapper.readTree(body);
            var users = jsonResponse.path("users");
            boolean exists = users.isArray() && !users.isEmpty();

            log.info("User {} exists: {}", email, exists);
            return exists;
        }
    }

    @Override
    @SneakyThrows
    public String createUser(String email, String password) {
        log.info("Creating user: {}", email);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("email", email);
        requestBody.put("password", password);
        requestBody.put("email_confirm", true); // Auto-confirm email

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        String url = supabaseProperties.getUrl() + "/auth/v1/admin/users";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + supabaseProperties.getServiceRoleKey())
                .addHeader("apikey", supabaseProperties.getServiceRoleKey())
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                log.error("Failed to create user {}: HTTP {} {}", email, response.code(), body);
                throw new IOException("Unexpected response code: " + response.code());
            }

            // Parse response to extract user ID
            var jsonResponse = objectMapper.readTree(body);
            String userId = jsonResponse.path("id").asText();

            log.info("Successfully created user: {} with ID: {}", email, userId);
            return userId;
        }
    }

    @Override
    @SneakyThrows
    public String getUserIdByEmail(String email) {
        log.info("Getting user ID for email: {}", email);

        String url = supabaseProperties.getUrl() + "/auth/v1/admin/users?email=" + email;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + supabaseProperties.getServiceRoleKey())
                .addHeader("apikey", supabaseProperties.getServiceRoleKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                log.error("Failed to get user ID {}: HTTP {} {}", email, response.code(), body);
                throw new IOException("Unexpected response code: " + response.code());
            }

            // Parse response to extract user ID
            var jsonResponse = objectMapper.readTree(body);
            var users = jsonResponse.path("users");

            if (!users.isArray() || users.isEmpty()) {
                throw new IllegalArgumentException("User not found: " + email);
            }

            String userId = users.get(0).path("id").asText();
            log.info("Found user ID {} for email: {}", userId, email);
            return userId;
        }
    }
}
