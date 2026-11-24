package com.sapphircrypt.sapphirypt.storage;

/**
 * Base checked exception for all Sapphirypt vault errors.
 */
public class VaultException extends Exception {
    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
