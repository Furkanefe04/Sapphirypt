package com.sapphircrypt.sapphirypt.storage;

/**
 * Thrown when structural format validation fails or the vault header/slots are corrupted or unsupported.
 */
public class VaultCorruptedException extends VaultException {
    public VaultCorruptedException(String message) {
        super(message);
    }

    public VaultCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
