package com.synctank.platform.config.secrets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Day 09 — what GET /health/secrets returns.
 *
 * Deliberately incapable of carrying a credential. A value never enters this record; only
 * whether it is present, where it came from, and an 8-hex-character SHA-256 prefix.
 *
 * The fingerprint is the useful part: it lets you confirm that the string in Secrets Manager
 * is the string you meant to store, by comparing fingerprints rather than values. Eight hex
 * characters is enough to catch a truncated paste or a stale rotation and nothing like enough
 * to reconstruct a 40-character token.
 */
public record SecretsStatus(String provider, String secretId, List<KeyStatus> keys) {

    public record KeyStatus(String property, String purpose, boolean present,
                            String source, String fingerprint) {}

    /** Returned when no initializer ran — see SecretsStatusController for when that happens. */
    public static SecretsStatus notInitialised() {
        return new SecretsStatus("uninitialised", null, List.of());
    }

    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}