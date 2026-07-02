package io.infrahack.passwordresetworkflow.util;

import java.security.SecureRandom;

/**
 * Generates 6-digit one-time verification codes ("000000".."999999").
 *
 * <p>{@link SecureRandom} rather than {@code Random}: codes are credentials, so they must be
 * unpredictable even to someone who has observed earlier codes. 6 numeric digits is OA-scope;
 * production hardening lives in the README (longer alphanumeric codes, attempt caps, hashing at rest).
 */
public final class CodeGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
