import ru.romariogi.kanjihour.WallpaperImportPolicy;
import ru.romariogi.kanjihour.WallpaperImportPolicy.Route;

/** Tests selection safety and allocation bounds; does not exercise Android/HyperOS APIs. */
public final class WallpaperImportPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        expect(Route.OWN_PHOTO, 15, 90, 15, true, false, false);
        expect(Route.OWN_SOLID, 15, 90, 15, false, false, false);
        expect(Route.LOCK_IMAGE, 16, 90, 15, true, false, false);
        expect(Route.LOCK_IMAGE, 16, 90, 15, true, false, true);
        expect(Route.LOCK_IMAGE, 16, -1, -1, false, false, false);
        expect(Route.LIVE, 16, 90, 15, true, true, false);
        expect(Route.SHARED_IMAGE, -1, 90, -1, true, false, false);
        expect(Route.SHARED_IMAGE, -2, 90, -2, false, false, false);
        expect(Route.LIVE, -1, 90, -1, false, false, true);
        expect(Route.LIVE, -1, 90, -1, false, true, false);
        expect(Route.UNAVAILABLE, 0, 90, 0, true, false, false);
        expect(Route.UNAVAILABLE, -1, -1, -1, true, false, false);
        expect(Route.UNAVAILABLE, -1, 0, -1, true, false, false);

        require(WallpaperImportPolicy.unchanged(10, -1, 10, -1), "same lock");
        require(WallpaperImportPolicy.unchanged(10, 4, 10, 8), "unrelated home update");
        require(!WallpaperImportPolicy.unchanged(10, -1, 11, -1), "lock update");
        require(!WallpaperImportPolicy.unchanged(-1, 10, -1, 11), "shared image update");
        require(!WallpaperImportPolicy.unchanged(-1, 10, 14, 10), "new separate lock");
        require(!WallpaperImportPolicy.unchanged(14, 10, -1, 10), "separate lock removed");
        require(WallpaperImportPolicy.unchanged(-1, 10, -1, 10), "same shared image");

        for (int[] size : new int[][] {{1080, 2400}, {1, 1}, {2800, 2800}, {2801, 1},
                {10000, 5000}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
            int sample = WallpaperImportPolicy.sampleSize(size[0], size[1], 2800);
            require(sample > 0 && (sample & (sample - 1)) == 0, "power of two");
            require(((long) Math.max(size[0], size[1]) + sample - 1) / sample <= 2800,
                "decode memory bound");
            if (sample > 1) require(((long) Math.max(size[0], size[1]) + sample / 2 - 1) / (sample / 2) > 2800,
                "avoid unnecessary quality loss");
        }
        expectInvalid(0, 100, 2800);
        expectInvalid(100, -1, 2800);
        expectInvalid(100, 100, 0);
        System.out.println("WallpaperImportPolicyTest: " + checks + " checks passed");
    }

    private static void expect(Route expected, int lockId, int systemId, int ownId,
            boolean ownSource, boolean lockLive, boolean systemLive) {
        Route actual = WallpaperImportPolicy.select(lockId, systemId, ownId, ownSource, lockLive, systemLive);
        require(actual == expected, "expected " + expected + " but got " + actual);
    }

    private static void expectInvalid(int width, int height, int limit) {
        try {
            WallpaperImportPolicy.sampleSize(width, height, limit);
            throw new AssertionError("invalid dimensions accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
