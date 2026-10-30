package com.ecommerce.backend.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Creates and hashes opaque tokens: refresh tokens, email verification and password reset links.
 *
 * <ul>
 *   <li>256 random bits from {@link SecureRandom}, the JDK's cryptographically strong source.
 *       {@code java.util.Random} or {@code UUID.randomUUID()} strings are not acceptable here.</li>
 *   <li>Encoded as Base64URL without padding: 43 characters, safe in URLs and cookies.</li>
 *   <li>Stored as lowercase hex SHA-256. High-entropy input makes a slow hash unnecessary, and a
 *       deterministic hash allows an indexed lookup (see AUTH.md section 3).</li>
 * </ul>
 */
@Component
public class OpaqueTokens {

    static final int TOKEN_BYTES = 32;
    public static final int ENCODED_LENGTH = 43;
    public static final String FORMAT_REGEX = "^[A-Za-z0-9_-]{43}$";

    private final SecureRandom random = new SecureRandom();

    /** A raw token for the client plus the hash for the database. */
    public record Generated(String raw, String hash) {
        @Override
        public String toString() {
            return "OpaqueToken[hash=" + hash.substring(0, 8) + "...]";
        }
    }

    public Generated generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generated(raw, hash(raw));
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
