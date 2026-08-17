package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.domain.GeoPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A hash of the fields that define "the same logical dispatch request". Lets
 * DispatchRequestService tell a genuine retry (same key, same fingerprint) apart from
 * a caller reusing an idempotency key for a different command (same key, different
 * fingerprint) -- the latter must be rejected, not silently merged into the first
 * request. See docs/DESIGN.md "Idempotency lifecycle".
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(String serviceType, GeoPoint origin, GeoPoint destination) {
        String canonical = serviceType + '|'
                + origin.latitude() + ',' + origin.longitude() + '|'
                + destination.latitude() + ',' + destination.longitude();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
