package io.github.stevenijm777.nrmc.capture.util;

import java.util.Random;

/**
 * ULID (48-bit millisecond timestamp + 80 random bits, Crockford base32).
 * Randomness comes from the caller so plans can generate reproducible ids from a seed.
 */
public final class Ulid {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final long MASK_40 = (1L << 40) - 1;

    private Ulid() {
    }

    public static String of(long timestampMillis, Random random) {
        char[] out = new char[26];
        long time = timestampMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        long high = random.nextLong() & MASK_40;
        long low = random.nextLong() & MASK_40;
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (high & 31)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (low & 31)];
            low >>>= 5;
        }
        return new String(out);
    }
}
