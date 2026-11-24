package com.sapphircrypt.sapphirypt.storage;

/**
 * Thrown when password or recovery key authentication fails.
 */
public class VaultAuthenticationException extends VaultException {
    public VaultAuthenticationException(String message) {
        super(message);
    }

    public VaultAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
