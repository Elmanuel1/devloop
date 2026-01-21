package com.tosspaper.supabase;

import java.util.Map;

/**
 * Client for authentication invitation and user management operations.
 * This interface is provider-agnostic - can be implemented with Supabase, Auth0, Cognito, etc.
 * Allows easy substitution of implementations (REST, SDK, mock, etc.)
 */
public interface AuthInvitationClient {

    /**
     * Invite a user by email to join the application.
     * Sends an invitation email with a signup link.
     *
     * @param email    Email address to invite
     * @param metadata Optional custom user metadata (e.g., company_id)
     * @throws UserAlreadyExistsException if user already exists in the authentication provider
     */
    void inviteUserByEmail(String email, Map<String, Object> metadata) throws UserAlreadyExistsException;

    /**
     * Check if a user exists in the authentication provider.
     *
     * @param email Email address to check
     * @return true if user exists, false otherwise
     */
    boolean userExists(String email);

    /**
     * Create a new user in the authentication provider.
     *
     * @param email    Email address for the new user
     * @param password Password for the new user
     * @return User ID of the created user
     */
    String createUser(String email, String password);

    /**
     * Get the user ID for an existing user by email.
     *
     * @param email Email address to look up
     * @return User ID if found
     * @throws IllegalArgumentException if user not found
     */
    String getUserIdByEmail(String email);
}
