package com.example.digitalsphere.domain;

import com.example.digitalsphere.domain.model.ValidationResult;

/**
 * All session-related business rules in one place.
 * Pure Java — no Android imports, fully unit-testable.
 *
 * SRS references: FR-07, FR-08, FR-09, FR-34, FR-35
 */
public class SessionManager {

    private static final int DEFAULT_DURATION_MINUTES = 5;
    private static final int MAX_DURATION_MINUTES     = 180;
    private static final int MIN_DURATION_MINUTES     = 1;

    /**
     * FR-07: Derive a session ID from a raw session name.
     * "CS 101" → "cs101"  |  "  Math  " → "math"
     */
    public String createSessionId(String rawName) {
        if (rawName == null) return "";
        return rawName.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * FR-06: Session name must be non-empty and at least 2 characters.
     */
    public ValidationResult validateSessionName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return ValidationResult.error("Session name cannot be empty. Enter a name like CS101.");
        }
        if (name.trim().length() < 2) {
            return ValidationResult.error("Session name is too short. Use at least 2 characters.");
        }
        return ValidationResult.ok();
    }

    /**
     * FR-34: Student name must be non-empty.
     */
    public ValidationResult validateStudentName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return ValidationResult.error("Please enter your full name.");
        }
        return ValidationResult.ok();
    }

    /**
     * FR-35: Session input from student must be non-empty.
     */
    public ValidationResult validateSessionInput(String sessionInput) {
        if (sessionInput == null || sessionInput.trim().isEmpty()) {
            return ValidationResult.error("Enter the session name given by your professor.");
        }
        return ValidationResult.ok();
    }

    /**
     * FR-08 / FR-09: Parse duration. Returns default if input is blank.
     * Throws IllegalArgumentException with a user-readable message on bad input.
     */
    public int parseDuration(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return DEFAULT_DURATION_MINUTES;
        }
        int value;
        try {
            value = Integer.parseInt(rawInput.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Duration must be a whole number (e.g. 5).");
        }
        if (value < MIN_DURATION_MINUTES || value > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                "Duration must be between " + MIN_DURATION_MINUTES +
                " and " + MAX_DURATION_MINUTES + " minutes.");
        }
        return value;
    }

    public int getDefaultDuration() {
        return DEFAULT_DURATION_MINUTES;
    }

    /**
     * Builds the pseudo device ID used on the professor's side when a student
     * is detected via BLE beacon. Deterministic so the duplicate check works.
     * Format: "beacon_[normalised_name]_[sessionId]"
     */
    public String buildPseudoDeviceId(String studentName, String sessionId) {
        String safe = studentName.trim().toLowerCase().replaceAll("\\s+", "_");
        return "beacon_" + safe + "_" + sessionId;
    }
}
