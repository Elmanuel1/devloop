package com.tosspaper.supabase;

/**
 * Exception thrown when attempting to invite a user that already exists in Supabase.
 * Indicates that Supabase returned a 422 error with error_code "email_exists".
 */
public class UserAlreadyExistsException extends Exception {

    private final String email;

    public UserAlreadyExistsException(String email, String message) {
        super(message);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
