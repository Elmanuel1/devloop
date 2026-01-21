package com.tosspaper.httpsecurity;

public class HttpSecurityConfigurationException extends RuntimeException {
    public HttpSecurityConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpSecurityConfigurationException(Throwable cause) {
        super("Incorrect HttpSecurity configuration.", cause);
    }
}
