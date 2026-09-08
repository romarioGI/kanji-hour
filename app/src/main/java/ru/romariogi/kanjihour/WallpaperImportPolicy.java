package ru.romariogi.kanjihour;

/** Pure rules for identifying a static lock background and reusing its clean source. */
public final class WallpaperImportPolicy {
    public enum Route { OWN_PHOTO, LOCK_IMAGE, SHARED_IMAGE, LIVE, UNAVAILABLE }
    public enum Recovery { CLEAR, QUARANTINE, BLOCK }
    private WallpaperImportPolicy() {}

    public static boolean isOwnWallpaper(int lockId, int lastOwnId) {
        return lockId > 0 && lastOwnId > 0 && lockId == lastOwnId;
    }

    public static Route select(int lockId, int systemId, int lastOwnId,
                               boolean hasOwnSource, boolean lockLive, boolean systemLive) {
        if (lockLive) return Route.LIVE;
        if (isOwnWallpaper(lockId, lastOwnId)) {
            return hasOwnSource ? Route.OWN_PHOTO : Route.UNAVAILABLE;
        }
        if (lockId > 0) return Route.LOCK_IMAGE;
        if (lockId == 0) return Route.UNAVAILABLE;
        if (systemLive) return Route.LIVE;
        return systemId > 0 ? Route.SHARED_IMAGE : Route.UNAVAILABLE;
    }

    public static boolean unchanged(int lockBefore, int systemBefore, int lockAfter, int systemAfter) {
        return lockBefore != 0 && lockBefore == lockAfter
                && (lockBefore > 0 || (systemBefore > 0 && systemBefore == systemAfter));
    }

    public static boolean needsUpdate(int lockId, int ownId, boolean hasGlyph,
                                      long hour, long lastHour, boolean force) {
        return force || !isOwnWallpaper(lockId, ownId) || !hasGlyph || hour != lastHour;
    }

    /** An interrupted write may have installed our glyph before its ID was saved. */
    public static Recovery recovery(String current, String before, String uncertain) {
        if (current.equals(before)) return Recovery.CLEAR;
        if (uncertain.isEmpty()) return Recovery.QUARANTINE;
        return current.equals(uncertain) ? Recovery.BLOCK : Recovery.CLEAR;
    }

    public static int sampleSize(int width, int height, int maxDimension) {
        if (width <= 0 || height <= 0 || maxDimension <= 0)
            throw new IllegalArgumentException("Invalid wallpaper dimensions");
        int sample = 1;
        int largest = Math.max(width, height);
        while (((long) largest + sample - 1L) / sample > maxDimension) {
            if (sample > Integer.MAX_VALUE / 2) throw new IllegalArgumentException("Wallpaper is too large");
            sample *= 2;
        }
        return sample;
    }
}
