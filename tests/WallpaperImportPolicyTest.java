import ru.romariogi.kanjihour.WallpaperImportPolicy;
import ru.romariogi.kanjihour.WallpaperImportPolicy.Route;
import ru.romariogi.kanjihour.WallpaperImportPolicy.Recovery;

/** Selection and recovery rules, not Android/HyperOS end-to-end tests. */
public final class WallpaperImportPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        expect(Route.OWN_PHOTO, 15, 90, 15, true, false, false);
        expect(Route.UNAVAILABLE, 15, 90, 15, false, false, false);
        expect(Route.LOCK_IMAGE, 16, 90, 15, true, false, false);
        expect(Route.LOCK_IMAGE, 16, 90, 15, true, false, true);
        expect(Route.LOCK_IMAGE, 16, -1, -1, false, false, false);
        expect(Route.LIVE, 16, 90, 15, true, true, false);
        expect(Route.LIVE, 15, 90, 15, true, true, false);
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
        require(!WallpaperImportPolicy.unchanged(0, 10, 0, 10), "unknown is not safe");
        require(!WallpaperImportPolicy.unchanged(-1, 0, -1, 0), "unknown shared is not safe");

        require(!WallpaperImportPolicy.needsUpdate(15, 15, true, 5, 5, false), "own broadcast does not loop");
        require(WallpaperImportPolicy.needsUpdate(16, 15, true, 5, 5, false), "manual change within same hour");
        require(WallpaperImportPolicy.needsUpdate(-1, 15, true, 5, 5, false), "shared wallpaper replacement");
        require(WallpaperImportPolicy.needsUpdate(15, 15, true, 6, 5, false), "next hour");
        require(WallpaperImportPolicy.needsUpdate(15, 15, true, 10, 5, false), "missed hours catch up");
        require(WallpaperImportPolicy.needsUpdate(15, 15, false, 5, 5, false), "reenable after removal");
        require(WallpaperImportPolicy.needsUpdate(15, 15, true, 5, 5, true), "explicit refresh");
        require(WallpaperImportPolicy.recovery("lock:1", "lock:1", "") == Recovery.CLEAR, "write never started");
        require(WallpaperImportPolicy.recovery("lock:2", "lock:1", "") == Recovery.QUARANTINE, "unknown write result");
        require(WallpaperImportPolicy.recovery("lock:2", "lock:1", "lock:2") == Recovery.BLOCK, "do not reimport own glyph");
        require(WallpaperImportPolicy.recovery("lock:3", "lock:1", "lock:2") == Recovery.CLEAR, "new wallpaper after quarantine");
        require(WallpaperImportPolicy.recovery("shared:3", "lock:1", "lock:3") == Recovery.CLEAR, "screen identity matters");

        for (int[] size : new int[][] {{1080, 2400}, {1, 1}, {2800, 2800}, {2801, 1},
                {10000, 5000}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
            int sample = WallpaperImportPolicy.sampleSize(size[0], size[1], 2800);
            require(sample > 0 && (sample & (sample - 1)) == 0, "power of two");
            require(((long) Math.max(size[0], size[1]) + sample - 1) / sample <= 2800, "decode memory bound");
            if (sample > 1) require(((long) Math.max(size[0], size[1]) + sample / 2 - 1) / (sample / 2) > 2800,
                    "avoid unnecessary quality loss");
        }
        expectInvalid(0, 100, 2800);
        expectInvalid(100, -1, 2800);
        expectInvalid(100, 100, 0);
        System.out.println("WallpaperImportPolicyTest: " + checks + " checks passed");
    }
    private static void expect(Route expected, int lock, int system, int own, boolean source, boolean live, boolean homeLive) {
        Route actual = WallpaperImportPolicy.select(lock, system, own, source, live, homeLive);
        require(actual == expected, "expected " + expected + " but got " + actual);
    }
    private static void expectInvalid(int width, int height, int limit) {
        try { WallpaperImportPolicy.sampleSize(width, height, limit); throw new AssertionError("invalid accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
