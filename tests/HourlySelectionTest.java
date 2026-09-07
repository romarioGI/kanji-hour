import ru.romariogi.kanjihour.HourlySelection;

/** Plain Java assertions: no emulator, Android SDK, or third-party test library. */
public final class HourlySelectionTest {
    private static int checks;

    public static void main(String[] args) {
        long[] seeds = {0L, 1L, -1L, 918273645L, Long.MIN_VALUE, Long.MAX_VALUE};
        int[] sizes = {1, 2, 3, 17, 77, 80, 101};
        long[] hours = {0L, 1L, -1L, -500000L, 500000L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long seed : seeds) {
            for (int size : sizes) {
                for (long hour : hours) {
                    int index = HourlySelection.indexForHour(hour, seed, size);
                    require(index >= 0 && index < size, "index out of bounds");
                    require(index == HourlySelection.indexForHour(hour, seed, size),
                            "selection is not stable");
                    if (size == 1) {
                        require(index == 0, "single entry must have index zero");
                    }
                }
                for (long start : new long[] {-3L * size, -size - 3L, -1L, 0L, 500001L}) {
                    boolean[] seen = new boolean[size];
                    for (int offset = 0; offset < size; offset++) {
                        int index = HourlySelection.indexForHour(start + offset, seed, size);
                        require(!seen[index], "repeat within one full cycle");
                        seen[index] = true;
                        require(index == HourlySelection.indexForHour(start + offset + size,
                                seed, size), "cycle must repeat at size hours");
                    }
                }
                if (size > 1) {
                    for (long hour = -3L * size; hour <= 3L * size; hour++) {
                        require(HourlySelection.indexForHour(hour, seed, size)
                                        != HourlySelection.indexForHour(hour + 1, seed, size),
                                "adjacent hour repeat, including cycle boundary");
                    }
                }
            }
        }
        boolean different = false;
        for (int hour = 0; hour < 80; hour++) {
            if (HourlySelection.indexForHour(hour, 1L, 80)
                    != HourlySelection.indexForHour(hour, 2L, 80)) {
                different = true;
            }
        }
        require(different, "different seeds must not always produce the same permutation");
        expectInvalid(0);
        expectInvalid(-1);
        expectInvalid(Integer.MIN_VALUE);
        System.out.println("HourlySelectionTest: " + checks + " checks passed");
    }

    private static void expectInvalid(int size) {
        try {
            HourlySelection.indexForHour(0L, 0L, size);
            throw new AssertionError("expected IllegalArgumentException for size=" + size);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
