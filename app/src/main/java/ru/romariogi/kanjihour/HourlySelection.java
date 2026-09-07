package ru.romariogi.kanjihour;

import java.util.Random;

/** Deterministic hourly selection, independent of Android and the device timezone. */
public final class HourlySelection {
    private HourlySelection() { }

    /**
     * Selects from a seed-specific shuffled cycle of {@code size} entries.
     * The same permutation repeats every {@code size} hours. Every entry appears
     * exactly once per cycle; adjacent hours differ when size is greater than one,
     * including across the end of a cycle. Negative epoch hours are supported.
     */
    public static int indexForHour(long epochHour, long seed, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (size == 1) {
            return 0;
        }

        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Random random = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            int saved = order[i];
            order[i] = order[other];
            order[other] = saved;
        }
        int position = (int) Math.floorMod(epochHour, (long) size);
        return order[position];
    }
}
