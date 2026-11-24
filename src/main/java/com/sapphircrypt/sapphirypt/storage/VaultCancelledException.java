package com.sapphircrypt.sapphirypt.storage;

/**
 * Thrown when a user cancels an in-progress vault operation.
 */
public class VaultCancelledException extends VaultException {
    public VaultCancelledException(String message) {
        super(message);
    }
}
