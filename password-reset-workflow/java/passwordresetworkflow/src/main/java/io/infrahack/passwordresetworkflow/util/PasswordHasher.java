package io.infrahack.passwordresetworkflow.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Salted SHA-256 password hashing, stored as {@code saltHex:sha256hex(saltHex + rawPassword)}.
 *
 * <p>The per-user random salt defeats rainbow tables; plain SHA-256 is however far too fast to
 * resist offline brute force, so production would swap this for bcrypt/scrypt/argon2 — the stored
 * format keeps that swap local to this class. Raw passwords never leave the call stack.
 */
public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private PasswordHasher() {}

    public static String hash(String rawPassword) {
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        String saltHex = HEX.formatHex(salt);
        return saltHex + ":" + sha256Hex(saltHex + rawPassword);
    }

    public static boolean matches(String rawPassword, String stored) {
        int sep = stored.indexOf(':');
        if (sep <= 0) {
            return false;
        }
        String saltHex = stored.substring(0, sep);
        String expected = stored.substring(sep + 1);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sha256Hex(saltHex + rawPassword).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
