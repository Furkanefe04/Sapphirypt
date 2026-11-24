package com.sapphircrypt.sapphirypt.storage;

/**
 * Thrown when AES-GCM tag or SHA-256 integrity verification fails on vault records or blocks.
 */
public class VaultIntegrityException extends VaultException {
    public VaultIntegrityException(String message) {
        super(message);
    }

    public VaultIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
