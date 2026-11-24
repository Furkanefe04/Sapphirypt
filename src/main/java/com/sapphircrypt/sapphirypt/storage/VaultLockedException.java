package com.sapphircrypt.sapphirypt.storage;

/**
 * Thrown when the vault is already locked by another process or the current session has been locked/closed.
 */
public class VaultLockedException extends VaultException {
    public VaultLockedException(String message) {
        super(message);
    }

    public VaultLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
