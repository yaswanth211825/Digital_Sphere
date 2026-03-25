package com.example.digitalsphere.domain.model;

/**
 * Immutable result returned by all SessionManager validation methods.
 * Pure Java — no Android imports.
 */
public final class ValidationResult {

    private final boolean valid;
    private final String message;

    private ValidationResult(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult error(String message) {
        return new ValidationResult(false, message);
    }

    public boolean isValid() { return valid; }

    public String getMessage() { return message; }
}
