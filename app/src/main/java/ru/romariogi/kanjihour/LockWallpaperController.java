package ru.romariogi.kanjihour;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;
import ru.romariogi.kanjihour.CurrentWallpaperImporter.Snapshot;
import ru.romariogi.kanjihour.CurrentWallpaperImporter.Source;

/** All entry points run on UpdateCoordinator's single writer. */
public final class LockWallpaperController {
    private LockWallpaperController() {}

    public static void refresh(Context context, Kanji kanji, long hour, boolean force) throws IOException {
        refresh(context, kanji, hour, force, new RefreshCancellation());
    }

    public static void refresh(Context context, Kanji kanji, long hour, boolean force,
                               RefreshCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        SharedPreferences prefs = Config.prefs(context);
        if (!prefs.getBoolean("lock_enabled", false)) return;
        Snapshot snapshot = checkedSnapshot(context);
        if (!WallpaperImportPolicy.needsUpdate(snapshot.lockId, prefs.getInt("lock_last_wallpaper_id", 0),
                prefs.getBoolean("lock_has_glyph", true), hour, prefs.getLong("lock_last_hour", Long.MIN_VALUE), force)) {
            prefs.edit().remove("lock_error").apply();
            return;
        }
        try (Source source = CurrentWallpaperImporter.read(context, snapshot)) {
            write(context, source, kanji, hour, cancellation);
        }
    }

    public static Bitmap preview(Context context, Kanji kanji, float y, float scale) throws IOException {
        try (Source source = CurrentWallpaperImporter.read(context, checkedSnapshot(context))) {
            Bitmap result = WallpaperRenderer.render(source.file, kanji, 432, y, scale);
            try {
                CurrentWallpaperImporter.ensureCurrent(context, source.snapshot);
                return result;
            } catch (IOException | RuntimeException error) {
                result.recycle();
                throw error;
            }
        }
    }

    /** Stop first. Restore only if the currently installed wallpaper is still ours. */
    public static void disableAndRemove(Context context) throws IOException {
        SharedPreferences prefs = Config.prefs(context);
        persist(prefs.edit().putBoolean("lock_enabled", false));
        HourlyScheduler.schedule(context);
        Snapshot snapshot = checkedSnapshot(context);
        if (!WallpaperImportPolicy.isOwnWallpaper(snapshot.lockId, prefs.getInt("lock_last_wallpaper_id", 0))
                || !prefs.getBoolean("lock_has_glyph", true)) {
            prefs.edit().remove("lock_error").apply();
            return;
        }
        try (Source source = CurrentWallpaperImporter.read(context, snapshot)) {
            write(context, source, null, Long.MIN_VALUE, new RefreshCancellation());
        }
    }

    private static void write(Context context, Source source, Kanji kanji, long hour,
                              RefreshCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        SharedPreferences prefs = Config.prefs(context);
        Bitmap bitmap = WallpaperRenderer.render(source.file, kanji, Config.WALL_WIDTH,
                prefs.getFloat("lock_y", Config.DEFAULT_Y), prefs.getFloat("lock_scale", 1f));
        try {
            cancellation.throwIfCancelled();
            CurrentWallpaperImporter.ensureCurrent(context, source.snapshot);
            cancellation.throwIfCancelled();
            // Preserve the clean file and an intent-to-write BEFORE touching system wallpaper.
            // If the process dies after setBitmap, the next attempt must not import our own glyph.
            source.retain();
            persist(prefs.edit().putString("lock_source_name", source.file.getName())
                    .putString("lock_source_key", source.snapshot.key())
                    .putBoolean("lock_write_pending", true)
                    .putString("lock_write_before", source.snapshot.key()).remove("lock_write_uncertain"));
            try {
                CurrentWallpaperImporter.ensureCurrent(context, source.snapshot);
                cancellation.throwIfCancelled();
            } catch (IOException | RuntimeException changedBeforeWrite) {
                // No system write was attempted: cancellation/known changes are not uncertain.
                persist(prefs.edit().putBoolean("lock_write_pending", false)
                        .remove("lock_write_before").remove("lock_write_uncertain"));
                throw changedBeforeWrite;
            }
            int id = WallpaperRenderer.setLockBitmap(context, bitmap);
            // Do not interrupt an already-started system write or its ownership commit.
            // If killed here, the persisted journal still protects the next process.
            persist(prefs.edit().putInt("lock_last_wallpaper_id", id)
                    .putBoolean("lock_has_glyph", kanji != null).putLong("lock_last_hour", hour)
                    .putLong("lock_last_success", System.currentTimeMillis())
                    .putBoolean("lock_write_pending", false).remove("lock_write_before")
                    .remove("lock_write_uncertain").remove("lock_error"));
            cleanOldSources(context, source.file);
        } finally { bitmap.recycle(); }
    }

    private static Snapshot checkedSnapshot(Context context) throws IOException {
        Snapshot snapshot = CurrentWallpaperImporter.inspect(context);
        SharedPreferences prefs = Config.prefs(context);
        if (!prefs.getBoolean("lock_write_pending", false)) return snapshot;
        WallpaperImportPolicy.Recovery recovery = WallpaperImportPolicy.recovery(snapshot.key(),
                prefs.getString("lock_write_before", ""), prefs.getString("lock_write_uncertain", ""));
        if (recovery == WallpaperImportPolicy.Recovery.CLEAR) {
            persist(prefs.edit().putBoolean("lock_write_pending", false).remove("lock_write_before")
                    .remove("lock_write_uncertain"));
            return snapshot;
        }
        if (recovery == WallpaperImportPolicy.Recovery.QUARANTINE)
            persist(prefs.edit().putString("lock_write_uncertain", snapshot.key()));
        throw new IOException("Предыдущая запись обоев прервалась. Чтобы не наложить второй кандзи, "
                + "установите фон заново в настройках Android.");
    }

    static void persist(SharedPreferences.Editor edit) throws IOException {
        if (!edit.commit()) throw new IOException("Не удалось сохранить состояние. Запись обоев приостановлена.");
    }

    private static void cleanOldSources(Context context, File keep) {
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().startsWith("lock-source-") && file.getName().endsWith(".png") && !file.equals(keep))
                file.delete();
        }
    }
}
