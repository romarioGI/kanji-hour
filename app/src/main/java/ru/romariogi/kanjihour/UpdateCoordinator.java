package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes wallpaper/photo/settings writes and updates from every entry point. */
public final class UpdateCoordinator {
    private static final String TAG = "KanjiHourUpdate";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kanji-hour-updates");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private UpdateCoordinator() {}

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    /** Completion runs on the worker thread; UI callers should use runOnUiThread. */
    public static void refresh(Context context, boolean forceWallpaper, Runnable completion) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                performRefresh(app, forceWallpaper);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Hourly update failed", failure);
                Config.prefs(app).edit().putString("update_error",
                        "Не удалось обновить кандзи. Откройте приложение и повторите попытку.").apply();
            } finally {
                try {
                    HourlyScheduler.schedule(app);
                } finally {
                    if (completion != null) completion.run();
                }
            }
        });
    }

    private static void performRefresh(Context context, boolean forceWallpaper) {
        SharedPreferences prefs = Config.prefs(context);
        long now = System.currentTimeMillis();
        long hour = Math.floorDiv(now, HourlyScheduler.HOUR_MILLIS);
        Kanji kanji = KanjiRepository.getAtTime(context, now);

        // A launcher failure must not prevent an independent lock-screen update.
        try {
            KanjiWidgetProvider.updateAll(context, kanji);
            prefs.edit().remove("update_error").apply();
        } catch (RuntimeException launcherFailure) {
            Log.w(TAG, "Home widget update failed", launcherFailure);
            prefs.edit().putString("update_error",
                    "Не удалось обновить домашний виджет. Попробуйте добавить его заново.").apply();
        }

        if (!prefs.getBoolean("lock_enabled", false)) return;
        if (wallpaperWasReplaced(context, prefs)) {
            prefs.edit().putBoolean("lock_enabled", false).remove("lock_last_wallpaper_id").remove("lock_last_hour").putString("lock_error",
                    "Обои экрана блокировки были изменены. Обновление кандзи остановлено, "
                            + "чтобы сохранить ваш выбор. Для включения снова нажмите «Применить».")
                    .apply();
            return;
        }
        if (!forceWallpaper && prefs.getLong("lock_last_hour", Long.MIN_VALUE) == hour) return;

        try {
            int wallpaperId = WallpaperRenderer.apply(context, kanji);
            // Persist the hour only after Android has accepted the new wallpaper.
            prefs.edit().putLong("lock_last_hour", hour)
                    .putLong("lock_last_success", System.currentTimeMillis())
                    .putInt("lock_last_wallpaper_id", wallpaperId)
                    .remove("lock_error").apply();
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Lock wallpaper update failed", failure);
            String message = failure.getMessage();
            prefs.edit().putString("lock_error",
                    "Не удалось обновить экран блокировки. "
                            + ((message == null || message.trim().isEmpty())
                            ? "Откройте приложение и выберите фото заново." : message))
                    .apply();
        }
    }

    public static boolean wallpaperWasReplaced(Context context) {
        return wallpaperWasReplaced(context, Config.prefs(context));
    }

    private static boolean wallpaperWasReplaced(Context context, SharedPreferences prefs) {
        int lastId = prefs.getInt("lock_last_wallpaper_id", 0);
        if (lastId <= 0) return false;
        try {
            int currentId = WallpaperManager.getInstance(context)
                    .getWallpaperId(WallpaperManager.FLAG_LOCK);
            // A negative ID means there is no separate lock wallpaper anymore.
            // Zero and exceptions are treated as unavailable OEM responses.
            return currentId != 0 && currentId != lastId;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Wallpaper identifier unavailable; preserving enabled state", unavailable);
            return false;
        }
    }
}
