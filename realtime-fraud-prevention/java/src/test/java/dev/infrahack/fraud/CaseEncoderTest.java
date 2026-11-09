package dev.infrahack.fraud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaseEncoderTest {
    @Test void deterministicFiniteNormalizedHeldoutRepresentation() {
        for (int d : new int[]{8, 64, 256, 768}) {
            var encoder = new CaseEncoder(d);
            float[] fields = RetrievalBenchmark.fields(1000000);
            float[] q = encoder.encode(fields);
            assertArrayEquals(q, new CaseEncoder(d).encode(fields));
            double norm = 0;
            for (float v : q) { assertTrue(Float.isFinite(v)); norm += (double) v * v; }
            assertEquals(1, norm, 1e-6);
            assertNotEquals(java.util.Arrays.toString(q), java.util.Arrays.toString(encoder.encode(RetrievalBenchmark.fields(0))));
        }
    }
    @Test void rejectsUndefinedDistances() {
        var encoder = new CaseEncoder(8);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new float[199]));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new float[200]));
        var fields = RetrievalBenchmark.fields(1); fields[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(fields));
    }
}
