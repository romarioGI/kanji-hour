package ru.romariogi.kanjihour;

/** Pure selection rules: a missing lock image must never silently select the home image. */
public final class WallpaperImportPolicy {
    public enum Route { OWN_PHOTO, OWN_SOLID, LOCK_IMAGE, SHARED_IMAGE, LIVE, UNAVAILABLE }

    private WallpaperImportPolicy() {}

    public static boolean isOwnWallpaper(int lockId, int lastOwnId) {
        return lockId > 0 && lastOwnId > 0 && lockId == lastOwnId;
    }

    public static Route select(int lockId, int systemId, int lastOwnId,
                               boolean hasOwnSource, boolean lockLive, boolean systemLive) {
        if (isOwnWallpaper(lockId, lastOwnId)) {
            return hasOwnSource ? Route.OWN_PHOTO : Route.OWN_SOLID;
        }
        if (lockLive) return Route.LIVE;
        if (lockId > 0) return Route.LOCK_IMAGE;
        if (lockId == 0) return Route.UNAVAILABLE;
        // Only a negative ID documents that there is no separate lock wallpaper.
        if (systemLive) return Route.LIVE;
        return systemId > 0 ? Route.SHARED_IMAGE : Route.UNAVAILABLE;
    }

    public static boolean unchanged(int lockBefore, int systemBefore, int lockAfter, int systemAfter) {
        return lockBefore == lockAfter && (lockBefore >= 0 || systemBefore == systemAfter);
    }

    /** Power-of-two sampling bounds even unusually large system wallpaper files. */
    public static int sampleSize(int width, int height, int maxDimension) {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            throw new IllegalArgumentException("Invalid wallpaper dimensions");
        }
        int sample = 1;
        int largest = Math.max(width, height);
        while (((long) largest + sample - 1L) / sample > maxDimension) {
            if (sample > Integer.MAX_VALUE / 2) throw new IllegalArgumentException("Wallpaper is too large");
            sample *= 2;
        }
        return sample;
    }
}
