package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes wallpaper/settings writes and updates from every entry point. */
public final class UpdateCoordinator {
    private static final String TAG = "KanjiHourUpdate";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kanji-hour-updates");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private UpdateCoordinator() {}
    public static ExecutorService executor() { return EXECUTOR; }

    public static void refresh(Context context, boolean forceWallpaper, Runnable completion) {
        refresh(context, forceWallpaper, "ui_or_widget", completion);
    }

    /** Completion runs on the worker thread, or immediately if dispatch fails. */
    public static void refresh(Context context, boolean forceWallpaper, String trigger, Runnable completion) {
        Context app = context.getApplicationContext();
        long received = System.currentTimeMillis();
        long started = SystemClock.elapsedRealtime();
        RefreshTask.submit(EXECUTOR, () -> HourlyScheduler.schedule(app), () -> {
            Config.prefs(app).edit().putString("last_trigger", trigger)
                    .putLong("last_trigger_at", received).apply();
            performRefresh(app, forceWallpaper);
        }, failure -> {
            Log.w(TAG, "Hourly update failed", failure);
            Config.prefs(app).edit().putString("update_error",
                    "Не удалось обновить кандзи. Откройте приложение и повторите попытку.").apply();
        }, () -> {
            try {
                Config.prefs(app).edit().putLong("last_refresh_finished_at", System.currentTimeMillis())
                        .putLong("last_refresh_duration_ms", SystemClock.elapsedRealtime() - started).apply();
            } finally {
                if (completion != null) completion.run();
            }
        });
    }

    private static void performRefresh(Context context, boolean forceWallpaper) {
        SharedPreferences prefs = Config.prefs(context);
        long now = System.currentTimeMillis();
        long hour = Math.floorDiv(now, HourlyScheduler.HOUR_MILLIS);
        Kanji kanji = KanjiRepository.getAtTime(context, now);
        try {
            KanjiWidgetProvider.updateAll(context, kanji);
            prefs.edit().remove("update_error").putLong("widget_last_success", System.currentTimeMillis()).apply();
        } catch (RuntimeException launcherFailure) {
            Log.w(TAG, "Home widget update failed", launcherFailure);
            prefs.edit().putString("update_error", "Не удалось обновить домашний виджет.").apply();
        }
        if (!prefs.getBoolean("lock_enabled", false)) return;
        if (wallpaperWasReplaced(context, prefs)) {
            prefs.edit().putBoolean("lock_enabled", false).remove("lock_last_wallpaper_id")
                    .remove("lock_last_hour").putString("lock_error",
                    "Обои экрана блокировки были изменены. Для включения снова нажмите «Применить».").apply();
            return;
        }
        if (!forceWallpaper && prefs.getLong("lock_last_hour", Long.MIN_VALUE) == hour) return;
        try {
            int wallpaperId = WallpaperRenderer.apply(context, kanji);
            prefs.edit().putLong("lock_last_hour", hour)
                    .putLong("lock_last_success", System.currentTimeMillis())
                    .putInt("lock_last_wallpaper_id", wallpaperId).remove("lock_error").apply();
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Lock wallpaper update failed", failure);
            prefs.edit().putString("lock_error", "Не удалось обновить экран блокировки.").apply();
        }
    }

    public static boolean wallpaperWasReplaced(Context context) {
        return wallpaperWasReplaced(context, Config.prefs(context));
    }
    private static boolean wallpaperWasReplaced(Context context, SharedPreferences prefs) {
        int lastId = prefs.getInt("lock_last_wallpaper_id", 0);
        if (lastId <= 0) return false;
        try {
            int currentId = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK);
            return currentId != 0 && currentId != lastId;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Wallpaper identifier unavailable; preserving enabled state", unavailable);
            return false;
        }
    }
}
