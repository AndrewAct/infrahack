package dev.infrahack.fraud;

import java.util.Random;

/** Synthetic shape/cost model, not trained fraud semantics. Shared by corpus and held-out queries. */
public final class CaseEncoder {
    public static final String VERSION = "seeded-tanh-200-v1";
    public static final int INPUTS = 200;
    public static final long SEED = 20261026L;
    private final float[][] weights;

    public CaseEncoder(int dimension) {
        if (dimension < 1) throw new IllegalArgumentException("dimension");
        weights = new float[dimension][INPUTS];
        var random = new Random(SEED);
        for (var row : weights) for (int j = 0; j < INPUTS; j++) row[j] = (float) (random.nextGaussian() / Math.sqrt(INPUTS));
    }

    public float[] encode(float[] fields) {
        if (fields.length != INPUTS) throw new IllegalArgumentException("expected 200 standardized fields");
        float[] vector = new float[weights.length];
        double norm = 0;
        for (int i = 0; i < weights.length; i++) {
            double sum = 0;
            for (int j = 0; j < INPUTS; j++) {
                if (!Float.isFinite(fields[j])) throw new IllegalArgumentException("nonfinite input");
                sum += weights[i][j] * fields[j];
            }
            vector[i] = (float) Math.tanh(sum);
            norm += vector[i] * (double) vector[i];
        }
        if (norm == 0) throw new IllegalArgumentException("zero vector");
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) Math.sqrt(norm);
        return vector;
    }
}
